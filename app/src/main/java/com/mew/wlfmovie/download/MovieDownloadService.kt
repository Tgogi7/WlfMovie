package com.mew.wlfmovie.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mew.wlfmovie.R
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.models.DownloadedMovie
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Suffix del archivo segun el kind. REMUX usa .mp4 (es lo que ya estaba),
 * HLS usa .ts porque el archivo final es MPEG-TS o fMP4 concatenado, y
 * ExoPlayer sniffing lo detecta desde el contenido del archivo.
 *
 * WLFMOVIE V6.3: REMUX ya no se usa en la practica (CDN caido permanentemente),
 * pero se mantiene por compatibilidad con datos persistedos.
 */
private const val FILE_EXT_REMUX = ".mp4"
private const val FILE_EXT_HLS = ".ts"

/**
 * Foreground service que descarga una pelicula con OkHttp, actualiza la notificacion
 * cada 1s y al completar inserta la row en la tabla `downloads`.
 *
 * El servicio se arranca con:
 *   - ACTION_START_DOWNLOAD  + extras (movieId, title, poster, banner, url, language,
 *                              serverName, headersJson, downloadDir)
 *   - ACTION_CANCEL          + EXTRA_MOVIE_ID
 *
 * El progreso se publica tambien en [MovieDownloadManager.progressFlow] para que la UI
 * (boton de detalles, seccion de home) reaccione en tiempo real.
 */
class MovieDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    /**
     * WLFMOVIE V6.3: Reutiliza el cliente HTTP optimizado del MovieDownloadManager.
     * Ese cliente tiene connection pool de 32 conexiones keep-alive para soportar
     * las 16 conexiones paralelas del HlsDownloader sin agotar el pool.
     */
    private val client: OkHttpClient by lazy { MovieDownloadManager.downloadClient }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> handleStart(intent)
            ACTION_CANCEL -> handleCancel(intent)
            else -> stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------

    private fun handleStart(intent: Intent) {
        val movieId = intent.getStringExtra(EXTRA_MOVIE_ID) ?: run {
            stopSelfSafely(); return
        }
        val title = intent.getStringExtra(EXTRA_MOVIE_TITLE) ?: movieId
        val poster = intent.getStringExtra(EXTRA_MOVIE_POSTER)
        val banner = intent.getStringExtra(EXTRA_MOVIE_BANNER)
        val url = intent.getStringExtra(EXTRA_URL) ?: run {
            failWithMissingExtra(title, movieId, "url"); return
        }
        val language = intent.getStringExtra(EXTRA_LANGUAGE) ?: "latino"
        val serverName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "Remux"
        val kindStr = intent.getStringExtra(EXTRA_KIND) ?: MovieDownloadManager.Kind.REMUX.name
        val kind = runCatching { MovieDownloadManager.Kind.valueOf(kindStr) }
            .getOrDefault(MovieDownloadManager.Kind.REMUX)
        val headersJson = intent.getStringExtra(EXTRA_HEADERS_JSON) ?: "{}"
        val downloadDir = intent.getStringExtra(EXTRA_DOWNLOAD_DIR)
            ?: getDir(DIR_NAME, Context.MODE_PRIVATE).absolutePath

        // WLFMOVIE: SIEMPRE llamar a startForeground aqui (incluso si ya hay un job
        // activo para este movieId) — Android 12+ exige que cualquier
        // startForegroundService() resulte en startForeground() dentro de ~5s, sino
        // mata el servicio con ForegroundServiceDidNotStartInTimeException.
        //
        // WLFMOVIE V6.2.2: Si startForeground falla (manifest, permisos, etc.),
        // NO continuar — el scope.launch se ejecutaria sobre un service moribundo
        // y el trabajo se pierde sin notificar al usuario.
        if (!startForegroundForDownload(movieId, title)) {
            return
        }

        // Pre-publicar estado Downloading 0% para que la UI reaccione al instante.
        MovieDownloadManager.publishDownloading(movieId, 0f, 0L, 0L, 0L)

        // Si ya hay un job para esta pelicula, no duplicar (pero SI dejar el
        // startForeground hecho para satisfacer el contrato de foreground service).
        jobs[movieId]?.let { if (it.isActive) return }

        val job = scope.launch {
            try {
                ensureDownloadsDir(downloadDir)
                // WLFMOVIE V6.2.4: el sufijo del archivo depende del kind.
                // Remux: .mp4 (MP4 directo del CDN). HLS: .ts (MPEG-TS o fMP4
                // concatenado; ExoPlayer sniffing resuelve el formato real).
                // WLFMOVIE V6.3: en la practica solo HLS llega aqui — Remux CDN caido.
                val fileExt = when (kind) {
                    MovieDownloadManager.Kind.REMUX -> FILE_EXT_REMUX
                    MovieDownloadManager.Kind.HLS -> FILE_EXT_HLS
                }
                val filePath = File(
                    downloadDir,
                    "movie_${sanitizeFileName(movieId)}$fileExt",
                ).absolutePath

                // WLFMOVIE V6.2.4: rutear segun kind.
                // - REMUX: single HTTP GET con Range (resume si hay parcial).
                // - HLS: parsea m3u8 + descarga segmentos en paralelo.
                // WLFMOVIE V6.3: REMUX nunca llega aqui en la practica (Manager
                // siempre devuelve HLS), pero se mantiene el branch por compat.
                val fileSize = when (kind) {
                    MovieDownloadManager.Kind.REMUX ->
                        downloadWithResume(url, filePath, headersJson, movieId, title)
                    MovieDownloadManager.Kind.HLS ->
                        downloadHls(url, filePath, headersJson, movieId, title)
                }
                if (fileSize <= 0L) {
                    throw java.io.IOException("Downloaded 0 bytes")
                }
                // WLFMOVIE V6.3.4: descargar poster + banner a archivo LOCAL
                // antes de insertar la row. Asi, si el usuario entra sin
                // conexion, la pelicula descargada muestra su caratula en el
                // home y en el reproductor (antes quedaba sin foto porque el
                // poster era una URL de TMDB que no carga offline).
                // Best-effort: si falla, la row queda con la URL remota y el
                // backfill de MovieDownloadManager la repara cuando haya red.
                // OJO: dentro de scope.launch{} el `this` es el CoroutineScope;
                // el contexto del service se referencia con this@MovieDownloadService.
                val (localPoster, localBanner) = MovieDownloadManager
                    .downloadArtworkForMovie(this@MovieDownloadService, movieId, poster, banner)
                insertDownloadedRow(
                    movieId, title,
                    localPoster ?: poster,
                    localBanner ?: banner,
                    filePath, fileSize, language, serverName, url, headersJson,
                )
                MovieDownloadManager.publishCompleted(movieId, filePath)
                MovieDownloadNotifier.notifyCompleted(this@MovieDownloadService, movieId, title)
                toast(getString(R.string.download_completed_toast, title))

                // WLFMOVIE V6.6.0: ya NO se encola ninguna conversión a MP4 —
                // la conversión (y la re-codificación de audio) fue eliminada
                // por completo. La descarga queda tal como bajó: .ts (HLS) o
                // .mp4 (Remux), y "Enviar al PC" la sirve tal cual.
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelado por el usuario: borrar parcial y publicar Cancelled.
                cleanupPartialFile(movieId, downloadDir)
                MovieDownloadManager.publishCancelled(movieId)
                MovieDownloadNotifier.cancel(this@MovieDownloadService, movieId)
                toast(getString(R.string.download_failed_toast, title))
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Download failed for $movieId: ${e.message}", e)
                com.mew.wlfmovie.utils.CrashLogger.logError(
                    tag = "Download",
                    message = "Download failed for $movieId ($title): ${e.message}",
                    throwable = e,
                    context = "url=$url, server=$serverName, lang=$language, kind=$kind",
                )
                MovieDownloadManager.publishFailed(movieId, e.message ?: "unknown")
                MovieDownloadNotifier.notifyFailed(this@MovieDownloadService, movieId, title)
                // WLFMOVIE V6.3.2: Si el mensaje contiene "sin progreso",
                // significa que el watchdog detectó inactividad. Mostrar un
                // toast más específico para que el usuario sepa que no es un
                // error genérico sino que el CDN nos bloqueó o cortó.
                //
                // WLFMOVIE V6.3.3: tambien detectar "sin segmentos" (archivo
                // eliminado del servidor por DMCA — el m3u8 responde 200 pero
                // sin segmentos descargables).
                val msg = e.message.orEmpty()
                if (msg.contains("sin progreso", ignoreCase = true) ||
                    msg.contains("stalled", ignoreCase = true)) {
                    toast(getString(R.string.download_stalled_toast))
                } else if (msg.contains("sin segmentos", ignoreCase = true) ||
                    msg.contains("DMCA", ignoreCase = true)) {
                    toast(getString(R.string.download_no_segments_toast))
                } else {
                    toast(getString(R.string.download_failed_toast, title))
                }
            } finally {
                jobs.remove(movieId)
                stopForegroundAndSelf(movieId)
            }
        }
        jobs[movieId] = job
    }

    private fun handleCancel(intent: Intent) {
        val movieId = intent.getStringExtra(EXTRA_MOVIE_ID) ?: run {
            stopSelfSafely(); return
        }
        // Llamar a startForeground aunque sea para cancelar: Android 12+ requiere
        // que cualquier startForegroundService() resulte en startForeground() en <5s.
        // Si el servicio ya estaba en foreground, esto es un no-op (actualiza la
        // notificacion); si no estaba, evita el ForegroundServiceDidNotStartInTimeException.
        startForegroundForCancel(movieId)
        jobs[movieId]?.cancel()
        jobs.remove(movieId)
        // WLFMOVIE V6.3.1: Cleanup file partial: borrar TODOS los posibles
        // archivos y directorios parciales (mp4 de Remux, ts de HLS, y el
        // dir de segmentos HLS que puede tener GBs de .ts sueltos). Antes
        // solo se borraba el .mp4, así que una cancelación de descarga HLS
        // dejaba basura en disco (segmentos parciales + archivo .ts parcial).
        runCatching {
            val dir = getDir(DIR_NAME, Context.MODE_PRIVATE)
            File(dir, "movie_${sanitizeFileName(movieId)}.mp4").delete()
            File(dir, "movie_${sanitizeFileName(movieId)}.ts").delete()
            File(dir, "movie_${sanitizeFileName(movieId)}_segments").deleteRecursively()
        }
        MovieDownloadManager.publishCancelled(movieId)
        MovieDownloadNotifier.cancel(this, movieId)
        stopForegroundAndSelf(movieId)
    }

    private fun startForegroundForCancel(movieId: String) {
        // Notificacion minima para satisfacer el contrato de foreground service.
        // Se cancelara inmediatamente despues por stopForegroundAndSelf().
        val title = "Cancel"
        try {
            val notification = MovieDownloadNotifier
                .buildProgressNotification(this, movieId, title, 0, "0 B", "?")
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    MovieDownloadNotifier.notificationIdFor(movieId),
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(MovieDownloadNotifier.notificationIdFor(movieId), notification)
            }
        } catch (e: Exception) {
            // Si no podemos arrancar foreground (e.g., restricciones Android 12+),
            // simplemente dejamos que handleCancel haga el cleanup y stopSelf.
            Log.e(TAG, "startForeground for cancel failed: ${e.message}")
        }
    }

    /**
     * Devuelve true si el foreground se arranco OK, false si fallo.
     * WLFMOVIE V6.2.2: el llamador (handleStart) debe early-return si esto
     * devuelve false — si no, el scope.launch se ejecuta sobre un service
     * que ya se va a matar y el trabajo se pierde sin notificar al usuario.
     */
    private fun startForegroundForDownload(movieId: String, title: String): Boolean {
        val notification = MovieDownloadNotifier
            .buildProgressNotification(this, movieId, title, 0, "0 B", "?")
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    MovieDownloadNotifier.notificationIdFor(movieId),
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(MovieDownloadNotifier.notificationIdFor(movieId), notification)
            }
            true
        } catch (e: Throwable) {
            // ForegroundServiceStartNotAllowedException en Android 12+ si la app no
            // esta en primer plano, SecurityException si falta el permiso
            // FOREGROUND_SERVICE_DATA_SYNC, IllegalStateException si el service no
            // esta declarado en el manifest. Registrar todo.
            Log.e(TAG, "startForeground failed for $movieId: ${e.message}", e)
            com.mew.wlfmovie.utils.CrashLogger.logError(
                tag = "DownloadStart",
                message = "startForeground failed for $movieId: ${e.message}",
                throwable = e,
                context = "MovieDownloadService.startForegroundForDownload",
            )
            MovieDownloadManager.publishFailed(
                movieId,
                "No se pudo arrancar el servicio en foreground: ${e.message}",
            )
            stopSelfSafely()
            false
        }
    }

    private fun stopForegroundAndSelf(movieId: String) {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // ignore
        }
        if (jobs.isEmpty()) stopSelf()
    }

    private fun stopSelfSafely() {
        try { stopSelf() } catch (e: Exception) { /* ignore */ }
    }

    // ---------------------------------------------------------------------

    private fun failWithMissingExtra(title: String, movieId: String, name: String) {
        Log.e(TAG, "Missing extra $name for download of $movieId")
        MovieDownloadManager.publishFailed(movieId, "Missing $name")
        MovieDownloadNotifier.notifyFailed(this, movieId, title)
        stopForegroundAndSelf(movieId)
    }

    /**
     * Descarga con soporte HTTP Range para reanudar si la app se mato a mitad.
     * Retorna el total de bytes escritos en el archivo.
     */
    private fun downloadWithResume(
        url: String,
        filePath: String,
        headersJson: String,
        movieId: String,
        title: String,
    ): Long {
        val file = File(filePath)
        val existingBytes = if (file.exists()) file.length() else 0L

        val builder = Request.Builder().url(url)
        // Headers HTTP desde headersJson (pares key/value).
        runCatching {
            val obj = JSONObject(headersJson)
            obj.keys().forEach { key ->
                val value = obj.optString(key)
                if (value.isNotEmpty()) builder.header(key, value)
            }
        }
        // Range para reanudar.
        if (existingBytes > 0L) {
            builder.header("Range", "bytes=$existingBytes-")
        }
        builder.header("Accept-Encoding", "identity") // evitar gzip para poder medir bytes crudos

        val request = builder.build()
        val response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                throw java.io.IOException("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw java.io.IOException("Empty body")
            val totalContentLength = body.contentLength().let { declared ->
                if (declared <= 0L) 0L else declared + existingBytes
            }
            // Si el server respondio 200 (no 206) ignora el archivo parcial y reescribe desde 0.
            val effectiveExisting = if (resp.code == 206) existingBytes else 0L
            val appendMode = resp.code == 206
            val outStream = FileOutputStream(file, appendMode)
            outStream.use { out ->
                val input = body.byteStream()
                val buffer = ByteArray(64 * 1024)
                var lastEmit = System.currentTimeMillis()
                var lastSpeedSample = System.currentTimeMillis()
                val speedAccumulator = AtomicLong(0L)
                var downloaded = effectiveExisting
                var total = totalContentLength
                if (total <= 0L) total = -1L

                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    downloaded += read.toLong()
                    speedAccumulator.addAndGet(read.toLong())

                    val now = System.currentTimeMillis()
                    if (now - lastSpeedSample > 1_000) {
                        // Calcular velocidad: bytes / segundos
                        val elapsed = (now - lastSpeedSample).coerceAtLeast(1L)
                        val speedBps = (speedAccumulator.get() * 1000L) / elapsed
                        speedAccumulator.set(0L)
                        lastSpeedSample = now

                        if (now - lastEmit > 1_000) {
                            val percent = if (total > 0L) {
                                ((downloaded.toDouble() / total.toDouble()) * 100f).toInt().coerceIn(0, 100)
                            } else 0
                            val progress = if (total > 0L) (downloaded.toFloat() / total.toFloat()) else 0f
                            MovieDownloadManager.publishDownloading(
                                movieId,
                                progress,
                                downloaded,
                                total,
                                speedBps,
                            )
                            MovieDownloadNotifier.notifyProgress(
                                this,
                                movieId,
                                title,
                                percent,
                                humanReadableBytes(downloaded),
                                if (total > 0L) humanReadableBytes(total) else "?",
                            )
                            lastEmit = now
                        }
                    }
                }
                out.flush()
                // Estado final al 100%
                if (total > 0L) {
                    MovieDownloadManager.publishDownloading(
                        movieId,
                        1f,
                        downloaded,
                        total,
                        0L,
                    )
                }
                return file.length()
            }
        }
    }

    /**
     * WLFMOVIE V6.2.4: Descarga una pelicula HLS (m3u8) via [HlsDownloader].
     *
     * WLFMOVIE V6.3: HLS es ahora el UNICO metodo de descarga. El Manager hace
     * scraper a TODOS los providers (no solo CineHax) para encontrar el mejor
     * server HLS para el idioma seleccionado por el usuario. El [HlsDownloader]
     * paraleliza 16 segmentos concurrentes con 3 reintentos por segmento para
     * compensar la latencia del single-GET.
     *
     * El progress se publica via [MovieDownloadManager.publishDownloading] igual
     * que en [downloadWithResume], asi que la UI (boton de descarga, seccion de
     * home, notificacion) reacciona igual en ambos modos.
     *
     * Resume: a nivel segmento — si la descarga falla a mitad y se re-arranca el
     * servicio, los segmentos ya descargados en el dir temporal se reutilizan.
     * El archivo final .ts solo se escribe al final, asi que no hay archivo
     * parcial "roto" si la app muere a mitad de la concatenacion.
     */
    private suspend fun downloadHls(
        url: String,
        filePath: String,
        headersJson: String,
        movieId: String,
        title: String,
    ): Long {
        val targetFile = File(filePath)
        val downloader = HlsDownloader(
            client = client,
            movieId = movieId,
            title = title,
            onProgress = { downloaded, total, speedBps ->
                // Si no conocemos el total (HLS no lo sabemos hasta tener
                // todos los segmentos), pasamos total=0 — la UI muestra
                // "Descargando X MB" sin porcentaje, igual que en Remux
                // cuando el server no manda Content-Length.
                //
                // WLFMOVIE V6.3.3: speedBps == -1 es el sentinel de "fase de
                // guardado" (concatenación local de segmentos tras bajar el
                // 100%). La notificación y la UI muestran "Guardando X%" en
                // vez de una velocidad — así el usuario entiende por qué la
                // descarga sigue "viva" un rato más después de llegar al
                // tamaño final.
                val isFinalizing = speedBps < 0L
                val progress = if (total > 0L) downloaded.toFloat() / total.toFloat() else 0f
                val percent = if (total > 0L) {
                    ((downloaded.toDouble() / total.toDouble()) * 100f).toInt().coerceIn(0, 100)
                } else 0
                MovieDownloadManager.publishDownloading(
                    movieId,
                    progress,
                    downloaded,
                    total,
                    speedBps,
                )
                MovieDownloadNotifier.notifyProgress(
                    this,
                    movieId,
                    title,
                    percent,
                    humanReadableBytes(downloaded),
                    if (total > 0L) humanReadableBytes(total) else "?",
                    isFinalizing,
                )
            },
        )
        return downloader.download(url, headersJson, targetFile)
    }

    private suspend fun insertDownloadedRow(
        movieId: String,
        title: String,
        poster: String?,
        banner: String?,
        filePath: String,
        fileSizeBytes: Long,
        language: String,
        serverName: String,
        sourceUrl: String,
        headersJson: String,
    ) {
        val row = DownloadedMovie(
            id = movieId,
            title = title,
            poster = poster,
            banner = banner,
            filePath = filePath,
            fileSizeBytes = fileSizeBytes,
            durationMillis = 0L,
            watchPositionMillis = 0L,
            downloadedAtMillis = System.currentTimeMillis(),
            language = language,
            serverName = serverName,
            sourceUrl = sourceUrl,
            headersJson = headersJson,
            isWatched = false,
        )
        AppDatabase.getInstance(this@MovieDownloadService).downloadDao().insert(row)
    }

    private fun cleanupPartialFile(movieId: String, downloadDir: String) {
        runCatching {
            // Borrar ambos posibles archivos parciales (mp4 de Remux, ts de HLS)
            // ya que el cleanup se llama sin saber que kind era la descarga.
            File(downloadDir, "movie_${sanitizeFileName(movieId)}.mp4").delete()
            File(downloadDir, "movie_${sanitizeFileName(movieId)}.ts").delete()
            // Y el directorio temporal de segmentos HLS si quedo.
            File(downloadDir, "movie_${sanitizeFileName(movieId)}_segments").deleteRecursively()
            // WLFMOVIE V6.3.4: y el artwork parcial (poster/banner .jpg y .tmp)
            // si la descarga se cancelo justo mientras se descargaban las
            // imagenes (pasa despues del ultimo segmento, antes del insert).
            val safeId = sanitizeFileName(movieId)
            File(downloadDir, "poster_$safeId.jpg").delete()
            File(downloadDir, "poster_$safeId.jpg.tmp").delete()
            File(downloadDir, "banner_$safeId.jpg").delete()
            File(downloadDir, "banner_$safeId.jpg.tmp").delete()
        }
    }

    private fun ensureDownloadsDir(downloadDir: String) {
        val dir = File(downloadDir)
        if (!dir.exists()) dir.mkdirs()
        val nomedia = File(dir, ".nomedia")
        if (!nomedia.exists()) nomedia.createNewFile()
    }

    private fun toast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this@MovieDownloadService, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun humanReadableBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIdx = 0
        while (size >= 1024.0 && unitIdx < units.size - 1) {
            size /= 1024.0
            unitIdx++
        }
        return if (unitIdx == 0) "${bytes.toInt()} B"
        else String.format(java.util.Locale.US, "%.1f %s", size, units[unitIdx])
    }

    private fun sanitizeFileName(id: String): String =
        id.replace("[^A-Za-z0-9._-]".toRegex(), "_")

    companion object {
        private const val TAG = "MovieDownloadService"
        const val DIR_NAME = "downloads"

        const val ACTION_START_DOWNLOAD = "com.mew.wlfmovie.download.ACTION_START_DOWNLOAD"
        const val ACTION_CANCEL = "com.mew.wlfmovie.download.ACTION_CANCEL"

        const val EXTRA_MOVIE_ID = "extra_movie_id"
        const val EXTRA_MOVIE_TITLE = "extra_movie_title"
        const val EXTRA_MOVIE_POSTER = "extra_movie_poster"
        const val EXTRA_MOVIE_BANNER = "extra_movie_banner"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_LANGUAGE = "extra_language"
        const val EXTRA_SERVER_NAME = "extra_server_name"
        const val EXTRA_KIND = "extra_kind"
        const val EXTRA_HEADERS_JSON = "extra_headers_json"
        const val EXTRA_DOWNLOAD_DIR = "extra_download_dir"

        /**
         * Convenience helper para arrancar el servicio desde fuera (por ejemplo
         * desde MovieDownloadManager o MovieDownloadWorker).
         *
         * WLFMOVIE V6.2.4: anadido `kind` para decidir si la descarga es
         * REMUX (MP4 single-GET) o HLS (m3u8 + segmentos paralelos).
         */
        fun startDownload(
            context: Context,
            movieId: String,
            title: String,
            poster: String?,
            banner: String?,
            url: String,
            language: String,
            serverName: String,
            kind: MovieDownloadManager.Kind,
            headersJson: String,
            downloadDir: String,
        ) {
            val intent = Intent(context, MovieDownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_MOVIE_ID, movieId)
                putExtra(EXTRA_MOVIE_TITLE, title)
                putExtra(EXTRA_MOVIE_POSTER, poster)
                putExtra(EXTRA_MOVIE_BANNER, banner)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_LANGUAGE, language)
                putExtra(EXTRA_SERVER_NAME, serverName)
                putExtra(EXTRA_KIND, kind.name)
                putExtra(EXTRA_HEADERS_JSON, headersJson)
                putExtra(EXTRA_DOWNLOAD_DIR, downloadDir)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startCancel(context: Context, movieId: String) {
            val intent = Intent(context, MovieDownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_MOVIE_ID, movieId)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Si el servicio no puede arrancarse (e.g. background restrictions),
                // simplemente publica Cancelled y borrar el archivo parcial aqui mismo.
                runCatching {
                    val dir = context.getDir(DIR_NAME, Context.MODE_PRIVATE)
                    val safeId = movieId.replace("[^A-Za-z0-9._-]".toRegex(), "_")
                    // WLFMOVIE V6.3.2: limpiar TODOS los posibles parciales
                    // (mp4 de Remux, ts de HLS, y dir de segmentos HLS).
                    // Antes solo se borraba el .mp4, dejando GBs de basura
                    // cuando se cancelaba una descarga HLS por fallback.
                    File(dir, "movie_${safeId}.mp4").delete()
                    File(dir, "movie_${safeId}.ts").delete()
                    File(dir, "movie_${safeId}_segments").deleteRecursively()
                    // WLFMOVIE V6.3.4: artwork parcial tambien (ver cleanupPartialFile).
                    File(dir, "poster_${safeId}.jpg").delete()
                    File(dir, "poster_${safeId}.jpg.tmp").delete()
                    File(dir, "banner_${safeId}.jpg").delete()
                    File(dir, "banner_${safeId}.jpg.tmp").delete()
                }
                MovieDownloadManager.publishCancelled(movieId)
            }
        }
    }
}
