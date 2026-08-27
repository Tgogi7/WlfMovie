package com.mew.wlfmovie.download

/**
 * Estado observable de una descarga, para que la UI (boton en detalles, seccion en home)
 * reaccione en tiempo real.
 */
sealed class DownloadProgressState {

    /** No hay descarga en curso ni descarga completada para esta pelicula. */
    data object Idle : DownloadProgressState()

    /** La descarga esta en curso. */
    data class Downloading(
        val progress: Float,             // 0f..1f
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBps: Long,
    ) : DownloadProgressState()

    /** La descarga termino y el archivo esta listo para reproducir offline. */
    data class Completed(
        val filePath: String,
    ) : DownloadProgressState()

    /** La descarga fallo. */
    data class Failed(
        val error: String,
    ) : DownloadProgressState()

    /** La descarga fue cancelada por el usuario. */
    data object Cancelled : DownloadProgressState()
}
