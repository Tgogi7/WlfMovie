package com.mew.wlfmovie.utils

import android.content.Context
import android.util.Log
import com.mew.wlfmovie.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object SyncManager {

    private const val TAG = "WlfMovie-Sync"

    fun formatLastSync(timestamp: String?): String {
        if (timestamp == null || timestamp.isBlank()) return "Nunca"
        try {
            val cleanTs = timestamp.replace("+00:00", "Z").replace("+0000", "Z")
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val date = try { sdf.parse(cleanTs) } catch (e: Exception) {
                val sdf2 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                sdf2.timeZone = TimeZone.getTimeZone("UTC")
                sdf2.parse(cleanTs)
            } ?: return "Nunca"
            val out = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)
            out.timeZone = TimeZone.getTimeZone("America/Bogota")
            return out.format(date)
        } catch (e: Exception) { return "Hace poco" }
    }

    fun formatRelativeSync(timestamp: String?): String {
        if (timestamp == null || timestamp.isBlank()) return "Sin sincronizar"
        try {
            val cleanTs = timestamp.replace("+00:00", "Z").replace("+0000", "Z")
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val date = sdf.parse(cleanTs) ?: return "Hace poco"
            val diffMin = (Date().time - date.time) / 60000
            return when {
                diffMin < 1 -> "Ahora mismo"
                diffMin < 60 -> "Hace $diffMin min"
                diffMin < 1440 -> "Hace ${diffMin / 60} h"
                else -> "Hace ${diffMin / 1440} días"
            }
        } catch (e: Exception) { return "Hace poco" }
    }

    suspend fun collectLocalData(context: Context): String = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val json = JSONObject()

        // FAVORITOS
        val favMovies = db.movieDao().getFavorites().first()
        val favTvShows = db.tvShowDao().getFavorites().first()

        val favorites = JSONObject()
        val moviesArr = JSONArray()
        favMovies.forEach { movie ->
            val m = JSONObject()
            m.put("id", movie.id); m.put("title", movie.title ?: "")
            m.put("poster", movie.poster ?: ""); m.put("banner", movie.banner ?: "")
            m.put("released", movie.released?.let { it.format("yyyy-MM-dd") } ?: "")
            m.put("rating", movie.rating ?: 0); m.put("overview", movie.overview ?: "")
            m.put("imdbId", movie.imdbId ?: "")
            moviesArr.put(m)
        }
        favorites.put("movies", moviesArr)

        val tvArr = JSONArray()
        favTvShows.forEach { tv ->
            val t = JSONObject()
            t.put("id", tv.id); t.put("title", tv.title ?: "")
            t.put("poster", tv.poster ?: ""); t.put("banner", tv.banner ?: "")
            t.put("released", tv.released?.let { it.format("yyyy-MM-dd") } ?: "")
            t.put("rating", tv.rating ?: 0); t.put("overview", tv.overview ?: "")
            t.put("imdbId", tv.imdbId ?: "")
            tvArr.put(t)
        }
        favorites.put("tvShows", tvArr)
        json.put("favorites", favorites)

        // WATCH LATER
        val prefs = context.getSharedPreferences("wlfmovie_watch_later", Context.MODE_PRIVATE)
        val watchLater = JSONObject()
        watchLater.put("movies", JSONArray(prefs.getStringSet("movies", emptySet())!!))
        watchLater.put("tvShows", JSONArray(prefs.getStringSet("tv_shows", emptySet())!!))
        json.put("watchLater", watchLater)

        // CONTINUE WATCHING - movies (con imdbId)
        val allMovies = db.movieDao().getAll()
        val cwMovies = JSONArray()
        allMovies.forEach { movie ->
            if (movie.watchHistory != null) {
                val cw = JSONObject()
                cw.put("id", movie.id); cw.put("title", movie.title ?: "")
                cw.put("poster", movie.poster ?: ""); cw.put("banner", movie.banner ?: "")
                cw.put("released", movie.released?.let { it.format("yyyy-MM-dd") } ?: "")
                cw.put("rating", movie.rating ?: 0); cw.put("overview", movie.overview ?: "")
                cw.put("imdbId", movie.imdbId ?: "")
                cw.put("lastPosition", movie.watchHistory!!.lastPlaybackPositionMillis)
                cw.put("duration", movie.watchHistory!!.durationMillis)
                cwMovies.put(cw)
            }
        }

        // CONTINUE WATCHING - episodes (con imdbId del tvShow)
        val allEpisodes = db.episodeDao().getAllForBackup()
        val cwEpisodes = JSONArray()
        allEpisodes.forEach { episode ->
            if (episode.watchHistory != null && !episode.isWatched) {
                val cw = JSONObject()
                cw.put("id", episode.id); cw.put("title", episode.title ?: "")
                cw.put("poster", episode.poster ?: ""); cw.put("overview", episode.overview ?: "")
                cw.put("number", episode.number)
                cw.put("lastPosition", episode.watchHistory!!.lastPlaybackPositionMillis)
                cw.put("duration", episode.watchHistory!!.durationMillis)
                episode.tvShow?.let { tv ->
                    cw.put("tvShowId", tv.id); cw.put("tvShowTitle", tv.title ?: "")
                    cw.put("tvShowPoster", tv.poster ?: ""); cw.put("tvShowBanner", tv.banner ?: "")
                    cw.put("tvShowImdbId", tv.imdbId ?: "")
                    cw.put("tvShowReleased", tv.released?.let { it.format("yyyy-MM-dd") } ?: "")
                }
                episode.season?.let { season ->
                    cw.put("seasonId", season.id ?: "")
                    cw.put("seasonNumber", season.number)
                    cw.put("seasonTitle", season.title ?: "")
                }
                cwEpisodes.put(cw)
            }
        }

        val continueWatching = JSONObject()
        continueWatching.put("movies", cwMovies)
        continueWatching.put("episodes", cwEpisodes)
        json.put("continueWatching", continueWatching)

        json.toString()
    }

    suspend fun clearLocalData(context: Context) = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            val allMovies = db.movieDao().getAll()
            allMovies.forEach { movie ->
                var changed = false
                if (movie.isFavorite) { movie.isFavorite = false; movie.favoritedAtMillis = null; changed = true }
                if (movie.watchHistory != null) { movie.watchHistory = null; changed = true }
                if (changed) db.movieDao().insert(movie)
            }
            val allTvShows = db.tvShowDao().getAll().first()
            allTvShows.forEach { tv ->
                if (tv.isFavorite) { tv.isFavorite = false; tv.favoritedAtMillis = null; db.tvShowDao().insert(tv) }
            }
            val allEpisodes = db.episodeDao().getAllForBackup()
            allEpisodes.forEach { ep ->
                if (ep.watchHistory != null || ep.isWatched) {
                    ep.watchHistory = null; ep.isWatched = false
                    db.episodeDao().insert(ep)
                }
            }
            context.getSharedPreferences("wlfmovie_watch_later", Context.MODE_PRIVATE)
                .edit().putStringSet("movies", emptySet()).putStringSet("tv_shows", emptySet()).apply()
            Log.i(TAG, "Datos locales limpiados")
        } catch (e: Exception) { Log.e(TAG, "Error clearLocalData: ${e.message}") }
    }

    suspend fun applyRemoteData(context: Context, jsonStr: String, clearFirst: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            if (clearFirst) clearLocalData(context)
            val db = AppDatabase.getInstance(context)
            val json = JSONObject(jsonStr)

            // Favoritos movies (con imdbId)
            json.optJSONObject("favorites")?.optJSONArray("movies")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val existing = db.movieDao().getById(m.getString("id"))
                    if (existing == null) {
                        val movie = com.mew.wlfmovie.models.Movie(
                            id = m.getString("id"), title = m.optString("title", ""),
                            overview = m.optString("overview", null),
                            released = m.optString("released", null),
                            rating = m.optDouble("rating", 0.0),
                            poster = m.optString("poster", null),
                            banner = m.optString("banner", null))
                        movie.isFavorite = true; movie.favoritedAtMillis = System.currentTimeMillis()
                        movie.imdbId = m.optString("imdbId", null)?.takeIf { it.isNotBlank() }
                        db.movieDao().insert(movie)
                    } else if (!existing.isFavorite) {
                        existing.isFavorite = true; existing.favoritedAtMillis = System.currentTimeMillis()
                        if (existing.imdbId.isNullOrBlank()) {
                            existing.imdbId = m.optString("imdbId", null)?.takeIf { it.isNotBlank() }
                        }
                        db.movieDao().insert(existing)
                    }
                }
            }

            // Favoritos series (con imdbId)
            json.optJSONObject("favorites")?.optJSONArray("tvShows")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    val existing = db.tvShowDao().getById(t.getString("id"))
                    if (existing == null) {
                        val tvShow = com.mew.wlfmovie.models.TvShow(
                            id = t.getString("id"), title = t.optString("title", ""),
                            overview = t.optString("overview", null),
                            released = t.optString("released", null),
                            rating = t.optDouble("rating", 0.0),
                            poster = t.optString("poster", null),
                            banner = t.optString("banner", null))
                        tvShow.isFavorite = true; tvShow.favoritedAtMillis = System.currentTimeMillis()
                        tvShow.imdbId = t.optString("imdbId", null)?.takeIf { it.isNotBlank() }
                        db.tvShowDao().insert(tvShow)
                    } else if (!existing.isFavorite) {
                        existing.isFavorite = true; existing.favoritedAtMillis = System.currentTimeMillis()
                        if (existing.imdbId.isNullOrBlank()) {
                            existing.imdbId = t.optString("imdbId", null)?.takeIf { it.isNotBlank() }
                        }
                        db.tvShowDao().insert(existing)
                    }
                }
            }

            // Watch later
            json.optJSONObject("watchLater")?.let { wl ->
                val prefs = context.getSharedPreferences("wlfmovie_watch_later", Context.MODE_PRIVATE)
                val movieSet = if (clearFirst) mutableSetOf() else prefs.getStringSet("movies", emptySet())!!.toMutableSet()
                val tvSet = if (clearFirst) mutableSetOf() else prefs.getStringSet("tv_shows", emptySet())!!.toMutableSet()
                wl.optJSONArray("movies")?.let { arr -> for (i in 0 until arr.length()) movieSet.add(arr.getString(i)) }
                wl.optJSONArray("tvShows")?.let { arr -> for (i in 0 until arr.length()) tvSet.add(arr.getString(i)) }
                prefs.edit().putStringSet("movies", movieSet).putStringSet("tv_shows", tvSet).apply()
            }

            // Continue watching movies (con imdbId)
            json.optJSONObject("continueWatching")?.optJSONArray("movies")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val existing = db.movieDao().getById(m.getString("id"))
                    val movie = existing ?: com.mew.wlfmovie.models.Movie(
                        id = m.getString("id"), title = m.optString("title", ""),
                        overview = m.optString("overview", null),
                        released = m.optString("released", null),
                        rating = m.optDouble("rating", 0.0),
                        poster = m.optString("poster", null),
                        banner = m.optString("banner", null))
                    movie.watchHistory = com.mew.wlfmovie.models.WatchItem.WatchHistory(
                        lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                        lastPlaybackPositionMillis = m.optLong("lastPosition", 0),
                        durationMillis = m.optLong("duration", 0))
                    movie.imdbId = m.optString("imdbId", null)?.takeIf { it.isNotBlank() } ?: movie.imdbId
                    db.movieDao().insert(movie)
                }
            }

            // Continue watching episodes (con imdbId del tvShow y season completos)
            json.optJSONObject("continueWatching")?.optJSONArray("episodes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val existing = db.episodeDao().getById(e.getString("id"))

                    // WLFMOVIE: Primero asegurar que el TvShow existe en la DB con TODOS los datos
                    val tvShowId = e.optString("tvShowId", null)
                    if (!tvShowId.isNullOrBlank()) {
                        val tvShowExisting = db.tvShowDao().getById(tvShowId)
                        if (tvShowExisting == null) {
                            val tvShow = com.mew.wlfmovie.models.TvShow(
                                id = tvShowId,
                                title = e.optString("tvShowTitle", ""),
                                poster = e.optString("tvShowPoster", null),
                                banner = e.optString("tvShowBanner", null),
                                released = e.optString("tvShowReleased", null))
                            tvShow.imdbId = e.optString("tvShowImdbId", null)?.takeIf { it.isNotBlank() }
                            db.tvShowDao().insert(tvShow)
                        } else {
                            // Actualizar imdbId si falta
                            if (tvShowExisting.imdbId.isNullOrBlank()) {
                                tvShowExisting.imdbId = e.optString("tvShowImdbId", null)?.takeIf { it.isNotBlank() }
                                db.tvShowDao().insert(tvShowExisting)
                            }
                        }
                    }

                    // Asegurar que el Season existe
                    val seasonId = e.optString("seasonId", null)?.takeIf { it.isNotBlank() }
                        ?: "${tvShowId}_s${e.optInt("seasonNumber", 1)}"
                    val seasonNumber = e.optInt("seasonNumber", 1)
                    val seasonTitle = e.optString("seasonTitle", "")

                    if (existing == null) {
                        // Crear episodio nuevo
                        val episode = com.mew.wlfmovie.models.Episode(
                            id = e.getString("id"),
                            number = e.optInt("number", 1),
                            title = e.optString("title", null),
                            poster = e.optString("poster", null),
                            overview = e.optString("overview", null))
                        episode.watchHistory = com.mew.wlfmovie.models.WatchItem.WatchHistory(
                            lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                            lastPlaybackPositionMillis = e.optLong("lastPosition", 0),
                            durationMillis = e.optLong("duration", 0))

                        // WLFMOVIE: Room usa TypeConverter para guardar tvShow y season.
                        // Guarda solo el ID. Al leerlo, crea un TvShow(id, "") vacío.
                        // Por eso necesitamos que el TvShow exista en la DB con imdbId.
                        if (!tvShowId.isNullOrBlank()) {
                            episode.tvShow = com.mew.wlfmovie.models.TvShow(tvShowId, e.optString("tvShowTitle", ""))
                        }
                        episode.season = com.mew.wlfmovie.models.Season(seasonId, seasonNumber, seasonTitle)
                        db.episodeDao().insert(episode)
                    } else if (existing.watchHistory == null) {
                        existing.watchHistory = com.mew.wlfmovie.models.WatchItem.WatchHistory(
                            lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                            lastPlaybackPositionMillis = e.optLong("lastPosition", 0),
                            durationMillis = e.optLong("duration", 0))
                        db.episodeDao().insert(existing)
                    }
                }
            }
            Log.i(TAG, "Datos remotos aplicados (clearFirst=$clearFirst)")
        } catch (e: Exception) { Log.e(TAG, "Error applyRemoteData: ${e.message}") }
    }

    suspend fun upload(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val session = AccountManager.getSession(context) ?: return@withContext false
            val localData = collectLocalData(context)
            val encrypted = CryptoUtils.encrypt(localData, session.email)
            val success = SupabaseClient.uploadSyncData(session.userId, encrypted)
            if (success) {
                SupabaseClient.getLastSync(session.userId)?.let { AccountManager.saveLastSync(context, it) }
            }
            success
        } catch (e: Exception) { Log.e(TAG, "Error upload: ${e.message}"); false }
    }

    suspend fun download(context: Context, clearFirst: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            val session = AccountManager.getSession(context) ?: return@withContext false
            val result = SupabaseClient.downloadSyncData(session.userId) ?: return@withContext false
            val (syncData, lastSync) = result
            if (syncData != null) {
                val decrypted = CryptoUtils.decrypt(syncData, session.email)
                if (decrypted != null) applyRemoteData(context, decrypted, clearFirst)
            } else {
                if (clearFirst) clearLocalData(context)
            }
            lastSync?.let { AccountManager.saveLastSync(context, it) }
            true
        } catch (e: Exception) { Log.e(TAG, "Error download: ${e.message}"); false }
    }

    fun autoUpload(context: Context) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                if (AccountManager.getSession(context) != null) {
                    Log.i(TAG, "Auto-upload triggered")
                    upload(context)
                    // WLFMOVIE V4: Actualizar indicador de nube
                }
            } catch (e: Exception) { Log.e(TAG, "Error auto-upload: ${e.message}") }
        }
    }

    suspend fun autoDownloadIfNewer(context: Context) = withContext(Dispatchers.IO) {
        try {
            val session = AccountManager.getSession(context) ?: return@withContext
            val remoteLastSync = SupabaseClient.getLastSync(session.userId) ?: return@withContext
            val localLastSync = AccountManager.getLastSync(context)
            if (localLastSync == null || remoteLastSync > localLastSync) {
                Log.i(TAG, "Auto-download: servidor más reciente")
                download(context, clearFirst = false)
            } else {
                Log.i(TAG, "Auto-download: ya actualizado")
            }
        } catch (e: Exception) { Log.e(TAG, "Error auto-download: ${e.message}") }
    }
}
