package com.mew.wlfmovie.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mew.wlfmovie.models.DownloadedMovie
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY downloadedAtMillis DESC")
    fun getAllAsFlow(): Flow<List<DownloadedMovie>>

    @Query("SELECT * FROM downloads ORDER BY downloadedAtMillis DESC")
    suspend fun getAll(): List<DownloadedMovie>

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun getById(id: String): DownloadedMovie?

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun getByIdAsFlow(id: String): Flow<DownloadedMovie?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadedMovie)

    @Query("UPDATE downloads SET watchPositionMillis = :position, durationMillis = :duration, isWatched = :isWatched WHERE id = :id")
    suspend fun updateProgress(id: String, position: Long, duration: Long, isWatched: Boolean)

    // V6.6.0: antes lo usaba DownloadConverter para re-apuntar la ruta tras
    // convertir .ts → .mp4. El converter fue eliminado y este método quedó
    // sin llamadas — se conserva por si en el futuro se re-apunta la ruta
    // (mismas columnas, sin cambio de esquema).
    @Query("UPDATE downloads SET filePath = :filePath, fileSizeBytes = :fileSizeBytes WHERE id = :id")
    suspend fun updateFilePath(id: String, filePath: String, fileSizeBytes: Long)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)
}
