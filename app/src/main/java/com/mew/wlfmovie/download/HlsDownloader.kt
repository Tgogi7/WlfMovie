package com.mew.wlfmovie.download

import android.util.Log
import com.mew.wlfmovie.utils.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * WLFMOVIE V6.3: Descargador de HLS (m3u8) con segmentos en paralelo, optimizado para velocidad.
 *
 * Esta clase NO es exclusiva de CineHax: el [MovieDownloadManager] ahora hace scraper a TODOS
 * los providers (CuevanaEu, CineCalidad, PoseidonHD2, Pelisplusto, PelisflixHd, SeriesFlix,
 * FlixLatam, SoloLatino, CableVisionHD, CineCity, Doramasflix, CineHax) — el mismo set de
 * servers que TmdbProvider.getServers() cuando el usuario va a ver una pelicula.
 *
 * El usuario selecciona el idioma en el dialogo; este descargador baja el mejor server
 * para ese idioma (el primer HLS resuelto por el extractor del provider correspondiente).
 *
 * Estrategia para maximizar velocidad:
 *  1. Resuelve el master playlist → elige la variante de mayor BANDWIDTH (mejor calidad).
 *  2. Parsea el media playlist → lista de segmentos + init segment si hay.
 *  3. Descarga segmentos en paralelo (16 concurrentes por defecto) a temp files `seg_N.ts`.
 *     Cada segmento tiene hasta 5 reintentos con backoff exponencial — esto
 *     recupera de 429/5xx transitorios del CDN sin perder todo el progreso.
 *  4. Resume a nivel segmento: si la descarga falla y se re-arranca, los segmentos ya
 *     descargados en el dir temporal se reutilizan (chequeo de tamanno > 0).
 *  5. Concatena init + segmentos en orden al archivo final `.ts`.
 *  6. WLFMOVIE V6.4.6: cada respuesta se VALIDA — si el CDN entregó una
 *     imagen (PNG/JPEG/GIF) o una página HTML en lugar del segmento, se
 *     rechaza y la descarga falla con un motivo claro. (Antes esa basura
 *     se concatenaba sin más: salían .ts que arrancaban con firma PNG,
 *     imposibles de reproducir/convertir y origen de los fragParsingError
 *     históricos en el player del PC.)
 *  7. Limpia temp files.
 *
 * WLFMOVIE V6.3.3 — FIX CRITICO "la descarga se pausa al final y no se guarda":
 *  En V6.3.1/V6.3.2 el ticker se lanzaba DENTRO de `coroutineScope { ... }` pero
 *  `tickerActive.set(0)` se ejecutaba DESPUES de que el scope terminara. Como
 *  `coroutineScope` espera a TODOS los hijos (incluido el ticker, que solo
 *  termina cuando tickerActive != 1), el scope NUNCA terminaba: la descarga
 *  bajaba el 100% de los segmentos y se quedaba muerta ANTES de concatenar.
 *  - En V6.3.1 (sin watchdog) se congelaba para siempre → "se pauso en 732.7 MB".
 *  - En V6.3.2 (con watchdog de 30s) el watchdog mataba la descarga 30s despues
 *    de completarla → "descargó 4 GB, se pausó y se canceló" (Toy Story 1026 MB,
 *    Obsesión 1.15 GB, Avatar 4 GB — todas descargas COMPLETAS que morían al final).
 *  FIX V6.3.3: la concatenacion ahora corre DENTRO del scope, el ticker se
 *  detiene explicitamente (cancel del Job) cuando termina el guardado, y el
 *  watchdog se DESACTIVA durante la fase de guardado (es I/O local, no red).
 *
 * WLFMOVIE V6.3.3 — Watchdog con intervenciones progresivas:
 *  Antes: 30s sin progreso → cancelar TODO. Ahora: 30s sin progreso → cancelar
 *  las conexiones atascadas (Call.cancel desbloquea el read bloqueado al
 *  instante) para que el reintento por segmento reconecte con una conexion
 *  fresca. Solo tras MAX_STALL_INTERVENTIONS intervenciones fallidas se
 *  cancela la descarga entera. Esto auto-recupera cortes silenciosos del CDN
 *  sin tirar a la basura GBs de progreso.
 *
 * Velocidad:
 *  - 16 conexiones paralelas sobre un CDN que limita a ~5 Mbps por conexion = ~80 Mbps
 *    agregados teoricos (~10 MB/s). En la practica el limite es el ancho de banda
 *    del usuario o el throttleo del CDN por IP.
 *  - Buffer de 128 KB para reducir syscalls en segmentos grandes (4s @ 5Mbps = 2.5MB).
 *  - Connection pool del OkHttpClient reutilizado entre segmentos (HTTP keep-alive).
 *
 * Formato del archivo final:
 *  - Si el playlist tiene `#EXT-X-MAP` (fMP4/CMAF), el resultado es `init.mp4 + seg_N.m4s`
 *    concatenados, reproducible como MP4 fMP4.
 *  - Si no tiene `#EXT-X-MAP` (MPEG-TS clasico), el resultado es `seg_N.ts` concatenados,
 *    reproducible como MPEG-TS.
 *  - En ambos casos, salvamos con extension `.ts` (ExoPlayer sniffing detecta el formato
 *    real desde el contenido del archivo).
 */
class HlsDownloader(
    private val client: OkHttpClient,
    private val movieId: String,
    private val title: String,
    private val onProgress: (downloadedBytes: Long, totalBytes: Long, speedBps: Long) -> Unit,
) {

    companion object {
        private const val TAG = "HlsDownloader"

        /**
         * WLFMOVIE V6.3: Subido de 8 a 16 conexiones concurrentes.
         * 16 es el sweet spot: la mayoria de CDNs limitan por IP a ~5 Mbps/conexion, asi
         * que con 16 llegamos a ~80 Mbps agregados. Subir mas (24, 32) no ayuda y
         * empieza a causar 429 (Too Many Requests) en CDNs estrictos.
         */
        private const val PARALLELISM = 16

        /**
         * WLFMOVIE V6.3.2: Subido de 3 a 5 reintentos por segmento. El usuario
         * reporto que algunas peliculas se quedan colgadas sin error
         * ni mas progreso. Con 5 intentos y backoff damos mas chances
         * de recuperar conexiones cortadas por el CDN.
         */
        private const val MAX_SEGMENT_RETRIES = 5

        /**
         * WLFMOVIE V6.3: Buffer de 128 KB. En segmentos de 2-5 MB (tipicos de HLS
         * 1080p), 128 KB reduce de ~80 syscalls a ~20 syscalls por segmento.
         */
        private const val IO_BUFFER = 128 * 1024

        /**
         * WLFMOVIE V6.4.6: cuántos bytes capturar del inicio de cada segmento
         * para validarlo (ver [validateSegmentPayload]).
         */
        private const val SEGMENT_HEAD_CAPTURE = 1024

        /**
         * WLFMOVIE V6.4.6: tipos de caja ISO-BMFF aceptables al inicio de un
         * segmento fMP4/CMAF (init = ftyp; media = moof/emsg/prft/...).
         */
        private val SEGMENT_VALID_BOXES = setOf(
            "ftyp", "moof", "styp", "emsg", "prft", "free", "skip", "uuid", "mdat", "moov",
        )

        /**
         * WLFMOVIE V6.3.2: Ventana de inactividad del watchdog. Si el total de
         * bytes en disco no cambia durante este tiempo, consideramos que el
         * CDN nos ha bloqueado o silenciosamente cortado la conexion.
         *
         * WLFMOVIE V6.3.3: esta ventana ya NO mata la descarga directamente —
         * dispara una "intervencion" (cancelar las conexiones atascadas para
         * forzar reconexion de los segmentos). Ver [MAX_STALL_INTERVENTIONS].
         */
        private const val STALL_TIMEOUT_MS = 30_000L

        /**
         * WLFMOVIE V6.3.3: Numero de intervenciones del watchdog antes de
         * rendirse. Cada intervention cancela las conexiones activas; los
         * segmentos afectados se reintentan con backoff. Si tras
         * 3 intervenciones (≈90-120s) sigue sin haber progreso, ahi si
         * cancelamos la descarga entera.
         */
        private const val MAX_STALL_INTERVENTIONS = 3

        /**
         * WLFMOVIE V6.3.3: Sentinel para onProgress — speedBps = -1 significa
         * "fase de guardado" (concatenacion local de segmentos). La UI lo
         * usa para mostrar "Guardando pelicula... X%" en vez de una velocidad.
         */
        private const val SPEED_FINALIZING = -1L
    }

    data class MediaPlaylist(
        val initSegmentUri: String?,
        val segments: List<String>,
    )

    /**
     * Descarga el playlist y todos sus segmentos al `targetFile`.
     * Retorna el tamano final en bytes.
     *
     * El `targetFile` debe tener extension `.ts` (es lo que ExoPlayer espera para un
     * stream HLS concatenado; sniffing resuelve el resto).
     */
    suspend fun download(
        playlistUrl: String,
        headersJson: String,
        targetFile: File,
    ): Long {
        // Directorio temporal para segmentos. Lo creamos al lado del target.
        val tempDir = File(
            targetFile.parentFile,
            "${targetFile.nameWithoutExtension}_segments",
        ).apply {
            mkdirs()
            // .nomedia evita que MediaScanner escanee los .ts sueltos.
            File(this, ".nomedia").takeIf { !it.exists() }?.createNewFile()
        }

        try {
            // 1. Fetch + parse del playlist. Puede ser master o media.
            val (mediaUrl, mediaPlaylist) = fetchAndParsePlaylist(playlistUrl, headersJson)
                ?: throw java.io.IOException(
                    "No se pudo parsear el playlist HLS: $playlistUrl"
                )

            val segCount = mediaPlaylist.segments.size
            if (segCount == 0) {
                // WLFMOVIE V6.3.3: mensaje mas claro — este caso ocurre cuando el
                // servidor elimino el archivo (DMCA) pero el m3u8 sigue respondiendo
                // con un cuerpo sin segmentos ("File deleted by DMCA request").
                throw java.io.IOException(
                    "Playlist HLS sin segmentos (archivo eliminado del servidor o DMCA): $mediaUrl"
                )
            }
            Log.i(TAG, "HLS $movieId: $segCount segmentos, init=${mediaPlaylist.initSegmentUri != null}")

            // 2. Init segment (si existe, para fMP4). Debe ir primero en el archivo
            // final. Lo descargamos sincrono antes de lanzar los paralelos para que
            // el orden de concatenacion sea correcto.
            val initFile: File? = mediaPlaylist.initSegmentUri?.let { initUri ->
                val f = File(tempDir, "init.mp4")
                if (!f.exists() || f.length() == 0L) {
                    downloadSegmentWithRetry(resolveUrl(playlistUrl, initUri), f, headersJson)
                }
                f
            }

            // 3. Descarga paralela de segmentos con semaforo de concurrencia.
            val done = BooleanArray(segCount)
            val sizes = LongArray(segCount)
            val errors = mutableListOf<Throwable>()
            val speedAcc = AtomicLong(0L)
            var lastSpeedSample = System.currentTimeMillis()
            var lastEmit = System.currentTimeMillis()
            val base = playlistUrl

            // WLFMOVIE V6.3.3: registro de las Calls HTTP activas (segmentos en
            // vuelo). El watchdog las cancela cuando detecta un stall —
            // Call.cancel() desbloquea al instante el read() bloqueado, y el
            // retry por segmento reconecta con una conexion fresca.
            val activeCalls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

            // WLFMOVIE V6.3.3: maquina de fases del watchdog/ticker:
            //  0 = descargando segmentos (watchdog ACTIVO)
            //  1 = guardando/concatenando (watchdog DESACTIVADO — es I/O local)
            //  2 = terminado
            val phase = AtomicLong(0L)

            // WLFMOVIE V6.3.2: Watchdog de inactividad (ver STALL_TIMEOUT_MS).
            val lastTotalSeen = AtomicLong(0L)
            val lastProgressAt = AtomicLong(System.currentTimeMillis())
            // WLFMOVIE V6.3.3: cuantas intervenciones llevamos en este stall.
            val stallInterventions = AtomicInteger(0)

            // WLFMOVIE V6.3.3: tamano esperado del archivo final (se calcula al
            // terminar de bajar todos los segmentos). Lo usa el ticker durante
            // la fase de guardado para emitir un progreso determinado
            // ("Guardando 45% — 500 MB de 1.1 GB").
            val expectedTotalBytes = AtomicLong(0L)

            // WLFMOVIE V6.3.1: "ticker" que emite el progreso cada 1s INCLUSO si
            // ningun segmento ha terminado, y que ademas (V6.3.2) hace de
            // watchdog.
            //
            // WLFMOVIE V6.3.3 (FIX CRITICO): el ticker se detiene EXPLICITAMENTE
            // (tickerJob.cancel()) cuando el guardado termina, y la concatenacion
            // corre DENTRO del mismo coroutineScope. Antes, tickerActive.set(0)
            // estaba DESPUES del scope → el scope esperaba al ticker para siempre
            // → la descarga completa se colgaba justo antes de guardar.
            val tickerActive = AtomicLong(1L)
            coroutineScope {
                val tickerJob = launch(Dispatchers.IO) {
                    while (tickerActive.get() == 1L) {
                        delay(1_000L)
                        if (tickerActive.get() != 1L) break
                        val now = System.currentTimeMillis()

                        if (phase.get() == 1L) {
                            // ------------------------------------------------
                            // FASE GUARDADO: emitir progreso de la concatenacion.
                            // Sin watchdog — es I/O local, siempre avanza.
                            // ------------------------------------------------
                            if (now - lastEmit > 500L) {
                                val expected = expectedTotalBytes.get()
                                val merged = targetFile.length()
                                onProgress(merged, expected, SPEED_FINALIZING)
                                lastEmit = now
                            }
                            continue
                        }
                        if (phase.get() >= 2L) break

                        // ------------------------------------------------
                        // FASE DESCARGA: velocidad + progreso + watchdog.
                        // ------------------------------------------------
                        val elapsed = (now - lastSpeedSample).coerceAtLeast(1L)
                        val speedBps = (speedAcc.get() * 1000L) / elapsed
                        speedAcc.set(0L)
                        lastSpeedSample = now
                        if (now - lastEmit > 1_000L) {
                            // WLFMOVIE V6.3.2: leer bytes parciales en disco —
                            // incluye los bytes de segmentos en progreso, no
                            // solo los completados.
                            val downloadedSoFar = (0 until segCount).sumOf { idx ->
                                val segFile = File(tempDir, "seg_${idx}.ts")
                                if (done[idx]) sizes[idx] else segFile.length()
                            }
                            onProgress(downloadedSoFar, 0L, speedBps)
                            lastEmit = now

                            // WLFMOVIE V6.3.3: watchdog con intervenciones.
                            if (downloadedSoFar > lastTotalSeen.get()) {
                                // Hubo progreso — resetear ventana e intervenciones.
                                lastTotalSeen.set(downloadedSoFar)
                                lastProgressAt.set(now)
                                if (stallInterventions.get() != 0) {
                                    stallInterventions.set(0)
                                    Log.i(TAG, "HLS $movieId: progreso recuperado tras intervención — descarga sigue viva")
                                }
                            } else if (now - lastProgressAt.get() > STALL_TIMEOUT_MS) {
                                // Guard de raza: si mientras medíamos el stall la
                                // descarga ya pasó a fase de guardado (phase != 0),
                                // NO es un stall real — seguir sin intervenir.
                                if (phase.get() != 0L) continue
                                val intervention = stallInterventions.incrementAndGet()
                                if (intervention <= MAX_STALL_INTERVENTIONS) {
                                    // INTERVENCIÓN: cancelar las conexiones atascadas.
                                    // Call.cancel() hace que el read() bloqueado lance
                                    // IOException inmediatamente; downloadSegmentWithRetry
                                    // lo atrapa y reintenta con una conexion fresca.
                                    Log.w(
                                        TAG,
                                        "Watchdog: sin progreso ${STALL_TIMEOUT_MS / 1000}s " +
                                            "(intervención $intervention/$MAX_STALL_INTERVENTIONS) — " +
                                            "cancelando ${activeCalls.size} conexiones atascadas para forzar reconexión"
                                    )
                                    activeCalls.forEach { runCatching { it.cancel() } }
                                    runCatching { client.connectionPool.evictAll() }
                                    // Reiniciar la ventana para la próxima intervención.
                                    lastProgressAt.set(now)
                                } else {
                                    // Agotamos las intervenciones: el CDN está
                                    // realmente muerto para nosotros. Cancelar todo.
                                    val err = java.io.IOException(
                                        "Descarga detenida sin progreso tras " +
                                            "${MAX_STALL_INTERVENTIONS} reconexiones " +
                                            "(bloqueo del CDN o conexión cortada). " +
                                            "Bytes descargados: $downloadedSoFar"
                                    )
                                    Log.e(TAG, "Watchdog: ${err.message}")
                                    synchronized(errors) { errors.add(err) }
                                    // Publicar el último total antes de fallar para
                                    // que la UI lo refleje.
                                    onProgress(downloadedSoFar, 0L, 0L)
                                    // Desbloquear los reads bloqueados ya mismos
                                    // (sin esto, el catch de MovieDownloadService
                                    // puede tardar hasta readTimeout en ejecutarse).
                                    activeCalls.forEach { runCatching { it.cancel() } }
                                    runCatching { client.connectionPool.evictAll() }
                                    throw err
                                }
                            }
                        }
                    }
                }

                val semaphore = Semaphore(PARALLELISM)
                val jobs = mediaPlaylist.segments.mapIndexed { idx, segUri ->
                    async(Dispatchers.IO) {
                        if (errors.isNotEmpty()) return@async
                        semaphore.withPermit {
                            if (errors.isNotEmpty()) return@withPermit
                            val segFile = File(tempDir, "seg_${idx}.ts")
                            try {
                                // WLFMOVIE V6.3: skip si ya esta descargado (resume).
                                if (!segFile.exists() || segFile.length() == 0L) {
                                    downloadSegmentWithRetry(
                                        resolveUrl(base, segUri),
                                        segFile,
                                        headersJson,
                                        activeCalls,
                                    )
                                }
                                done[idx] = true
                                sizes[idx] = segFile.length()
                                speedAcc.addAndGet(segFile.length())

                                // WLFMOVIE V6.3.2: actualizar progreso del watchdog.
                                lastTotalSeen.set(
                                    (0 until segCount).sumOf { i ->
                                        val f = File(tempDir, "seg_${i}.ts")
                                        if (done[i]) sizes[i] else f.length()
                                    }
                                )
                                lastProgressAt.set(System.currentTimeMillis())

                                // Progress emit (throttled) — ademas del ticker,
                                // emitimos cuando un segmento termina. Asi el
                                // usuario ve un "salto" cada vez que un segmento
                                // baja, no solo cada 1s.
                                val now = System.currentTimeMillis()
                                if (now - lastEmit > 1_000L) {
                                    val elapsed = (now - lastSpeedSample).coerceAtLeast(1L)
                                    val speedBps = (speedAcc.get() * 1000L) / elapsed
                                    speedAcc.set(0L)
                                    lastSpeedSample = now
                                    val downloadedSoFar = (0 until segCount).sumOf { i ->
                                        val f = File(tempDir, "seg_${i}.ts")
                                        if (done[i]) sizes[i] else f.length()
                                    }
                                    onProgress(downloadedSoFar, 0L, speedBps)
                                    lastEmit = now
                                }
                            } catch (e: Throwable) {
                                Log.e(TAG, "Segmento $idx fallo tras $MAX_SEGMENT_RETRIES intentos: ${e.message}", e)
                                synchronized(errors) { errors.add(e) }
                                throw e
                            }
                        }
                    }
                }
                jobs.awaitAll()

                // ------------------------------------------------------------
                // WLFMOVIE V6.3.3 (FIX CRITICO): todos los segmentos bajaron.
                // A partir de aqui el watchdog se DESACTIVA (fase guardado) y
                // la concatenacion corre DENTRO de este scope. El ticker se
                // detiene explicitamente al final. NADA de esto existia antes:
                // el scope se quedaba esperando al ticker para siempre y la
                // descarga moria justo despues de bajar el ultimo segmento.
                // ------------------------------------------------------------
                if (errors.isNotEmpty()) {
                    tickerActive.set(0L)
                    tickerJob.cancel()
                    throw errors.first()
                }

                // Fase guardado: el ticker emite "Guardando X%" con total conocido.
                val initSize = initFile?.length() ?: 0L
                val segmentsTotal = (0 until segCount).sumOf { sizes[it] }
                expectedTotalBytes.set(initSize + segmentsTotal)
                phase.set(1L)
                Log.i(
                    TAG,
                    "HLS $movieId: segmentos completos (${segmentsTotal} bytes). " +
                        "Guardando archivo final (${segCount} segmentos)…"
                )

                // 4. Concatenar init + segmentos en orden al archivo final.
                FileOutputStream(targetFile).use { out ->
                    initFile?.let {
                        it.inputStream().use { i -> i.copyTo(out) }
                    }
                    for (idx in 0 until segCount) {
                        val segFile = File(tempDir, "seg_${idx}.ts")
                        if (!segFile.exists()) {
                            throw java.io.IOException(
                                "Segmento $idx perdido despues de descarga paralela"
                            )
                        }
                        segFile.inputStream().use { i -> i.copyTo(out) }
                    }
                    out.flush()
                }

                // Guardado listo: parar el ticker y cerrar el scope.
                phase.set(2L)
                tickerActive.set(0L)
                tickerJob.cancel()
            }

            if (errors.isNotEmpty()) {
                throw errors.first()
            }

            // 5. Progress final = 100%.
            val totalSize = targetFile.length()
            onProgress(totalSize, totalSize, 0L)

            Log.i(TAG, "HLS $movieId: completo, ${totalSize} bytes, $segCount segmentos")
            return totalSize
        } finally {
            // 6. Cleanup temp dir (best-effort).
            runCatching { tempDir.deleteRecursively() }
        }
    }

    // ------------------------------------------------------------------
    // Playlist parsing
    // ------------------------------------------------------------------

    /**
     * Descarga el playlist, detecta si es master o media, y devuelve el URL final
     * del media playlist + su parseo.
     */
    private suspend fun fetchAndParsePlaylist(
        url: String,
        headersJson: String,
    ): Pair<String, MediaPlaylist>? {
        val text = fetchText(url, headersJson) ?: return null

        // Master playlist: tiene `#EXT-X-STREAM-INF`.
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variantUri = pickBestVariant(text) ?: return null
            val resolvedVariant = resolveUrl(url, variantUri)
            val mediaText = fetchText(resolvedVariant, headersJson) ?: return null
            val parsed = parseMediaPlaylist(mediaText)
            return resolvedVariant to parsed
        }

        // Es un media playlist directo.
        val parsed = parseMediaPlaylist(text)
        return url to parsed
    }

    /**
     * Elige la variante de mayor BANDWIDTH del master playlist.
     * Si ninguna tiene BANDWIDTH explicita, toma la primera.
     */
    private fun pickBestVariant(masterText: String): String? {
        var bestBw = -1L
        var bestUri: String? = null
        var bestBwPending = false  // la siguiente linea no-# es la URI de la variante best

        for (line in masterText.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                val bwMatch = Regex("""BANDWIDTH=(\d+)""").find(trimmed)
                val bw = bwMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                if (bw > bestBw) {
                    bestBw = bw
                    bestBwPending = true
                } else {
                    bestBwPending = false
                }
            } else if (bestBwPending && trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                bestUri = trimmed
                bestBwPending = false
            }
        }
        return bestUri
    }

    /**
     * Parsea un media playlist.
     * - Detecta `#EXT-X-MAP:URI="..."` para fMP4 init segment.
     * - Recoge todas las lineas no-# que siguen a un `#EXTINF`.
     */
    private fun parseMediaPlaylist(text: String): MediaPlaylist {
        val segments = mutableListOf<String>()
        var initSegment: String? = null
        var nextLineIsSegment = false

        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // EXT-X-MAP aparece antes del primer segmento (init segment fMP4).
            if (trimmed.startsWith("#EXT-X-MAP")) {
                val match = Regex("""URI="([^"]+)"""").find(trimmed)
                if (match != null) initSegment = match.groupValues[1]
                continue
            }

            // EXTINF marca que la siguiente linea no-# es un segment URI.
            if (trimmed.startsWith("#EXTINF")) {
                nextLineIsSegment = true
                continue
            }

            if (nextLineIsSegment && !trimmed.startsWith("#")) {
                segments.add(trimmed)
                nextLineIsSegment = false
            }
        }

        return MediaPlaylist(
            initSegmentUri = initSegment,
            segments = segments,
        )
    }

    // ------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------

    /**
     * WLFMOVIE V6.3: Descarga un segmento con hasta [MAX_SEGMENT_RETRIES] reintentos.
     * Backoff exponencial: 1s, 2s, 4s, 8s, 16s. Si todos los intentos fallan,
     * lanza la ultima excepcion.
     *
     * WLFMOVIE V6.3.3: recibe [activeCalls] para registrar la Call en vuelo.
     * Cuando el watchdog detecta un stall, cancela esas Calls — el read()
     * bloqueado lanza IOException al instante y este mismo retry loop
     * reconecta con una conexion fresca. Es el mecanismo de auto-curacion.
     */
    private suspend fun downloadSegmentWithRetry(
        url: String,
        targetFile: File,
        headersJson: String,
        activeCalls: MutableSet<Call>? = null,
    ) {
        var lastError: Throwable? = null
        for (attempt in 0 until MAX_SEGMENT_RETRIES) {
            try {
                downloadSegment(url, targetFile, headersJson, activeCalls)
                return  // success
            } catch (e: Throwable) {
                // Si el watchdog nos canceló la Call, NO contarla como intento
                // fallido consumible — es una reconexion forzada, no un error
                // del servidor. Solo aplicarla si quedan intentos.
                lastError = e
                // Si el archivo quedo a medio escribir, lo borramos para que el proximo
                // intento empiece de 0 (no queremos concatenar basura).
                runCatching { if (targetFile.exists()) targetFile.delete() }
                if (attempt < MAX_SEGMENT_RETRIES - 1) {
                    val backoffMs = (1L shl attempt) * 1000L  // 1s, 2s, 4s, 8s, 16s
                    Log.w(TAG, "Segmento $url fallo (intento ${attempt + 1}/$MAX_SEGMENT_RETRIES): ${e.message}. Reintentando en ${backoffMs}ms")
                    delay(backoffMs)
                }
            }
        }
        throw lastError ?: java.io.IOException("downloadSegmentWithRetry exhausted")
    }

    private fun fetchText(url: String, headersJson: String): String? {
        return runCatching {
            val builder = Request.Builder().url(url)
            applyHeaders(builder, headersJson)
            val request = builder.build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "fetchText $url -> HTTP ${resp.code}")
                    return@use null
                }
                resp.body?.string()
            }
        }.getOrNull()
    }

    /**
     * Descarga un segmento al `targetFile`. Si falla con un codigo HTTP != 2xx/206,
     * lanza IOException (lo atrapa el llamador paralelo).
     *
     * WLFMOVIE V6.3.3: la Call se registra en [activeCalls] mientras esta en vuelo
     * para que el watchdog pueda cancelarla (desbloquear el read) si hace falta.
     */
    private fun downloadSegment(
        url: String,
        targetFile: File,
        headersJson: String,
        activeCalls: MutableSet<Call>? = null,
    ) {
        val builder = Request.Builder().url(url)
        applyHeaders(builder, headersJson)
        // Pedir bytes crudos, no gzip, para poder medir tamanos reales.
        builder.header("Accept-Encoding", "identity")
        // WLFMOVIE V6.3: hint al CDN para que no nos throtlee. Algunos CDNs respetan
        // esto y suben el limite por conexion.
        builder.header("Connection", "keep-alive")
        val request = builder.build()

        val call = client.newCall(request)
        if (activeCalls != null) activeCalls.add(call)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    throw java.io.IOException("HTTP ${resp.code} for $url")
                }
                val body = resp.body ?: throw java.io.IOException("Empty body for $url")
                FileOutputStream(targetFile).use { out ->
                    val input = body.byteStream()
                    // WLFMOVIE V6.4.6: capturar los primeros bytes para validar
                    // que la respuesta sea video de verdad (ver
                    // validateSegmentPayload). El resto se copia tal cual.
                    val head = ByteArray(SEGMENT_HEAD_CAPTURE)
                    var headLen = 0
                    val buffer = ByteArray(IO_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        if (headLen < head.size) {
                            val take = minOf(read, head.size - headLen)
                            System.arraycopy(buffer, 0, head, headLen, take)
                            headLen += take
                        }
                        out.write(buffer, 0, read)
                    }
                    out.flush()
                    // WLFMOVIE V6.4.6: rechazar imágenes/HTML servidos por el CDN
                    // en lugar del segmento. Lanza IOException con motivo claro →
                    // el retry lo trata como cualquier fallo de segmento y si el
                    // CDN siempre responde basura, la descarga FALLA en vez de
                    // guardar un archivo inservible.
                    validateSegmentPayload(
                        url,
                        head,
                        headLen,
                        targetFile.length(),
                        resp.header("Content-Type"),
                    )
                }
            }
        } finally {
            if (activeCalls != null) activeCalls.remove(call)
        }
    }

    /**
     * WLFMOVIE V6.4.6: valida que la respuesta de un "segmento" HLS sea de
     * verdad video.
     *
     * Motivo: descubrimos (log del 2026-08-28) que algunos CDNs anti-leech
     * responden HTTP 200 con una IMAGEN PNG en lugar del segmento TS/fMP4;
     * el descargador las concatenaba sin más y el resultado era un .ts que
     * arrancaba con la firma "PNG/IHDR" — imposible de reproducir y de
     * convertir ("Failed to instantiate extractor"), y origen de los
     * fragParsingError del player del PC.
     *
     * Acepta:
     *  - MPEG-TS: sync 0x47 en el byte 0 con corrida de syncs a stride 188
     *    (se admite un resync corto dentro del primer KB).
     *  - fMP4/CMAF: caja ISO-BMFF al inicio con tipo conocido
     *    (ftyp/moof/styp/emsg/...).
     *
     * Rechaza con motivo claro: PNG, JPEG, GIF, HTML y cualquier otra cosa.
     * El rechazo se registra en el CrashLogger (debug) con URL y volcado hex
     * para poder diagnosticar patrones del CDN.
     */
    private fun validateSegmentPayload(
        url: String,
        head: ByteArray,
        headLen: Int,
        fileLen: Long,
        contentType: String?,
    ) {
        if (fileLen <= 0L) {
            throw java.io.IOException("El servidor respondió un segmento vacío: $url")
        }
        if (headLen <= 0) return // sin datos capturados; no se puede validar

        // ¿Basura conocida? Identificar PRIMERO — ojo: GIF también empieza
        // con 0x47, hay que descartarlo antes del chequeo de sync TS.
        fun junkKind(): String? = when {
            headLen >= 4 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
                head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() -> "una imagen PNG"
            headLen >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() &&
                head[2] == 0xFF.toByte() -> "una imagen JPEG"
            headLen >= 4 && head[0] == 0x47.toByte() && head[1] == 0x49.toByte() &&
                head[2] == 0x46.toByte() && head[3] == 0x38.toByte() -> "una imagen GIF"
            head[0] == '<'.code.toByte() -> "una página HTML"
            else -> null
        }

        // ¿MPEG-TS? Sync 0x47 en 0/188 (+376 si alcanza), o resync corto.
        fun looksLikeTs(): Boolean {
            if (head[0] == 0x47.toByte()) {
                // Archivo más chico que 2 paquetes: con un sync basta.
                if (headLen < 2 * 188 + 1) return true
                // Exigir sync también en +188 (y +376 si el buffer alcanza):
                // (1/256)^2-3 de falso positivo sobre datos aleatorios.
                if (head[188] != 0x47.toByte()) return false
                return headLen < 3 * 188 + 1 || head[376] == 0x47.toByte()
            }
            // ¿Basura corta antes del primer paquete? Buscar corrida de 4.
            if (headLen >= 4 * 188 + 1) {
                for (i in 0..(headLen - 4 * 188)) {
                    if (head[i] == 0x47.toByte() && head[i + 188] == 0x47.toByte() &&
                        head[i + 376] == 0x47.toByte() && head[i + 564] == 0x47.toByte()
                    ) {
                        return true
                    }
                }
            }
            return false
        }

        // ¿fMP4/CMAF? Caja ISO-BMFF al inicio (tipo en offset 4).
        fun looksLikeFmp4(): Boolean {
            if (headLen < 8) return false
            val box = String(head, 4, 4, Charsets.US_ASCII)
            return box in SEGMENT_VALID_BOXES
        }

        if (looksLikeTs() || looksLikeFmp4()) return // video de verdad

        // Basura → error claro con volcado para el log.
        val kind = junkKind() ?: "contenido que no es video"
        val hex = (0 until minOf(headLen, 16)).joinToString(" ") {
            String.format(java.util.Locale.US, "%02x", head[it].toInt() and 0xFF)
        }
        CrashLogger.logError(
            tag = TAG,
            message = "Segmento rechazado: el servidor respondió $kind en lugar de " +
                "video (content-type=${contentType ?: "?"}, ${fileLen}B) — $url head=$hex",
            context = "validación de segmentos HLS",
        )
        throw java.io.IOException(
            "El servidor respondió $kind en lugar del segmento de video " +
                "(protección anti-descarga o enlace vencido; prueba con otro servidor). [$url]"
        )
    }

    private fun applyHeaders(builder: Request.Builder, headersJson: String) {
        runCatching {
            val obj = JSONObject(headersJson)
            obj.keys().forEach { key ->
                val value = obj.optString(key)
                if (value.isNotEmpty()) builder.header(key, value)
            }
        }
        // User-Agent para que el CDN no rechace la request.
        if (builder.build().header("User-Agent") == null) {
            builder.header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) WlfMovie/6.3",
            )
        }
    }

    /**
     * Resuelve un URI relativo contra el URL base del playlist.
     * Soporta URLs absolutos, paths relativos y queries relativos.
     */
    private fun resolveUrl(baseUrl: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) {
            return relative
        }
        return runCatching {
            val base = URI(baseUrl)
            base.resolve(relative).toString()
        }.getOrDefault(relative)
    }

    /**
     * Extension para copiar InputStream a OutputStream con el buffer configurado.
     * Evita la dependencia de kotlin-stdlib-jdk8 `copyTo` para tener control sobre
     * el tamanno del buffer.
     */
    private fun InputStream.copyTo(out: OutputStream) {
        val buffer = ByteArray(IO_BUFFER)
        while (true) {
            val read = this.read(buffer)
            if (read == -1) break
            out.write(buffer, 0, read)
        }
    }
}
