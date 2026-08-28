package com.mew.wlfmovie.download

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Log
import com.mew.wlfmovie.database.AppDatabase
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
import java.io.RandomAccessFile
import java.nio.ByteBuffer

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
 */
object DownloadConverter {

    private const val TAG = "DownloadConverter"

    /** Extensión del archivo temporal de conversión. */
    private const val TMP_SUFFIX = "_conv.mp4.tmp"

    /** Cada cuánto se publica el estado Converting (ms). */
    private const val PROGRESS_PUBLISH_MS = 400L

    /** Cada cuánto se valida que la descarga siga existiendo (ms). */
    private const val DB_CHECK_MS = 2_000L

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
            publish(movieId, ConversionState.Failed("archivo no existe"))
            return
        }
        // Ya es MP4 (descarga Remux vieja o ya convertida) → nada que hacer.
        if (!src.name.endsWith(".ts", ignoreCase = true)) {
            removeState(movieId)
            return
        }
        val parent = src.parentFile
        if (parent == null) {
            publish(movieId, ConversionState.Failed("directorio no disponible"))
            return
        }
        val safeId = sanitizeFileName(movieId)
        val dst = File(parent, "movie_${safeId}.mp4")
        val tmp = File(parent, "movie_${safeId}$TMP_SUFFIX")
        // Limpiar tmp huérfano de una conversión interrumpida (app muerta).
        runCatching { tmp.delete() }

        val startMs = SystemClock.elapsedRealtime()
        publish(movieId, ConversionState.Converting(0f, 0L, false))
        Log.i(TAG, "convertOne: iniciando movie=$movieId (${src.name}, ${src.length()} bytes)")

        val result = try {
            remuxFile(movieId, src, tmp, dao) { progress ->
                val elapsed = (SystemClock.elapsedRealtime() - startMs) / 1000L
                publish(movieId, ConversionState.Converting(progress, elapsed, true))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "convertOne: excepción en remux movie=$movieId: ${t.message}", t)
            RemuxResult(ok = false, reason = "error: ${t.message ?: t.javaClass.simpleName}")
        }

        if (result.ok) {
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
                    publish(movieId, ConversionState.Completed)
                    scope.launch {
                        delay(1_500L)
                        removeState(movieId)
                    }
                } else {
                    // DB falló: restaurar — el .ts sigue siendo la fuente.
                    runCatching { dst.delete() }
                    publish(movieId, ConversionState.Failed("no se pudo actualizar la base de datos"))
                }
            } else {
                runCatching { tmp.delete() }
                publish(movieId, ConversionState.Failed("no se pudo escribir el MP4"))
            }
        } else if (result.aborted) {
            runCatching { tmp.delete() }
            // Cancelación/eliminación: sin estado de error, simplemente limpiar.
            removeState(movieId)
            Log.i(TAG, "convertOne: abortada movie=$movieId (${result.reason})")
        } else {
            runCatching { tmp.delete() }
            Log.w(TAG, "convertOne: FALLO movie=$movieId — ${result.reason} (el .ts se conserva)")
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
    )

    /**
     * Remux TS/fMP4-concat → MP4. Copia las pistas de audio/video tal cual
     * (writeSampleData con los buffers del extractor). Los timestamps se
     * normalizan restando el PTS de la PRIMERA muestra (offset global único
     * → el A/V sync queda intacto y el MP4 arranca en ~0).
     */
    private suspend fun remuxFile(
        movieId: String,
        src: File,
        dst: File,
        dao: com.mew.wlfmovie.database.dao.DownloadDao,
        onProgress: (Float) -> Unit,
    ): RemuxResult {
        val startMs = SystemClock.elapsedRealtime()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(src.path)

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
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    val d = runCatching {
                        fmt.getLong(MediaFormat.KEY_DURATION)
                    }.getOrDefault(-1L)
                    if (d > totalDurationUs) totalDurationUs = d
                }
            }
            if (totalDurationUs <= 0L) {
                val scanned = runCatching { scanTsDurationUs(src) }.getOrDefault(-1L)
                if (scanned > 0L) totalDurationUs = scanned
            }
            Log.i(TAG, "remuxFile: movie=$movieId totalDurationUs=$totalDurationUs")

            muxer = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Mapear pistas A/V del extractor al muxer. Si ALGUNA pista A/V no
            // la soporta MediaMuxer → fallar la conversión entera (un MP4 sin
            // video o sin audio sería una película rota silenciosa).
            val trackMap = HashMap<Int, Int>()
            for (t in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(t)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                try {
                    val muxTrack = muxer.addTrack(format)
                    trackMap[t] = muxTrack
                    extractor.selectTrack(t)
                } catch (e: Exception) {
                    return RemuxResult(
                        ok = false,
                        reason = "formato no soportado por MP4: $mime",
                    )
                }
            }
            if (trackMap.isEmpty()) {
                return RemuxResult(ok = false, reason = "el archivo no tiene pistas de audio/video")
            }

            muxer.start()
            val buf = ByteBuffer.allocateDirect(1 shl 20) // 1MB
            val info = MediaCodec.BufferInfo()
            var baseUs = -1L
            var lastPubMs = 0L
            var lastDbCheckMs = 0L
            var aborted = false

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
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break

                val ptsUs = extractor.sampleTime
                if (baseUs < 0L) baseUs = ptsUs
                // coerceAtLeast(0): si la segunda pista arranca unos ms antes
                // que la primera muestra leída, no escribir PTS negativos.
                val outPts = (ptsUs - baseUs).coerceAtLeast(0L)

                val muxTrack = trackMap[trackIdx]
                if (muxTrack != null && size > 0) {
                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    info.set(0, size, outPts, flags)
                    muxer.writeSampleData(muxTrack, buf, info)
                }

                if (totalDurationUs > 0L && now - lastPubMs > PROGRESS_PUBLISH_MS) {
                    lastPubMs = now
                    val frac = (outPts.toFloat() / totalDurationUs.toFloat())
                        .coerceIn(0f, 1f)
                    onProgress(frac)
                }

                extractor.advance()
            }

            if (aborted) {
                return RemuxResult(ok = false, reason = "cancelada", aborted = true)
            }
            muxer.stop()
            return RemuxResult(ok = true, durationMs = SystemClock.elapsedRealtime() - startMs)
        } finally {
            runCatching { extractor.release() }
            runCatching { muxer?.release() }
        }
    }

    // ------------------------------------------------------------------
    // Escaneo PCR (duración de un MPEG-TS sin índice)
    // ------------------------------------------------------------------

    /**
     * Duración de un MPEG-TS en µs: primer PCR de la cabeza vs último PCR de
     * la cola. PCR base = 33 bits @ 90kHz → µs = ticks * 100 / 9.
     * Devuelve -1 si no se puede determinar.
     */
    private fun scanTsDurationUs(file: File): Long {
        val len = file.length()
        if (len < 1880L) return -1L
        RandomAccessFile(file, "r").use { raf ->
            val headLen = minOf(len, 256L * 1024L).toInt()
            val head = ByteArray(headLen)
            raf.readFully(head)
            val firstPcr = firstPcrBase(head)
            if (firstPcr < 0L) return -1L

            val tailLen = minOf(len, 512L * 1024L).toInt()
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
