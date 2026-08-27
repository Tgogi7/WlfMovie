package com.mew.wlfmovie.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * Worker que arranca [MovieDownloadService] con ACTION_START_DOWNLOAD.
 *
 * Es el "retry mechanism" de WorkManager: si el foreground service muere antes
 * de completar la descarga (e.g. la app fue matada por el sistema), WorkManager
 * reintentara segun el backoff configurado, y este Worker re-arrancara el servicio.
 * El servicio, al arrancar, lee el archivo parcial existente y reanuda con HTTP Range.
 *
 * WLFMOVIE V6.2.4: el `kind` decide si la descarga es REMUX (MP4 single-GET con
 * Range) o HLS (m3u8 + segmentos paralelos). Se pasa como string ("REMUX" / "HLS")
 * y el service lo parsea a [MovieDownloadManager.Kind].
 */
class MovieDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val movieId = inputData.getString(KEY_MOVIE_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_MOVIE_TITLE) ?: movieId
        val poster = inputData.getString(KEY_MOVIE_POSTER)
        val banner = inputData.getString(KEY_MOVIE_BANNER)
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val language = inputData.getString(KEY_LANGUAGE) ?: "latino"
        val serverName = inputData.getString(KEY_SERVER_NAME) ?: "Remux"
        val kindStr = inputData.getString(KEY_KIND) ?: MovieDownloadManager.Kind.REMUX.name
        val kind = runCatching { MovieDownloadManager.Kind.valueOf(kindStr) }
            .getOrDefault(MovieDownloadManager.Kind.REMUX)
        val headersJson = inputData.getString(KEY_HEADERS_JSON) ?: "{}"
        val downloadDir = inputData.getString(KEY_DOWNLOAD_DIR)
            ?: applicationContext.getDir(MovieDownloadService.DIR_NAME, Context.MODE_PRIVATE).absolutePath

        return try {
            MovieDownloadService.startDownload(
                applicationContext,
                movieId,
                title,
                poster,
                banner,
                url,
                language,
                serverName,
                kind,
                headersJson,
                downloadDir,
            )
            Result.success()
        } catch (e: Throwable) {
            // WLFMOVIE V6.2.2: Registrar el fallo en lugar de tragarlo
            // silenciosamente. Mismo bug que MovieDownloadManager.startDownload —
            // si no logueamos, el usuario ve "iniciada" pero no pasa nada.
            com.mew.wlfmovie.utils.CrashLogger.logError(
                tag = "DownloadWorker",
                message = "Worker failed to start service for $movieId: ${e.message}",
                throwable = e,
                context = "url=$url, server=$serverName, lang=$language, kind=$kind",
            )
            Result.failure()
        }
    }

    companion object {
        const val KEY_MOVIE_ID = "movie_id"
        const val KEY_MOVIE_TITLE = "movie_title"
        const val KEY_MOVIE_POSTER = "movie_poster"
        const val KEY_MOVIE_BANNER = "movie_banner"
        const val KEY_URL = "url"
        const val KEY_LANGUAGE = "language"
        const val KEY_SERVER_NAME = "server_name"
        const val KEY_KIND = "kind"
        const val KEY_HEADERS_JSON = "headers_json"
        const val KEY_DOWNLOAD_DIR = "download_dir"

        fun inputDataOf(
            movieId: String,
            title: String,
            poster: String?,
            banner: String?,
            url: String,
            language: String,
            serverName: String,
            kind: String,
            headersJson: String,
            downloadDir: String,
        ) = workDataOf(
            KEY_MOVIE_ID to movieId,
            KEY_MOVIE_TITLE to title,
            KEY_MOVIE_POSTER to poster,
            KEY_MOVIE_BANNER to banner,
            KEY_URL to url,
            KEY_LANGUAGE to language,
            KEY_SERVER_NAME to serverName,
            KEY_KIND to kind,
            KEY_HEADERS_JSON to headersJson,
            KEY_DOWNLOAD_DIR to downloadDir,
        )
    }
}

