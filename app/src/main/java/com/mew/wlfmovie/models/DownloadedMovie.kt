package com.mew.wlfmovie.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Representa una pelicula descargada localmente.
 *
 * Esta tabla es 100% LOCAL y AISLADA del sync de nube.
 * SyncManager nunca la lee ni la escribe.
 * Si el usuario hace sync de nube, las descargas locales no se pierden.
 * Si el usuario descarga algo, el sync no se altera.
 */
@Entity(tableName = "downloads")
data class DownloadedMovie(
    @PrimaryKey
    val id: String,                       // = movie.id (TMDb id)
    val title: String,
    val poster: String?,
    val banner: String?,
    val filePath: String,                 // /data/.../files/downloads/movie_{id}.mp4
    val fileSizeBytes: Long,
    val durationMillis: Long = 0L,        // se llena al reproducir offline por primera vez
    val watchPositionMillis: Long = 0L,   // progreso LOCAL (no se sincroniza)
    val downloadedAtMillis: Long,
    val language: String,                // "latino" | "subtitulado" | "castellano"
    val serverName: String,               // "Remux - Latino"
    val sourceUrl: String,                // URL original (para re-descargar si hace falta)
    val headersJson: String = "{}",      // headers HTTP serializados (vacío en V7)
    val isWatched: Boolean = false,       // LOCAL (no se sincroniza)
)
