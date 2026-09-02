package com.mew.wlfmovie.download

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.utils.CrashLogger
import com.mew.wlfmovie.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch

/**
 * WLFMOVIE V6.4.5: Cola de conversión TS → MP4 (FASE 2 de la opción
 * "Convertir descargas a MP4" de Configuración → Descargas).
 *
 * QUÉ HACE:
 *  - Cuando una descarga HLS termina (o el usuario activa la opción en
 *    Configuración, o la app arranca con la opción activada), la película
 *    (.ts = MPEG-TS o fMP4 concatenado) se encola aquí.
 *  - Un worker convierte UNA a la vez con REMUX (MediaExtractor + MediaMuxer):
 *    copia los streams de audio/video SIN recodificar → rápido (limitado por
 *    disco, minutos), sin pérdida de calidad, sin internet, sin FFmpeg.
 *  - Al terminar: el .ts se reemplaza por movie_<id>.mp4, se actualiza la ruta
 *    en la base de datos (DownloadedMovie.filePath) y TODO el sistema pasa a
 *    ver un MP4 normal:
 *      * Reproducción offline: igual (ExoPlayer).
 *      * "Enviar al PC": el servidor ya sirve .mp4 directo por HTTP con Range
 *        (video nativo del navegador, seek instantáneo) — sin hls.js, sin
 *        segmentos, sin fragParsingError.
 *
 * ESTADO EN DETALLES DE LA PELÍCULA:
 *  - Queued(position)    → "En cola para convertir (posición X)"
 *  - Converting(p, t)    → "Convirtiendo a MP4… X%" (o mm:ss si no hay %)
 *  - Completed           → "MP4 listo" (1.5s, luego desaparece)
 *  - Failed(reason)      → "No se pudo convertir a MP4…" (el .ts se conserva
 *                          y se sigue enviando al PC por el camino HLS de
 *                          siempre — degradación elegante, nunca se pierde
 *                          la película).
 *
 * PROGRESO: MediaExtractor no expone bytes consumidos y la duración de un TS
 * no está indexada, así que:
 *   1) KEY_DURATION del MediaFormat de las pistas (válido para fMP4-concat
 *      con moov — MediaExtractor NO expone la duración del archivo);
 *   2) si no → escaneo de PCR: primer PCR de la cabeza del archivo vs último
 *      PCR de la cola (ventana de 256KB / 512KB) → duración exacta;
 *   3) si tampoco → progreso indeterminado, se muestra solo el tiempo.
 *
 * SEGURIDAD:
 *  - Se escribe a movie_<id>_conv.mp4.tmp y SOLO al éxito se renombra y se
 *    borra el .ts: si la app muere / se cancela / falla el disco, el .ts
 *    original queda intacto.
 *  - La conversión se auto-aborta si el usuario borra la descarga (row de DB
 *    desaparece o archivo fuente se elimina) — chequeado cada 2s.
 *  - La cola NO persiste entre reinicios; al arrancar la app con la opción
 *    activada se re-encolan automáticamente los .ts pendientes (las fallidas
 *    reintentan una vez por arranque).
 *
 * DIAGNÓSTICO (V6.4.5 build-fix 2): todo fallo de conversión se reporta al
 * CrashLogger (el "Errores recopilados" de Ajustes → Acerca de, solo debug)
 * con:
 *   - la ETAPA exacta que falló (setDataSource / leer pistas / addTrack /
 *     muxer.start / copiar muestras / muxer.stop / renombrar / BD),
 *   - el stack trace completo si hubo excepción,
 *   - un volcado técnico: primeros 16 bytes del .ts en hex+ascii (revela al
 *     instante si es MPEG-TS real, fMP4 o contenido indescifrable tipo HLS
 *     cifrado sin descifrar), lista de pistas con mime+duración y cuántas
 *     muestras alcanzó a escribir.
 * El motivo corto también se muestra en la tarjeta de detalles de la
 * película (entre paréntesis).
 *
 * V6.4.6 — REPARACIÓN + GUARD DE COBERTURA:
 *  - CAUSA RAÍZ descubierta con el diagnóstico: algunos CDNs anti-leech
 *    responden HTTP 200 con una IMAGEN PNG en lugar del segmento de video;
 *    el descargador viejo concatenaba esa basura y el .ts resultante
 *    arrancaba con firma PNG → "Failed to instantiate extractor".
 *  - ANTES de remuxear se analiza la estructura del .ts: si arranca con
 *    basura pero después hay video, se remuxea desde el primer byte válido
 *    (salto transparente — sin copiar GBs extra en API 26+). Si no hay
 *    video, falla con un motivo claro en lugar del críptico del extractor.
 *  - GUARD DE COBERTURA: si el MP4 resultante cubriría menos del 70% de la
 *    duración del .ts (segmentos corruptos intercalados), la conversión
 *    FALLA A PROPÓSITO — nunca se entrega un MP4 truncado ni se borra el
 *    .ts por un video a medias.
 *
 * V6.4.7 — MP4 VERIFICADO + BLINDAJE DE TIMELINE:
 *  - SÍNTOMA que destapó esta versión: el primer MP4 entregado ("MP4
 *    listo") quedaba con el spinner eterno en el reproductor — el seek
 *    mostraba el frame de destino pero el reloj nunca avanzaba. El remux
 *    "terminaba bien" pero producía un timeline que ExoPlayer no tolera.
 *  - BLINDAJE durante la copia (por pista): (a) las muestras previas al
 *    arranque global ya no se escriben clamp-eadas a 0 (antes dejaban
 *    corridas duplicadas en PTS 0); (b) una regresión de PTS mayor a 5s
 *    (reloj hacia atrás por segmento roto) se salta; si más del ~1% de
 *    las muestras cae en ese caso, la conversión FALLA ("el reloj del
 *    video salta hacia atrás"). Las regresiones menores (B-frames) se
 *    escriben tal cual — no tocan el A/V sync.
 *  - VERIFICACIÓN DE REPRODUCCIÓN: antes de renombrar/actualizar BD/borrar
 *    el .ts, el MP4 se reproduce con un ExoPlayer headless (superficie
 *    dummy, volumen 0). Exige READY + reloj avanzando ≥600ms. Si no
 *    arranca → la conversión FALLA y la película SE QUEDA EN .ts (que sí
 *    reproduce). Nunca más se entrega un MP4 que la propia app no puede
 *    reproducir. Si el verificador no puede correr (infraestructura), no
 *    bloquea la conversión.
 *  - Cada conversión exitosa deja una línea "VERIFICADO" en el
 *    recopilador de errores de debug con el volcado por pista (muestras
 *    escritas / re-anclajes por cada pista) — diagnóstico instantáneo si un
 *    MP4 verificado fallara en algún reproductor externo (p.ej. el PC).
 *
 * V6.4.8 — AUDIO/VIDEO SINCRONIZADO (re-timing por pista):
 *  - SÍNTOMA (visto al enviar los MP4 al PC): "reproducían" pero el AUDIO
 *    IBA ADELANTE de la película y el video se veía entrecortado, "como un
 *    juego con lag" a ~10fps (fotos en movimiento). Causa raíz: el remux de
 *    V6.4.6 copiaba los PTS tal cual, y los .ts concatenados de estos CDNs
 *    traen SALTOS DE RELOJ (segmentos cuyo reloj arranca por detrás de lo
 *    ya escrito) → el timeline del video quedaba con el tiempo dando vueltas
 *    → el reproductor descarta los frames "atrasados" → el video se queda
 *    atrás del audio (audio "adelantado") y el cadence se rompe (lag).
 *  - ARREGLO — re-timing MONÓTONO POR PISTA (ver remuxFile): las muestras YA
 *    NO SE SALTAN (V6.4.7 saltaba: eso creaba huecos y desincronizaba); al
 *    detectar un salto de reloj la pista se RE-ANCLA y su línea de tiempo
 *    continúa justo donde iba → timeline monótono, continuo y sincronizado.
 *  - PROBE inicial: el offset global de normalización es el PTS mínimo REAL
 *    de todas las pistas (no el de la primera muestra leída) → el MP4
 *    arranca en 0 con el A/V alineado, sin ráfagas amontonadas en t=0.
 *  - Guards nuevos: pista declarada pero vacía → fallo honesto; balance de
 *    duración audio↔video (más de 20s de diferencia = pistas desalineadas)
 *    → fallo honesto; re-anclajes desbocados (>1% de las muestras = fuente
 *    que oscila) → fallo honesto. El .ts NUNCA se borra en un fallo.
 *  - Fix de compilación de V6.4.7: CountDownLatch(1) (el constructor pide
 *    Int; con 1L no compilaba).
 */
object DownloadConverter {

    private const val TAG = "DownloadConverter"

    /** Extensión del archivo temporal de conversión. */
    private const val TMP_SUFFIX = "_conv.mp4.tmp"

    /** Cada cuánto se publica el estado Converting (ms). */
    private const val PROGRESS_PUBLISH_MS = 400L

    /** Cada cuánto se valida que la descarga siga existiendo (ms). */
    private const val DB_CHECK_MS = 2_000L

    /** V6.4.6: hasta dónde escanear buscando video tras basura inicial. */
    private const val REPAIR_SCAN_LIMIT = 64L * 1024L * 1024L

    /** V6.4.6: tamaño de bloque del chequeo de salud (sync TS cada 4 MB). */
    private const val HEALTH_BLOCK_BYTES = 4L * 1024L * 1024L

    /** V6.4.6: syncs consecutivos a stride 188 para dar por válido un arranque TS. */
    private const val TS_SYNC_RUN = 8

    /**
     * V6.4.8: regresión de PTS (µs) tolerada por pista antes de considerar
     * que el RELOJ DE LA FUENTE se reinició (segmento con el reloj roto). Al
     * detectarlo la pista se RE-ANCLA: su línea de tiempo continúa justo
     * después de la última muestra escrita (nunca retrocede, no se pierde
     * contenido). Las regresiones MENORES (B-frames, reorden normal de
     * decodificación) se escriben con su PTS tal cual.
     */
    private const val PTS_RESET_TOLERANCE_US = 5_000_000L

    /** V6.4.8: muestras mínimas del probe inicial (cubre el arranque del primer GOP). */
    private const val PROBE_MIN_SAMPLES = 24

    /** V6.4.8: muestras máximas del probe inicial. */
    private const val PROBE_MAX_SAMPLES = 300

    /** V6.4.8: bytes máximos del probe inicial (las muestras se bufferizan en RAM). */
    private const val PROBE_MAX_BYTES = 6L * 1024L * 1024L

    /**
     * V6.4.8: diferencia máxima tolerada (µs) entre la duración de la pista
     * de video y la de audio en el MP4 final. Más que esto = pistas
     * desalineadas (el síntoma "el audio va adelante") → fallo honesto.
     */
    private const val AV_BALANCE_TOLERANCE_US = 20_000_000L

    /** V6.4.7: presupuesto máximo de la verificación de reproducción (ms). */
    private const val VERIFY_TIMEOUT_MS = 15_000L

    /**
     * V6.4.7: cuánto debe avanzar el reloj del video (ms) tras el READY
     * para dar el MP4 por reproducible.
     */
    private const val VERIFY_ADVANCE_MS = 600L

    // ------------------------------------------------------------------
    // Estado observable (mismo patrón que MovieDownloadManager.progressFlow)
    // ------------------------------------------------------------------

    sealed class ConversionState {
        object Idle : ConversionState()
        data class Queued(val position: Int) : ConversionState()
        data class Converting(
            val progress: Float,
            val elapsedSec: Long,
            val hasProgress: Boolean,
        ) : ConversionState()

        object Completed : ConversionState()
        data class Failed(val reason: String) : ConversionState()
    }

    private val stateFlowInternal = MutableStateFlow<Map<String, ConversionState>>(emptyMap())

    fun observeState(movieId: String): Flow<ConversionState> =
        stateFlowInternal.map { it[movieId] ?: ConversionState.Idle }

    fun snapshotState(movieId: String): ConversionState =
        stateFlowInternal.value[movieId] ?: ConversionState.Idle

    // ------------------------------------------------------------------
    // Cola
    // ------------------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueLock = Any()
    private val queue = ArrayDeque<String>()
    private val enqueuedIds = HashSet<String>()

    @Volatile
    private var currentId: String? = null

    @Volatile
    private var abortCurrent = false

    @Volatile
    private var appContext: Context? = null

    private var workerJob: Job? = null

    /**
     * Encola una película para convertir. No-op si la opción está desactivada,
     * si ya está en cola o si ya se está convirtiendo.
     * Devuelve true si se encoló.
     */
    fun enqueue(context: Context, movieId: String): Boolean {
        if (!UserPreferences.convertDownloadsToMp4) return false
        appContext = context.applicationContext
        val added = synchronized(queueLock) {
            if (movieId in enqueuedIds || movieId == currentId) {
                false
            } else {
                queue.addLast(movieId)
                enqueuedIds.add(movieId)
                true
            }
        }
        if (added) {
            publishQueuedStatesLocked()
            ensureWorker()
            Log.i(TAG, "enqueue: movie=$movieId encolada (cola=${queueSnapshot().size})")
        }
        return added
    }

    /**
     * Encola TODAS las descargas existentes que sigan en .ts (la más antigua
     * primero). Se usa al activar el switch en Configuración y al arrancar la
     * app con la opción activada (reanudar cola tras reinicio).
     * Devuelve cuántas se encolaron.
     */
    suspend fun enqueueAllPending(context: Context): Int {
        appContext = context.applicationContext
        if (!UserPreferences.convertDownloadsToMp4) return 0
        val rows = runCatching {
            AppDatabase.getInstance(context.applicationContext).downloadDao().getAll()
        }.getOrDefault(emptyList())
        var count = 0
        // getAll viene por downloadedAtMillis DESC (la más nueva primero) →
        // invertir para convertir la más antigua primero (FIFO justo).
        for (row in rows.asReversed()) {
            if (!row.filePath.endsWith(".ts", ignoreCase = true)) continue
            if (!File(row.filePath).exists()) continue
            if (enqueue(context, row.id)) count++
        }
        if (count > 0) {
            Log.i(TAG, "enqueueAllPending: $count películas encoladas")
        }
        return count
    }

    /**
     * Cancela una película: si está en cola la saca; si se está convirtiendo
     * la aborta (el .tmp se limpia y el .ts queda intacto).
     */
    fun cancel(movieId: String) {
        var removedFromQueue = false
        synchronized(queueLock) {
            removedFromQueue = queue.remove(movieId)
            enqueuedIds.remove(movieId)
            if (removedFromQueue || stateFlowInternal.value.containsKey(movieId)) {
                removeState(movieId)
            }
        }
        // Re-numerar posiciones de la cola (si se sacó uno del medio, los
        // siguientes suben una posición).
        if (removedFromQueue) {
            publishQueuedStatesLocked()
        }
        if (currentId == movieId) {
            abortCurrent = true
        }
    }

    /**
     * Vacía la cola PERO deja terminar la conversión en curso (ya casi está
     * lista y produce un MP4 útil). Se usa al desactivar el switch: no se
     * encolan películas nuevas y las pendientes se descartan.
     */
    fun cancelQueued() {
        synchronized(queueLock) {
            val ids = queue.toList()
            queue.clear()
            enqueuedIds.clear()
            for (id in ids) removeState(id)
        }
    }

    private fun queueSnapshot(): List<String> = synchronized(queueLock) { queue.toList() }

    /** Publica el estado Queued(posición) de cada ítem de la cola. */
    private fun publishQueuedStatesLocked() {
        synchronized(queueLock) {
            stateFlowInternal.value = stateFlowInternal.value.toMutableMap().apply {
                queue.forEachIndexed { idx, id ->
                    put(id, ConversionState.Queued(idx + 1))
                }
            }
        }
    }

    private fun publish(movieId: String, state: ConversionState) {
        stateFlowInternal.value = stateFlowInternal.value.toMutableMap().apply {
            put(movieId, state)
        }
    }

    private fun removeState(movieId: String) {
        stateFlowInternal.value = stateFlowInternal.value.toMutableMap().apply {
            remove(movieId)
        }
    }

    private fun ensureWorker() {
        synchronized(queueLock) {
            if (workerJob?.isActive == true) return
            workerJob = scope.launch { workerLoop() }
        }
    }

    private suspend fun workerLoop() {
        while (true) {
            var id = dequeueNext()
            if (id == null) {
                // Re-verificar tras una pausa breve: cierra la carrera con
                // enqueue() añadiendo justo cuando el worker iba a morir
                // (ensureWorker pudo ver el job aún "activo" en su ventana
                // de completado y no lanzar uno nuevo).
                delay(50L)
                id = dequeueNext()
            }
            if (id == null) return // cola vacía → el worker muere (ensureWorker lo revive)
            // Refrescar posiciones de los que quedan en cola (el que era
            // "posición 2" pasa a ser "posición 1", etc.).
            publishQueuedStatesLocked()
            currentId = id
            abortCurrent = false
            val ctx = appContext
            try {
                if (ctx != null) {
                    convertOne(ctx, id)
                } else {
                    removeState(id)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "workerLoop: error convirtiendo movie=$id: ${t.message}", t)
                CrashLogger.logError(
                    tag = TAG,
                    message = "movie=$id: error inesperado en la cola de conversión: " +
                        "${t.message ?: t.javaClass.simpleName}",
                    throwable = t,
                    context = "conversión TS a MP4",
                )
                removeState(id)
            } finally {
                currentId = null
                abortCurrent = false
            }
        }
    }

    private fun dequeueNext(): String? = synchronized(queueLock) {
        val next = queue.removeFirstOrNull()
        if (next != null) enqueuedIds.remove(next)
        next
    }

    // ------------------------------------------------------------------
    // Conversión individual
    // ------------------------------------------------------------------

    private fun sanitizeFileName(id: String): String = id.replace("[^A-Za-z0-9._-]".toRegex(), "_")

    private suspend fun convertOne(context: Context, movieId: String) {
        val dao = AppDatabase.getInstance(context).downloadDao()
        val row = dao.getById(movieId)
        if (row == null) {
            removeState(movieId)
            return
        }
        val src = File(row.filePath)
        if (!src.exists() || src.length() <= 0L) {
            val r = RemuxResult(
                ok = false,
                reason = "archivo no existe o está vacío",
                stage = "verificar archivo fuente",
            )
            reportConversionFailure(movieId, row.title, src, r)
            publish(movieId, ConversionState.Failed(r.reason ?: "error"))
            return
        }
        // Ya es MP4 (descarga Remux vieja o ya convertida) → nada que hacer.
        if (!src.name.endsWith(".ts", ignoreCase = true)) {
            removeState(movieId)
            return
        }
        val parent = src.parentFile
        if (parent == null) {
            val r = RemuxResult(
                ok = false,
                reason = "directorio no disponible",
                stage = "verificar archivo fuente",
            )
            reportConversionFailure(movieId, row.title, src, r)
            publish(movieId, ConversionState.Failed(r.reason ?: "error"))
            return
        }
        val safeId = sanitizeFileName(movieId)
        val dst = File(parent, "movie_${safeId}.mp4")
        val tmp = File(parent, "movie_${safeId}$TMP_SUFFIX")
        // Limpiar tmp huérfano de una conversión interrumpida (app muerta).
        runCatching { tmp.delete() }

        // WLFMOVIE V6.4.6: ANÁLISIS DE ESTRUCTURA antes de remuxear. Los .ts
        // descargados antes de la validación de segmentos pueden arrancar con
        // basura (PNG/HTML que el CDN entregó en lugar de un segmento). Si
        // después de la basura hay video → se remuxea desde ahí (reparación).
        // Si no hay video → falla con motivo claro y accionable.
        val analysis = analyzeSource(src)
        Log.i(
            TAG,
            "convertOne: análisis movie=$movieId → inicio=${analysis.validStart} " +
                "tipo=${analysis.kind} salud=${analysis.healthyBlocks}/${analysis.totalBlocks}"
        )
        val failFast = when {
            analysis.validStart < 0L && analysis.kind == "fMP4-sin-init" -> RemuxResult(
                ok = false,
                reason = "el archivo está corrupto al inicio y le falta el encabezado fMP4 (init); hay que re-descargar",
                stage = "analizar archivo",
                diag = analysisSummary(analysis),
            )
            analysis.validStart < 0L -> RemuxResult(
                ok = false,
                reason = "el archivo descargado no contiene video (el servidor entregó imágenes u otro contenido en lugar de los segmentos); hay que re-descargar",
                stage = "analizar archivo",
                diag = analysisSummary(analysis),
            )
            analysis.totalBlocks >= 4 && analysis.healthyBlocks * 2 < analysis.totalBlocks -> RemuxResult(
                ok = false,
                reason = "más de la mitad del archivo no es video válido " +
                    "(${analysis.healthyBlocks} de ${analysis.totalBlocks} bloques sanos); hay que re-descargar",
                stage = "analizar archivo",
                diag = analysisSummary(analysis),
            )
            else -> null
        }
        if (failFast != null) {
            reportConversionFailure(movieId, row.title, src, failFast)
            publish(movieId, ConversionState.Failed(failFast.reason ?: "error"))
            return
        }
        if (analysis.junkPrefixBytes > 0L) {
            CrashLogger.logError(
                tag = TAG,
                message = "REPARACIÓN movie=$movieId «${row.title}»: el .ts arranca con " +
                    "${analysis.junkPrefixBytes} bytes de basura (el servidor entregó una " +
                    "imagen/página en lugar de un segmento). Se convierte el video saltando " +
                    "esa basura. Desde V6.4.6 las descargas nuevas ya rechazan segmentos " +
                    "que no son video.",
                context = "conversión TS a MP4",
            )
        }

        val startMs = SystemClock.elapsedRealtime()
        publish(movieId, ConversionState.Converting(0f, 0L, false))
        Log.i(TAG, "convertOne: iniciando movie=$movieId (${src.name}, ${src.length()} bytes)")

        val result = try {
            remuxFile(movieId, src, tmp, dao, analysis.validStart) { progress ->
                val elapsed = (SystemClock.elapsedRealtime() - startMs) / 1000L
                publish(movieId, ConversionState.Converting(progress, elapsed, true))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "convertOne: excepción en remux movie=$movieId: ${t.message}", t)
            RemuxResult(
                ok = false,
                reason = "error: ${t.message ?: t.javaClass.simpleName}",
                stage = "remux",
                throwable = t,
            )
        }

        if (result.ok) {
            // ============================================================
            // V6.4.7: VERIFICACIÓN DE REPRODUCCIÓN con un ExoPlayer real
            // ANTES de tocar nada. Un MP4 que no arranca en el reproductor
            // no reemplaza al .ts (que sí reproduce): la conversión falla
            // honestamente y no se pierde la película. Si el propio
            // verificador no pudo correr (infraestructura), no se bloquea
            // la conversión (comportamiento V6.4.6).
            // ============================================================
            publish(
                movieId,
                ConversionState.Converting(
                    1f,
                    (SystemClock.elapsedRealtime() - startMs) / 1000L,
                    true,
                )
            )
            val verify = verifyMp4Plays(context, tmp)
            Log.i(TAG, "convertOne: verificación movie=$movieId → plays=${verify.plays} (${verify.detail})")
            if (!verify.plays && verify.decisive) {
                runCatching { tmp.delete() }
                val r = RemuxResult(
                    ok = false,
                    reason = "el MP4 generado no arranca en el reproductor " +
                        "(verificación interna); el .ts se conserva",
                    stage = "verificar reproducción",
                    diag = result.diag + " | verificación: ${verify.detail}",
                )
                reportConversionFailure(movieId, row.title, src, r)
                publish(movieId, ConversionState.Failed(r.reason ?: "error"))
                return
            }
            val renamed = runCatching {
                if (dst.exists()) dst.delete()
                tmp.renameTo(dst)
            }.getOrDefault(false)
            if (renamed && dst.length() > 0L) {
                val dbOk = runCatching {
                    dao.updateFilePath(movieId, dst.absolutePath, dst.length())
                }.isSuccess
                if (dbOk) {
                    // Solo borrar el .ts cuando el MP4 ya está en disco Y en DB.
                    runCatching { src.delete() }
                    Log.i(
                        TAG,
                        "convertOne: OK movie=$movieId → ${dst.name} " +
                            "(${dst.length()} bytes, ${result.durationMs}ms)"
                    )
                    // V6.4.7: una línea por conversión exitosa en el
                    // recopilador de debug (mismo canal que REPARACIÓN):
                    // dice si se verificó y QUÉ se escribió por pista — si
                    // algún MP4 vuelve a fallar en un reproductor externo,
                    // este volcado dice exactamente qué tiene adentro.
                    CrashLogger.logError(
                        tag = TAG,
                        message = "VERIFICADO movie=$movieId «${row.title}»: ${dst.name} " +
                            "${dst.length()}B — ${verify.detail} | ${result.diag}",
                        context = "conversión TS a MP4",
                    )
                    publish(movieId, ConversionState.Completed)
                    scope.launch {
                        delay(1_500L)
                        removeState(movieId)
                    }
                } else {
                    // DB falló: restaurar — el .ts sigue siendo la fuente.
                    runCatching { dst.delete() }
                    val r = RemuxResult(
                        ok = false,
                        reason = "no se pudo actualizar la base de datos",
                        stage = "actualizar BD",
                    )
                    reportConversionFailure(movieId, row.title, src, r)
                    publish(movieId, ConversionState.Failed(r.reason ?: "error"))
                }
            } else {
                runCatching { tmp.delete() }
                val r = RemuxResult(
                    ok = false,
                    reason = "no se pudo escribir el MP4 (renombrado falló)",
                    stage = "renombrar temporal",
                )
                reportConversionFailure(movieId, row.title, src, r)
                publish(movieId, ConversionState.Failed(r.reason ?: "error"))
            }
        } else if (result.aborted) {
            runCatching { tmp.delete() }
            // Cancelación/eliminación: sin estado de error, simplemente limpiar.
            removeState(movieId)
            Log.i(TAG, "convertOne: abortada movie=$movieId (${result.reason})")
        } else {
            runCatching { tmp.delete() }
            Log.w(TAG, "convertOne: FALLO movie=$movieId — ${result.reason} (el .ts se conserva)")
            reportConversionFailure(movieId, row.title, src, result)
            publish(movieId, ConversionState.Failed(result.reason ?: "error desconocido"))
        }
    }

    // ------------------------------------------------------------------
    // Remux (MediaExtractor → MediaMuxer), sin recodificar
    // ------------------------------------------------------------------

    private data class RemuxResult(
        val ok: Boolean,
        val reason: String? = null,
        val aborted: Boolean = false,
        val durationMs: Long = 0L,
        /** Etapa exacta que falló (para el CrashLogger y la UI). */
        val stage: String = "",
        /** Excepción original, si la hubo (se reporta completa al CrashLogger). */
        val throwable: Throwable? = null,
        /** Volcado técnico de una línea (head hex, pistas, muestras escritas). */
        val diag: String = "",
    )

    /**
     * Remux TS/fMP4-concat → MP4. Copia las pistas de audio/video tal cual
     * (writeSampleData con los buffers del extractor).
     *
     * V6.4.8 — EL TIMELINE SE CONSTRUYE EN TRES PASOS:
     *  1. PROBE: se bufferizan las primeras muestras (hasta ver todas las
     *     pistas + el arranque del primer GOP) → base global = PTS mínimo
     *     REAL del archivo → el MP4 arranca en 0 con el A/V alineado y sin
     *     ráfagas amontonadas en t=0.
     *  2. RE-TIMING POR PISTA: cada pista lleva una corrección de reloj
     *     acumulada. Si el reloj de la fuente salta hacia atrás (>5s: el
     *     típico segmento del CDN con el reloj reiniciado) o una muestra
     *     cae antes del base global, la pista se RE-ANCLA: continúa justo
     *     después de lo último escrito. El timeline queda monótono y
     *     CONTINUO — esto arregla el "audio adelantado + video a
     *     diapositivas" de los MP4 de V6.4.6 (el video se quedaba atrás y
     *     el reproductor descartaba frames por llegar "tarde").
     *  3. GUARDS (fallo honesto, el .ts se conserva): pista vacía,
     *     re-anclajes desbocados (>1% de las muestras = fuente que oscila),
     *     cobertura <70% de la duración y balance de duración audio↔video
     *     con más de 20s de diferencia.
     *
     * V6.4.6: [startOffset] permite remuxear saltando una basura inicial
     * (imágenes/HTML concatenados por el descargador viejo). En API 26+ se
     * apunta el extractor directamente al tramo válido (sin copiar nada);
     * en APIs viejas se copia el tramo a un .ts recortado temporal.
     */
    private suspend fun remuxFile(
        movieId: String,
        src: File,
        dst: File,
        dao: com.mew.wlfmovie.database.dao.DownloadDao,
        startOffset: Long = 0L,
        onProgress: (Float) -> Unit,
    ): RemuxResult {
        val startMs = SystemClock.elapsedRealtime()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var sourceRaf: RandomAccessFile? = null
        var trimmedFile: File? = null
        // Diagnóstico de una línea que viaja al CrashLogger si algo falla.
        val diag = StringBuilder()
        var stage = "abrir extractor"
        var samplesWritten = 0L
        var lastOutPtsUs = -1L
        try {
            diag.append("file=${src.name} tam=${src.length()}B ")
            diag.append(headInfo(src)).append(' ')
            if (startOffset > 0L) diag.append("salto=${startOffset}B ")
            stage = "setDataSource"
            if (startOffset > 0L) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // API 26+: abrir el .ts y apuntar el extractor DIRECTAMENTE
                    // al tramo válido — sin copiar GBs extra al disco.
                    sourceRaf = RandomAccessFile(src, "r")
                    extractor.setDataSource(
                        sourceRaf.fd,
                        startOffset,
                        src.length() - startOffset,
                    )
                } else {
                    // API < 26: setDataSource(fd, offset, length) no está
                    // garantizado → copiar el tramo válido a un .ts recortado
                    // (temporal, se borra al terminar).
                    trimmedFile = File(src.parentFile, "movie_trim_${sanitizeFileName(movieId)}.ts")
                    runCatching { trimmedFile.delete() }
                    copyTailTo(src, trimmedFile, startOffset)
                    extractor.setDataSource(trimmedFile.path)
                }
            } else {
                extractor.setDataSource(src.path)
            }
            stage = "leer pistas"

            // Duración total para el progreso:
            //  1) KEY_DURATION del MediaFormat de las pistas (la conoce el
            //     contenedor que trae índice — p.ej. fMP4-concat con moov);
            //  2) escaneo PCR (MPEG-TS puro no tiene duración indexada);
            //  3) -1 → progreso indeterminado.
            // OJO: MediaExtractor no expone getDuration(); la duración vive
            // en el MediaFormat de cada pista. Se toma el máximo entre todas
            // (la de video suele ser la más larga/precisa).
            var totalDurationUs = -1L
            for (t in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(t)
                val trackMime = fmt.getString(MediaFormat.KEY_MIME) ?: "?"
                val d = if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    runCatching {
                        fmt.getLong(MediaFormat.KEY_DURATION)
                    }.getOrDefault(-1L)
                } else -1L
                if (d > totalDurationUs) totalDurationUs = d
                diag.append("pista[$t]=$trackMime dur=${d}us ")
            }
            diag.append("totalPistas=${extractor.trackCount} ")
            if (totalDurationUs <= 0L) {
                val scanned = runCatching { scanTsDurationUs(src, startOffset) }.getOrDefault(-1L)
                if (scanned > 0L) totalDurationUs = scanned
            }
            diag.append("durTotal=${totalDurationUs}us ")
            Log.i(TAG, "remuxFile: movie=$movieId totalDurationUs=$totalDurationUs")

            stage = "crear archivo MP4 destino"
            muxer = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Mapear pistas A/V del extractor al muxer. Si ALGUNA pista A/V no
            // la soporta MediaMuxer → fallar la conversión entera (un MP4 sin
            // video o sin audio sería una película rota silenciosa).
            val trackMap = HashMap<Int, Int>()
            val videoTrackIdxs = HashSet<Int>() // V6.4.7: para el blindaje por pista
            for (t in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(t)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                if (mime.startsWith("video/")) videoTrackIdxs.add(t)
                try {
                    stage = "addTrack($mime)"
                    val muxTrack = muxer.addTrack(format)
                    trackMap[t] = muxTrack
                    extractor.selectTrack(t)
                } catch (e: Exception) {
                    return RemuxResult(
                        ok = false,
                        reason = "formato no soportado por MP4: $mime",
                        stage = stage,
                        throwable = e,
                        diag = diag.toString(),
                    )
                }
            }
            if (trackMap.isEmpty()) {
                return RemuxResult(
                    ok = false,
                    reason = "el extractor no encontró pistas de audio/video",
                    stage = "leer pistas",
                    diag = diag.toString(),
                )
            }

            stage = "muxer.start"
            muxer.start()
            stage = "copiar muestras"
            val buf = ByteBuffer.allocateDirect(1 shl 20) // 1MB
            val info = MediaCodec.BufferInfo()
            var baseUs = -1L
            var lastPubMs = 0L
            var lastDbCheckMs = 0L
            var aborted = false
            // V6.4.8: RE-TIMING MONÓTONO POR PISTA. Estado por pista:
            //   corrPerTrack       — corrección acumulada del reloj de la pista
            //                        (crece SOLO al re-anclar un salto de reloj);
            //   maxWrittenPerTrack — PTS de salida máximo escrito por pista;
            //   stepPerTrack       — cadencia estimada (delta típico entre
            //                        muestras) para re-anclar con suavidad;
            //   relockPerTrack     — cuántas veces hubo que re-anclar el reloj.
            val corrPerTrack = HashMap<Int, Long>()
            val maxWrittenPerTrack = HashMap<Int, Long>()
            val stepPerTrack = HashMap<Int, Long>()
            val relockPerTrack = HashMap<Int, Long>()
            val writtenPerTrack = HashMap<Int, Long>()
            for (t in trackMap.keys) {
                corrPerTrack[t] = 0L
                maxWrittenPerTrack[t] = -1L
                stepPerTrack[t] = if (t in videoTrackIdxs) 33_333L else 21_333L
                relockPerTrack[t] = 0L
                writtenPerTrack[t] = 0L
            }

            //
            // Escritura de UNA muestra con re-timing por pista. Regla de oro:
            // el PTS de salida de cada pista nunca retrocede más allá de la
            // tolerancia de B-frames respecto al máximo ya escrito.
            //
            fun retimeWrite(
                trackIdx: Int,
                rawPtsUs: Long,
                isSync: Boolean,
                size: Int,
                data: ByteBuffer,
            ) {
                val mux = muxer ?: return
                val muxTrack = trackMap[trackIdx] ?: return
                val maxW = maxWrittenPerTrack[trackIdx] ?: -1L
                var corr = corrPerTrack[trackIdx] ?: 0L
                var adj = rawPtsUs - baseUs + corr
                val step = (stepPerTrack[trackIdx] ?: 33_333L).coerceIn(1_000L, 100_000L)
                if (adj < 0L || (maxW >= 0L && adj + PTS_RESET_TOLERANCE_US < maxW)) {
                    // RE-ANCLAJE (V6.4.8): el reloj de la fuente saltó hacia
                    // atrás (segmento con el reloj reiniciado) o la muestra
                    // cae antes del base global. En V6.4.7 esta muestra se
                    // SALTABA → huecos → desincronización (el audio se iba
                    // adelante). Ahora la pista CONTINÚA justo después de lo
                    // último escrito: timeline monótono, sin pérdida de
                    // contenido y con el A/V sync conservado.
                    val target = if (maxW < 0L) 0L else maxW + step
                    corr += target - adj
                    corrPerTrack[trackIdx] = corr
                    adj = rawPtsUs - baseUs + corr
                    if (maxW >= 0L) {
                        relockPerTrack[trackIdx] = (relockPerTrack[trackIdx] ?: 0L) + 1L
                    }
                }
                if (maxW >= 0L && adj > maxW) {
                    val delta = adj - maxW
                    if (delta in 1_000L..1_000_000L) stepPerTrack[trackIdx] = delta
                }
                val out = adj.coerceAtLeast(0L)
                val flags = if (isSync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, out, flags)
                mux.writeSampleData(muxTrack, data, info)
                samplesWritten++
                if (out > lastOutPtsUs) lastOutPtsUs = out
                maxWrittenPerTrack[trackIdx] = maxOf(maxW, out)
                writtenPerTrack[trackIdx] = (writtenPerTrack[trackIdx] ?: 0L) + 1L
            }

            //
            // V6.4.8 PROBE: bufferizar las primeras muestras hasta ver todas
            // las pistas (más el arranque del primer GOP) para descubrir el
            // PTS mínimo REAL del archivo. Ese mínimo es el offset global de
            // normalización → el MP4 arranca en 0 con el A/V perfectamente
            // alineado y sin corridas de muestras amontonadas en t=0 (la
            // patología (a) de V6.4.7: audio/video con arranques distintos).
            //
            val probeBuf = ByteBuffer.allocateDirect(1 shl 20)
            val probeSamples = ArrayList<ProbeSample>()
            var probeBytes = 0L
            val probeMinPtsPerTrack = HashMap<Int, Long>()
            var probeDone = false
            fun finishProbe() {
                if (probeDone) return
                probeDone = true
                baseUs = probeMinPtsPerTrack.values.minOrNull() ?: 0L
                diag.append("probe=${probeSamples.size}muestras base=${baseUs}us ")
                for (s in probeSamples) {
                    retimeWrite(s.trackIdx, s.ptsUs, s.isSync, s.bytes.size, ByteBuffer.wrap(s.bytes))
                }
                probeSamples.clear()
            }

            while (true) {
                val now = SystemClock.elapsedRealtime()

                // Abort: switch de cancelación o la descarga se borró.
                if (abortCurrent) {
                    aborted = true
                    break
                }
                if (now - lastDbCheckMs > DB_CHECK_MS) {
                    lastDbCheckMs = now
                    val stillThere = runCatching { dao.getById(movieId) }.getOrNull()
                    if (stillThere == null || !src.exists()) {
                        aborted = true
                        break
                    }
                }

                val trackIdx = extractor.sampleTrackIndex
                if (trackIdx < 0) break
                if (!probeDone) probeBuf.position(0)
                val size = extractor.readSampleData(if (probeDone) buf else probeBuf, 0)
                if (size < 0) break

                val ptsUs = extractor.sampleTime
                val isSync =
                    extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0

                if (!probeDone) {
                    // Probe: guardar la muestra (copia de bytes) y anotar el
                    // PTS mínimo visto por pista.
                    if (size > 0) {
                        val bytes = ByteArray(size)
                        for (i in 0 until size) bytes[i] = probeBuf.get(i)
                        probeSamples.add(ProbeSample(trackIdx, ptsUs, isSync, bytes))
                        probeBytes += size
                        val prevMin = probeMinPtsPerTrack[trackIdx]
                        if (prevMin == null || ptsUs < prevMin) {
                            probeMinPtsPerTrack[trackIdx] = ptsUs
                        }
                    }
                    if (
                        (probeMinPtsPerTrack.size >= trackMap.size &&
                            probeSamples.size >= PROBE_MIN_SAMPLES) ||
                        probeSamples.size >= PROBE_MAX_SAMPLES ||
                        probeBytes >= PROBE_MAX_BYTES
                    ) {
                        finishProbe()
                    }
                } else if (size > 0) {
                    retimeWrite(trackIdx, ptsUs, isSync, size, buf)
                }

                if (probeDone && totalDurationUs > 0L && now - lastPubMs > PROGRESS_PUBLISH_MS) {
                    lastPubMs = now
                    val frac = (lastOutPtsUs.toFloat() / totalDurationUs.toFloat())
                        .coerceIn(0f, 1f)
                    onProgress(frac)
                }

                extractor.advance()
            }
            // EOF (o abort) con el probe sin terminar → flushing de lo
            // bufferizado con el base parcial descubierta.
            finishProbe()

            if (aborted) {
                return RemuxResult(
                    ok = false,
                    reason = "cancelada",
                    aborted = true,
                    stage = stage,
                    diag = diag.toString(),
                )
            }
            // WLFMOVIE V6.4.6: guard de muestra cero — antes un extractor que
            // no leía NADA producía un MP4 vacío marcado como OK.
            if (samplesWritten <= 0L) {
                return RemuxResult(
                    ok = false,
                    reason = "no se pudieron leer muestras de audio/video del archivo",
                    stage = "copiar muestras",
                    diag = diag.toString(),
                )
            }
            // WLFMOVIE V6.4.6: GUARD DE COBERTURA. Si el MP4 cubriría menos del
            // 70% de la duración del .ts, el extractor se calló a mitad —
            // señal de segmentos corruptos intercalados. Entregar ese MP4 y
            // borrar el .ts sería PERDER la película a medias: fallar a propósito.
            if (totalDurationUs > 0L && lastOutPtsUs < totalDurationUs * 7L / 10L) {
                val lastMin = lastOutPtsUs / 60_000_000L
                val totalMin = totalDurationUs / 60_000_000L
                return RemuxResult(
                    ok = false,
                    reason = "el video se cortaría en el minuto $lastMin de $totalMin " +
                        "(segmentos corruptos intercalados); hay que re-descargar",
                    stage = "verificar cobertura",
                    diag = diag.toString(),
                )
            }
            // WLFMOVIE V6.4.8: guard de pista vacía. Una pista declarada en
            // el .ts pero sin NINGUNA muestra legible produce un MP4 con un
            // track hueco — reproductores estrictos se atascan en él (una de
            // las caras del spinner eterno).
            val emptyTrack = trackMap.keys.firstOrNull { (writtenPerTrack[it] ?: 0L) <= 0L }
            if (emptyTrack != null) {
                return RemuxResult(
                    ok = false,
                    reason = "la pista ${if (emptyTrack in videoTrackIdxs) "de video" else "de audio"} " +
                        "quedó vacía en el MP4 (archivo fuente incompleto); hay que re-descargar",
                    stage = "verificar pistas",
                    diag = diag.toString() +
                        trackStatsSuffix(trackMap, writtenPerTrack, relockPerTrack, maxWrittenPerTrack),
                )
            }
            // WLFMOVIE V6.4.8: guard de reloj. Cada re-anclaje es una cirugía
            // menor y preserva el contenido; pero si hubo que re-anclar más
            // del ~1% de las muestras, la fuente OSCILA (segmentos con el
            // reloj roto una y otra vez) y el resultado no es confiable.
            val totalRelocks = relockPerTrack.values.sum()
            if (totalRelocks > samplesWritten / 100L + 8L) {
                return RemuxResult(
                    ok = false,
                    reason = "el reloj del video se reinicia sin parar " +
                        "($totalRelocks re-anclajes; segmentos corruptos); hay que re-descargar",
                    stage = "verificar timeline",
                    diag = diag.toString() +
                        trackStatsSuffix(trackMap, writtenPerTrack, relockPerTrack, maxWrittenPerTrack),
                )
            }
            stage = "muxer.stop"
            muxer.stop()
            // WLFMOVIE V6.4.8: guard de balance A/V. Se relee el MP4 recién
            // escrito y se comparan las duraciones de video y audio. Si
            // quedaron desalineadas (el video estirado = "el audio va
            // adelante", el síntoma de los MP4 de V6.4.6) → fallo honesto.
            stage = "verificar balance A/V"
            val balanceProblem = checkTrackBalance(dst)
            if (balanceProblem != null) {
                return RemuxResult(
                    ok = false,
                    reason = balanceProblem,
                    stage = "verificar balance A/V",
                    diag = diag.toString() +
                        trackStatsSuffix(trackMap, writtenPerTrack, relockPerTrack, maxWrittenPerTrack),
                )
            }
            if (totalRelocks > 0L) {
                CrashLogger.logError(
                    tag = TAG,
                    message = "RE-TIMING movie=$movieId: $totalRelocks re-anclajes de " +
                        "reloj aplicados (la fuente tenía saltos de reloj; el MP4 quedó " +
                        "continuo y con el A/V sincronizado)",
                    context = "conversión TS a MP4",
                )
            }
            diag.append("escribio=$samplesWritten muestras relocks=$totalRelocks ")
            diag.append(
                trackStatsSuffix(trackMap, writtenPerTrack, relockPerTrack, maxWrittenPerTrack)
            )
            return RemuxResult(
                ok = true,
                durationMs = SystemClock.elapsedRealtime() - startMs,
                diag = diag.toString(),
            )
        } catch (t: Throwable) {
            diag.append("escribio=$samplesWritten muestras ultimoPtsUs=$lastOutPtsUs ")
            return RemuxResult(
                ok = false,
                reason = t.message ?: t.javaClass.simpleName,
                stage = stage,
                throwable = t,
                diag = diag.toString(),
            )
        } finally {
            runCatching { extractor.release() }
            runCatching { muxer?.release() }
            runCatching { sourceRaf?.close() }
            runCatching { trimmedFile?.delete() }
        }
    }

    /**
     * V6.4.8: resumen por pista para el diag del CrashLogger:
     * "pista[t]=N escritas relock:R fin:123.4s".
     */
    private fun trackStatsSuffix(
        trackMap: Map<Int, Int>,
        written: Map<Int, Long>,
        relocks: Map<Int, Long>,
        maxWritten: Map<Int, Long>,
    ): String = buildString {
        for (t in trackMap.keys.sorted()) {
            append("pista[$t]=esc:${written[t] ?: 0L} ")
            append("relock:${relocks[t] ?: 0L} ")
            append("fin:")
            append(String.format(java.util.Locale.US, "%.1f", (maxWritten[t] ?: 0L) / 1_000_000.0))
            append("s ")
        }
    }

    /** V6.4.8: muestra bufferizada durante el probe inicial. */
    private data class ProbeSample(
        val trackIdx: Int,
        val ptsUs: Long,
        val isSync: Boolean,
        val bytes: ByteArray,
    )

    /**
     * V6.4.8: relee el MP4 recién escrito y compara la duración de la pista
     * de video con la de audio. Si quedaron desalineadas (p.ej. el video se
     * estiró y "el audio va adelante" — el síntoma de los MP4 de V6.4.6),
     * la conversión falla en vez de entregar una película desincronizada.
     * Fail-open: si el archivo no se puede releer, no se bloquea (la
     * verificación de reproducción de V6.4.7 es la puerta final).
     */
    private fun checkTrackBalance(file: File): String? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.path)
            var durV = -1L
            var durA = -1L
            for (t in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(t)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (!fmt.containsKey(MediaFormat.KEY_DURATION)) continue
                val d = runCatching { fmt.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(-1L)
                if (d <= 0L) continue
                if (mime.startsWith("video/") && d > durV) durV = d
                if (mime.startsWith("audio/") && d > durA) durA = d
            }
            if (durV > 0L && durA > 0L &&
                kotlin.math.abs(durV - durA) > AV_BALANCE_TOLERANCE_US
            ) {
                "las pistas quedaron desalineadas: video ${durV / 1_000_000L}s vs audio " +
                    "${durA / 1_000_000L}s; hay que re-descargar"
            } else {
                null
            }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { ex.release() }
        }
    }

    // ------------------------------------------------------------------
    // V6.4.7: verificación de reproducción ANTES de tocar el .ts
    // ------------------------------------------------------------------

    /**
     * Resultado de la verificación de reproducción.
     *
     * @param decisive true si el verificador corrió completo y su veredicto
     *   es fiable (hay que hacerle caso). false = fallo de infraestructura
     *   del propio verificador (no se pudo crear el player, etc.) → el
     *   llamador NO debe bloquear la conversión por esto.
     */
    private data class VerifyOutcome(
        val plays: Boolean,
        val decisive: Boolean,
        val detail: String,
    )

    /**
     * V6.4.7: reproduce el MP4 recién generado con un ExoPlayer REAL
     * (headless, sin UI, volumen 0, superficie dummy para que la pista de
     * video también se seleccione) y exige que:
     *   1) llegue a STATE_READY, y
     *   2) el reloj de reproducción avance ≥[VERIFY_ADVANCE_MS].
     *
     * ¿POR QUÉ? Los MP4 remuxeados pueden ser estructuralmente válidos para
     * muxers tolerantes (VLC) y aun así no avanzar en ExoPlayer (spinner
     * eterno; el seek muestra el frame pero el tiempo no corre). Verificar
     * con el MISMO motor que usa la app —antes de borrar el .ts— convierte
     * ese caso en un fallo honesto de conversión: la película se queda en
     * .ts (que sí reproduce) en vez de quedarse en un MP4 mudo.
     *
     * Se ejecuta en un HandlerThread propio (ExoPlayer exige Looper) y
     * bloquea al worker hasta [VERIFY_TIMEOUT_MS] como máximo.
     */
    private fun verifyMp4Plays(context: Context, file: File): VerifyOutcome {
        val thread = HandlerThread("WlfMp4Verify")
        // OJO: CountDownLatch recibe Int — con 1L no compila (fue el error
        // de compilación reportado en V6.4.7).
        val done = CountDownLatch(1)
        val outcome = arrayOfNulls<VerifyOutcome>(1)
        try {
            thread.start()
            val handler = Handler(thread.looper)
            handler.post {
                var player: ExoPlayer? = null
                var surfaceTexture: SurfaceTexture? = null
                var surface: Surface? = null
                try {
                    val p = ExoPlayer.Builder(context).build()
                    player = p
                    // Superficie dummy: sin superficie ExoPlayer no selecciona
                    // la pista de video y solo verificaríamos el audio.
                    surfaceTexture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        SurfaceTexture(false)
                    } else {
                        @Suppress("DEPRECATION")
                        SurfaceTexture(0)
                    }
                    surfaceTexture?.setDefaultBufferSize(16, 16)
                    val dummySurface = Surface(surfaceTexture)
                    surface = dummySurface
                    p.setVideoSurface(dummySurface)
                    p.volume = 0f
                    var playErr: String? = null
                    p.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            playErr = "${error.errorCodeName}: ${error.message?.take(100)}"
                        }
                    })
                    p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    p.prepare()
                    p.play()
                    val startedAt = SystemClock.elapsedRealtime()
                    var readyAtMs = -1L
                    var maxPosMs = 0L
                    while (true) {
                        if (playErr != null) break
                        val state = p.playbackState
                        val pos = p.currentPosition
                        if (pos > maxPosMs) maxPosMs = pos
                        if (state == Player.STATE_READY) {
                            if (readyAtMs < 0L) readyAtMs = SystemClock.elapsedRealtime() - startedAt
                            if (maxPosMs >= VERIFY_ADVANCE_MS) break
                        }
                        if (state == Player.STATE_ENDED) break
                        if (SystemClock.elapsedRealtime() - startedAt >= VERIFY_TIMEOUT_MS) break
                        SystemClock.sleep(100L)
                    }
                    val durMs = if (p.duration == C.TIME_UNSET) -1L else p.duration
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    val verdict = when {
                        playErr != null -> VerifyOutcome(
                            plays = false,
                            decisive = true,
                            detail = "player error ${playErr}",
                        )
                        readyAtMs >= 0L && maxPosMs >= VERIFY_ADVANCE_MS -> VerifyOutcome(
                            plays = true,
                            decisive = true,
                            detail = "ready=${readyAtMs}ms avance=${maxPosMs}ms dur=${durMs}ms verif=${elapsed}ms",
                        )
                        readyAtMs >= 0L -> VerifyOutcome(
                            // Llegó a READY pero el reloj quedó congelado
                            // (patología de timeline): MP4 no reproducible.
                            plays = false,
                            decisive = true,
                            detail = "ready pero reloj congelado en ${maxPosMs}ms " +
                                "tras ${elapsed}ms (dur=${durMs}ms)",
                        )
                        else -> VerifyOutcome(
                            plays = false,
                            decisive = true,
                            detail = "nunca llegó a READY en ${elapsed}ms " +
                                "(pos=${maxPosMs}ms dur=${durMs}ms)",
                        )
                    }
                    outcome[0] = verdict
                } catch (t: Throwable) {
                    // Infraestructura del verificador (no el archivo): no
                    // decidir sobre esto.
                    outcome[0] = VerifyOutcome(
                        plays = false,
                        decisive = false,
                        detail = "verificador no disponible: ${t.javaClass.simpleName}: ${t.message?.take(80)}",
                    )
                } finally {
                    runCatching { player?.clearVideoSurface() }
                    runCatching { player?.release() }
                    runCatching { surface?.release() }
                    runCatching { surfaceTexture?.release() }
                    done.countDown()
                }
            }
            // El verificador corre a lo sumo VERIFY_TIMEOUT_MS + margen de
            // arranque; si ni así terminó, es fallo de infraestructura.
            val finished = done.await(VERIFY_TIMEOUT_MS + 10_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            val r = outcome[0]
            if (!finished || r == null) {
                return VerifyOutcome(
                    plays = false,
                    decisive = false,
                    detail = "verificador no respondió a tiempo",
                )
            }
            return r
        } finally {
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------
    // V6.4.6: análisis de estructura del .ts (reparación de basura inicial)
    // ------------------------------------------------------------------

    /** Resultado del análisis de estructura del archivo fuente. */
    private data class SourceAnalysis(
        /** Offset del primer byte de video válido (-1 = no hay video). */
        val validStart: Long,
        /** "TS" | "fMP4" | "fMP4-sin-init" | "nada". */
        val kind: String,
        /** Bloques de 4MB con sync TS/cajas moof sanos. */
        val healthyBlocks: Int,
        val totalBlocks: Int,
        /** Bytes de basura antes del video (0 si el archivo está limpio). */
        val junkPrefixBytes: Long,
    )

    private fun analysisSummary(a: SourceAnalysis): String =
        "inicio=${if (a.validStart < 0L) "no hay video" else "${a.validStart}B"} " +
            "tipo=${a.kind} salud=${a.healthyBlocks}/${a.totalBlocks} " +
            "basuraInicial=${a.junkPrefixBytes}B"

    /**
     * Analiza la estructura del .ts:
     *  1. Camino rápido: ¿arranca limpio? (sync TS en 0 con corrida de
     *     syncs, o caja ftyp en 0).
     *  2. Si no: escaneo profundo de hasta [REPAIR_SCAN_LIMIT] buscando el
     *     arranque válido (corrida de 8 syncs TS a stride 188, o caja ftyp).
     *     8 syncs consecutivos = falso positivo prácticamente imposible
     *     sobre datos aleatorios ((1/256)^8).
     *  3. Chequeo de salud: cada 4MB se mira si hay sync TS (o cajas moof)
     *     — detecta basura intercalada sin leer el archivo entero.
     */
    private fun analyzeSource(src: File): SourceAnalysis {
        val len = src.length()
        if (len <= 0L) return SourceAnalysis(-1L, "nada", 0, 0, 0L)
        RandomAccessFile(src, "r").use { raf ->
            // 1) Camino rápido.
            val head = ByteArray(1024)
            val n = raf.read(head)
            if (n >= 8) {
                if (head[0] == 0x47.toByte() && hasTsSyncStride(head, 0, minOf(5, head.size / 188))) {
                    val (h, t) = healthScan(raf, len, 0L)
                    return SourceAnalysis(0L, "TS", h, t, 0L)
                }
                if (boxTypeAt(head, 0) == "ftyp") {
                    val (h, t) = healthScan(raf, len, 0L)
                    return SourceAnalysis(0L, "fMP4", h, t, 0L)
                }
            }
            // 2) Escaneo profundo del prefijo — DESDE 0 (el camino rápido solo
            // descartó un arranque limpio; la basura puede ser de 1 byte).
            raf.seek(0L)
            val scanLimit = minOf(len, REPAIR_SCAN_LIMIT)
            var window = ByteArray(0) // cola del bloque anterior (overlap)
            var windowFileStart = 0L
            var filePos = 0L
            var moofSeen = false
            val chunk = ByteArray(1024 * 1024)
            while (filePos < scanLimit) {
                val read = raf.read(chunk)
                if (read <= 0) break
                val combined: ByteArray
                val combinedStart: Long
                if (window.isEmpty()) {
                    combined = chunk.copyOf(read)
                    combinedStart = filePos
                } else {
                    combined = window + chunk.copyOf(read)
                    combinedStart = windowFileStart
                }
                val limit = combined.size - (TS_SYNC_RUN * 188 + 8)
                var i = 0
                while (i <= limit) {
                    if (combined[i] == 0x47.toByte() &&
                        hasTsSyncStride(combined, i, TS_SYNC_RUN)
                    ) {
                        val start = combinedStart + i
                        val (h, t) = healthScan(raf, len, start)
                        return SourceAnalysis(start, "TS", h, t, start)
                    }
                    if (i + 8 <= combined.size) {
                        if (isBoxType(combined, i, "ftyp")) {
                            val start = combinedStart + i
                            val (h, t) = healthScan(raf, len, start)
                            return SourceAnalysis(start, "fMP4", h, t, start)
                        }
                        if (isBoxType(combined, i, "moof")) moofSeen = true
                    }
                    i++
                }
                // Conservar la cola para no perder un hallazgo en la frontera.
                val keep = TS_SYNC_RUN * 188 + 8
                val from = maxOf(0, combined.size - keep)
                window = combined.copyOfRange(from, combined.size)
                windowFileStart = combinedStart + from
                filePos += read
            }
            return if (moofSeen) {
                SourceAnalysis(-1L, "fMP4-sin-init", 0, 0, len)
            } else {
                SourceAnalysis(-1L, "nada", 0, 0, len)
            }
        }
    }

    /**
     * Chequeo de salud: mira una ventana de 576B en cada frontera de 4MB
     * (desde [from]) y cuenta las que tienen sync TS o cajas moof. Detecta
     * basura intercalada sin leer el archivo entero.
     */
    private fun healthScan(raf: RandomAccessFile, len: Long, from: Long): Pair<Int, Int> {
        val window = ByteArray(576)
        var healthy = 0
        var total = 0
        var pos = from
        while (pos < len - 576L) {
            val ok = runCatching {
                raf.seek(pos)
                raf.readFully(window)
            }.isSuccess
            if (!ok) break
            total++
            // OJO con los bordes: para leer window[i + 376] hace falta
            // i + 376 <= size - 1 → rango hasta size - 2*188 - 1.
            val tsOk = (0..(window.size - 2 * 188 - 1)).any { i ->
                window[i] == 0x47.toByte() &&
                    window[i + 188] == 0x47.toByte() &&
                    window[i + 376] == 0x47.toByte()
            }
            val fmp4Ok = (0..(window.size - 8)).any { i -> isBoxType(window, i, "moof") }
            if (tsOk || fmp4Ok) healthy++
            pos += HEALTH_BLOCK_BYTES
        }
        return healthy to total
    }

    /** ¿Hay [count] syncs TS (0x47) consecutivos a stride 188 desde [pos]? */
    private fun hasTsSyncStride(buf: ByteArray, pos: Int, count: Int): Boolean {
        for (k in 0 until count) {
            val idx = pos + k * 188
            if (idx >= buf.size) return false
            if (buf[idx] != 0x47.toByte()) return false
        }
        return true
    }

    /** Tipo de caja ISO-BMFF en [pos] (bytes 4-7), o "" si no alcanza el buffer. */
    private fun boxTypeAt(buf: ByteArray, pos: Int): String {
        if (pos < 0 || pos + 8 > buf.size) return ""
        return String(buf, pos + 4, 4, Charsets.US_ASCII)
    }

    /**
     * ¿La caja ISO-BMFF en [pos] es de tipo [type]? Sin allocations (el
     * escaneo profundo llama esto por CADA byte — un String por posición
     * serían millones de allocations).
     */
    private fun isBoxType(buf: ByteArray, pos: Int, type: String): Boolean {
        if (pos < 0 || pos + 8 > buf.size) return false
        for (k in 0 until 4) {
            if (buf[pos + 4 + k] != type[k].code.toByte()) return false
        }
        return true
    }

    /** Copia el tramo [from, EOF) de [src] a [dst] (fallback API < 26). */
    private fun copyTailTo(src: File, dst: File, from: Long) {
        RandomAccessFile(src, "r").use { raf ->
            raf.seek(from)
            FileOutputStream(dst).use { out ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val r = raf.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Reporte de fallos al recopilador de errores de debug
    // ------------------------------------------------------------------

    /**
     * V6.4.5 build-fix 2: reporta un fallo de conversión al CrashLogger
     * (Ajustes → Acerca de → "Errores recopilados", visible solo en debug;
     * en release queda únicamente en logcat). Dos entradas por fallo:
     *  1) la humana: película, etapa exacta y motivo;
     *  2) el volcado técnico: head hex+ascii del .ts, pistas, muestras.
     */
    private fun reportConversionFailure(
        movieId: String,
        title: String,
        src: File,
        result: RemuxResult,
    ) {
        val stageTxt = if (result.stage.isNotBlank()) " en la etapa '${result.stage}'" else ""
        CrashLogger.logError(
            tag = TAG,
            message = "movie=$movieId «$title» no se pudo convertir a MP4$stageTxt: " +
                "${result.reason ?: "error desconocido"}. El .ts queda intacto.",
            throwable = result.throwable,
            context = "conversión TS a MP4",
        )
        CrashLogger.logError(
            tag = TAG,
            message = "diag movie=$movieId: ${result.diag.ifBlank { "sin volcado" }} | " +
                "src=${src.absolutePath} existe=${src.exists()} " +
                "tam=${if (src.exists()) src.length() else -1}B",
            context = "conversión TS a MP4",
        )
    }

    /**
     * Volcado de los primeros 16 bytes del archivo: hex + ascii + una
     * clasificación rápida del contenedor. Revela al instante si el .ts es
     * MPEG-TS real (sync 0x47), fMP4 ('ftyp'/'moov') o contenido
     * indescifrable (HLS cifrado AES-128 guardado sin descifrar = bytes
     * aleatorios).
     */
    private fun headInfo(file: File): String {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(16)
                val n = raf.read(buf)
                if (n <= 0) return "head=(archivo vacío)"
                val hex = (0 until n).joinToString(" ") {
                    String.format(java.util.Locale.US, "%02x", buf[it].toInt() and 0xFF)
                }
                val ascii = buildString {
                    for (i in 0 until n) {
                        val c = buf[i].toInt() and 0xFF
                        append(if (c in 0x20..0x7E) c.toChar() else '.')
                    }
                }
                val kind = when {
                    buf[0] == 0x47.toByte() -> "MPEG-TS sync 0x47"
                    ascii.contains("ftyp") || ascii.contains("moov") -> "MP4/fMP4"
                    else -> "no reconocido (¿HLS cifrado?)"
                }
                "head=$hex ascii=[$ascii] tipo=$kind"
            }
        } catch (t: Throwable) {
            "head=(no legible: ${t.javaClass.simpleName})"
        }
    }

    // ------------------------------------------------------------------
    // Escaneo PCR (duración de un MPEG-TS sin índice)
    // ------------------------------------------------------------------

    /**
     * Duración de un MPEG-TS en µs: primer PCR de la cabeza vs último PCR de
     * la cola. PCR base = 33 bits @ 90kHz → µs = ticks * 100 / 9.
     * [startOffset] corre la ventana de cabeza al tramo válido (V6.4.6:
     * reparación de basura inicial). La ventana de cola es de 4MB para
     * tolerar basura también al final.
     * Devuelve -1 si no se puede determinar.
     */
    private fun scanTsDurationUs(file: File, startOffset: Long = 0L): Long {
        val len = file.length()
        if (len - startOffset < 1880L) return -1L
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(startOffset)
            val headLen = minOf(len - startOffset, 256L * 1024L).toInt()
            val head = ByteArray(headLen)
            raf.readFully(head)
            val firstPcr = firstPcrBase(head)
            if (firstPcr < 0L) return -1L

            val tailLen = minOf(len, 4L * 1024L * 1024L).toInt()
            val tail = ByteArray(tailLen)
            raf.seek(len - tailLen)
            raf.readFully(tail)
            val lastPcr = lastPcrBase(tail)
            if (lastPcr < 0L || lastPcr <= firstPcr) return -1L

            val ticks = lastPcr - firstPcr
            return ticks * 100L / 9L
        }
    }

    private fun firstPcrBase(buf: ByteArray): Long {
        var i = 0
        while (i + 12 <= buf.size) {
            if (buf[i] == 0x47.toByte() && isTsSyncRun(buf, i)) {
                val pcr = pcrBaseAt(buf, i)
                if (pcr >= 0L) return pcr
                i += 188
            } else {
                i++
            }
        }
        return -1L
    }

    private fun lastPcrBase(buf: ByteArray): Long {
        var result = -1L
        var i = 0
        while (i + 12 <= buf.size) {
            if (buf[i] == 0x47.toByte() && isTsSyncRun(buf, i)) {
                val pcr = pcrBaseAt(buf, i)
                if (pcr >= 0L) result = pcr
                i += 188
            } else {
                i++
            }
        }
        return result
    }

    /** Verifica que haya al menos 3 sync bytes consecutivos a stride 188. */
    private fun isTsSyncRun(buf: ByteArray, pos: Int): Boolean {
        if (pos + 188 >= buf.size) return true // solo queda 1 paquete
        if (buf[pos + 188] != 0x47.toByte()) return false
        if (pos + 376 < buf.size && buf[pos + 376] != 0x47.toByte()) return false
        return true
    }

    /**
     * PCR base (33 bits) del paquete TS en [pos], o -1 si no tiene PCR.
     * Requiere: adaptation_field_control con payload-only=10|11... es decir
     * afc==2||3, adaptation_field_length >= 7 y PCR_flag==1.
     */
    private fun pcrBaseAt(buf: ByteArray, pos: Int): Long {
        val afc = (buf[pos + 3].toInt() shr 4) and 0x3
        if (afc != 2 && afc != 3) return -1L
        val afLen = buf[pos + 4].toInt() and 0xFF
        if (afLen < 7) return -1L
        if (buf[pos + 5].toInt() and 0x10 == 0) return -1L // PCR_flag
        // Layout del campo PCR (6 bytes = 48 bits, MSB primero):
        //   base(33 bits) | reserved(6 bits) | extension(9 bits)
        // → base = (campo48 shr 15) and 0x1FFFFFFFF.
        var field = 0L
        for (k in 0 until 6) {
            field = (field shl 8) or (buf[pos + 6 + k].toLong() and 0xFFL)
        }
        return (field shr 15) and 0x1FFFFFFFFL
    }
}
