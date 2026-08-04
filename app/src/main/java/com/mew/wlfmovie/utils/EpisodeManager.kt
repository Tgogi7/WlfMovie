package com.mew.wlfmovie.utils

import android.util.Log
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.models.Season
import com.mew.wlfmovie.models.TvShow
import com.mew.wlfmovie.models.Video
import com.mew.wlfmovie.models.Video.Type.Episode

object EpisodeManager {
    private const val TAG = "WlfMovie-EpisodeMgr"

    private val episodes = mutableListOf<Episode>()
    var currentIndex = 0
        private set

    fun addEpisodes(list: List<Episode>) {
        Log.i(TAG, "addEpisodes: ${list.size} episodios. Primero=${list.firstOrNull()?.let { "S${it.season.number}E${it.number}" }}, Último=${list.lastOrNull()?.let { "S${it.season.number}E${it.number}" }}")
        episodes.clear()
        episodes.addAll(list)
        currentIndex = 0
        logEpisodes()
    }

    private fun mergeEpisodes(list: List<Episode>) {
        if (list.isEmpty()) return

        Log.i(TAG, "mergeEpisodes: existing=${episodes.size}, new=${list.size}")
        Log.i(TAG, "mergeEpisodes: new episodes = ${list.joinToString { "S${it.season.number}E${it.number}" }}")

        val currentEpisodeId = getCurrentEpisode()?.id
        val merged = (episodes + list)
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.season.number }, { it.number }))

        episodes.clear()
        episodes.addAll(merged)

        currentIndex = currentEpisodeId
            ?.let { id -> episodes.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            ?: 0

        Log.i(TAG, "mergeEpisodes: merged=${episodes.size}, currentIndex=$currentIndex (current=${getCurrentEpisode()?.let { "S${it.season.number}E${it.number}" }})")
        logEpisodes()
    }

    suspend fun addEpisodesFromDb(type: Video.Type.Episode, database: AppDatabase) {
        Log.i(TAG, "addEpisodesFromDb: tvShow=${type.tvShow.id}, season=${type.season.number}, currentEpisode=${type.id}")
        val tvShowId = type.tvShow.id
        val seasonNumber = type.season.number
        var episodesFromDb = database.episodeDao().getByTvShowIdAndSeasonNumber(tvShowId, seasonNumber)
        Log.i(TAG, "addEpisodesFromDb: DB returned ${episodesFromDb.size} episodes for S${seasonNumber}")
        val tvShowContext = database.tvShowDao().getById(tvShowId)?.let { storedTvShow ->
            TvShow(
                id = storedTvShow.id,
                title = storedTvShow.title,
                poster = storedTvShow.poster,
                banner = storedTvShow.banner,
                released = storedTvShow.released?.format("yyyy-MM-dd"),
                imdbId = storedTvShow.imdbId
            )
        } ?: TvShow(
            id = type.tvShow.id,
            title = type.tvShow.title,
            poster = type.tvShow.poster,
            banner = type.tvShow.banner,
            imdbId = type.tvShow.imdbId
        )
        val seasonContext = Season(id = "", number = seasonNumber, title = type.season.title).apply {
            tvShow = tvShowContext
        }
        val provider = UserPreferences.currentProvider
        if (provider != null) {
            try {
                val tvShow = provider.getTvShow(tvShowId)
                val season = tvShow.seasons.find { it.number == seasonNumber }
                if (season != null) {
                    val fetchedEpisodes = provider.getEpisodesBySeason(season.id)
                    Log.i(TAG, "addEpisodesFromDb: provider returned ${fetchedEpisodes.size} episodes for S${seasonNumber}")
                    if (fetchedEpisodes.isNotEmpty()) {
                        fetchedEpisodes.forEach { episode ->
                            episode.tvShow = episode.tvShow ?: tvShow
                            episode.season = episode.season ?: season
                        }
                        database.episodeDao().insertAll(fetchedEpisodes)
                        episodesFromDb = fetchedEpisodes
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "addEpisodesFromDb: provider error: ${e.message}")
                e.printStackTrace()
            }
        }

        if (episodesFromDb.isNotEmpty()) {
            episodesFromDb.forEach { episode ->
                episode.tvShow = episode.tvShow ?: tvShowContext
                episode.season = episode.season ?: seasonContext
            }
            addEpisodes(convertToVideoTypeEpisodes(episodesFromDb, database, seasonNumber))
        }
    }

    suspend fun ensureNextEpisodeAvailable(type: Video.Type.Episode, database: AppDatabase): Boolean {
        Log.i(TAG, "ensureNextEpisodeAvailable: checking for next after ${type.id}")
        if (hasNextEpisode()) {
            val next = peekNextEpisode()
            Log.i(TAG, "ensureNextEpisodeAvailable: already have next: ${next?.let { "S${it.season.number}E${it.number}" }}")
            return true
        }

        val currentEpisode = getCurrentEpisode()
            ?.takeIf { current -> current.id == type.id }
            ?: type
        val provider = UserPreferences.currentProvider ?: return false
        val tvShowId = currentEpisode.tvShow.id
        val currentSeasonNumber = currentEpisode.season.number

        Log.i(TAG, "ensureNextEpisodeAvailable: current=S${currentSeasonNumber}E${currentEpisode.number} (${currentEpisode.id}), looking for next season")

        fun nextSeasonFrom(seasons: List<Season>): Season? =
            seasons
                .filter { season -> season.number > currentSeasonNumber }
                .sortedBy { season -> season.number }
                .firstOrNull()

        var nextSeason = nextSeasonFrom(database.seasonDao().getByTvShowId(tvShowId))

        if (nextSeason == null) {
            Log.i(TAG, "ensureNextEpisodeAvailable: no next season in DB, fetching from provider")
            runCatching { provider.getTvShow(tvShowId) }
                .getOrNull()
                ?.also { tvShow ->
                    database.tvShowDao().save(tvShow)
                    tvShow.seasons.forEach { season ->
                        season.tvShow = tvShow
                    }
                    database.seasonDao().insertAll(tvShow.seasons)
                    nextSeason = nextSeasonFrom(tvShow.seasons)
                }
        }

        val seasonToLoad = nextSeason ?: run {
            Log.i(TAG, "ensureNextEpisodeAvailable: no next season found")
            return false
        }

        Log.i(TAG, "ensureNextEpisodeAvailable: loading episodes for S${seasonToLoad.number}")

        var nextSeasonEpisodes = database.episodeDao()
            .getByTvShowIdAndSeasonNumber(tvShowId, seasonToLoad.number)

        if (nextSeasonEpisodes.isEmpty() && seasonToLoad.id.isNotBlank()) {
            nextSeasonEpisodes = runCatching {
                provider.getEpisodesBySeason(seasonToLoad.id)
            }.getOrDefault(emptyList()).also { fetchedEpisodes ->
                if (fetchedEpisodes.isNotEmpty()) {
                    fetchedEpisodes.forEach { episode ->
                        episode.tvShow = episode.tvShow ?: seasonToLoad.tvShow
                        episode.season = episode.season ?: seasonToLoad
                    }
                    database.episodeDao().insertAll(fetchedEpisodes)
                }
            }
        }

        if (nextSeasonEpisodes.isEmpty()) {
            Log.i(TAG, "ensureNextEpisodeAvailable: no episodes found for S${seasonToLoad.number}")
            return false
        }

        nextSeasonEpisodes.forEach { episode ->
            episode.tvShow = episode.tvShow ?: seasonToLoad.tvShow
            episode.season = episode.season ?: seasonToLoad
        }

        mergeEpisodes(convertToVideoTypeEpisodes(nextSeasonEpisodes, database, seasonToLoad.number))
        val result = hasNextEpisode()
        Log.i(TAG, "ensureNextEpisodeAvailable: result=$result, next=${peekNextEpisode()?.let { "S${it.season.number}E${it.number}" }}")
        return result
    }

    fun clearEpisodes(){
        Log.i(TAG, "clearEpisodes")
        episodes.clear()
        currentIndex = 0
    }

    fun setCurrentEpisode(episode: Episode) {
        val index = episodes.indexOfFirst { it.id == episode.id }
        Log.i(TAG, "setCurrentEpisode: looking for ${episode.id} (S${episode.season.number}E${episode.number}), found at index=$index, listSize=${episodes.size}")
        if (index >= 0) {
            currentIndex = index
        } else {
            Log.w(TAG, "setCurrentEpisode: episode NOT FOUND in list! Current index stays at $currentIndex")
        }
        logEpisodes()
    }

    fun getCurrentEpisode(): Episode? =
        episodes.getOrNull(currentIndex)

    fun peekNextEpisode(): Episode? =
        episodes.getOrNull(currentIndex + 1)

    fun getNextEpisode(): Episode? {
        if (currentIndex + 1 < episodes.size) {
            currentIndex++
            Log.i(TAG, "getNextEpisode: returning S${episodes[currentIndex].season.number}E${episodes[currentIndex].number} (index=$currentIndex)")
            return episodes[currentIndex]
        }
        Log.w(TAG, "getNextEpisode: no next episode (index=$currentIndex, size=${episodes.size})")
        return null
    }

    fun getPreviousEpisode(): Episode? {
        if (currentIndex -1 >= 0){
            currentIndex--
            Log.i(TAG, "getPreviousEpisode: returning S${episodes[currentIndex].season.number}E${episodes[currentIndex].number} (index=$currentIndex)")
            return episodes[currentIndex]
        }
        Log.w(TAG, "getPreviousEpisode: no previous episode (index=$currentIndex)")
        return null
    }

    fun hasPreviousEpisode(): Boolean {
        return currentIndex > 0
    }

    fun hasNextEpisode(): Boolean {
        return currentIndex < episodes.size - 1
    }

    fun listIsEmpty(episode: Episode): Boolean{
        return episodes.isEmpty() || episodes.none { it.id == episode.id }
    }

    fun convertToVideoTypeEpisodes(episodes: List<com.mew.wlfmovie.models.Episode>, database: AppDatabase, seasonNumber: Int): List<Episode> {
        val videoEpisodes = episodes.map { ep ->
            val seasonId = ep.season?.id ?: ""
            val tvShowId = ep.tvShow?.id ?: ""
            val seasonFromDb = database.seasonDao().getById(seasonId)
            val tvShowFromDb = database.tvShowDao().getById(tvShowId)
            val tvShowTitle = tvShowFromDb?.title?.takeUnless { it.isBlank() }
                ?: ep.tvShow?.title
                ?: ""
            Episode(
                id = ep.id,
                number = ep.number,
                title = ep.title,
                poster = ep.poster,
                overview = ep.overview,
                tvShow = Episode.TvShow(
                    id = tvShowId,
                    title = tvShowTitle,
                    poster = tvShowFromDb?.poster ?: ep.tvShow?.poster,
                    banner = tvShowFromDb?.banner ?: ep.tvShow?.banner,
                    releaseDate = tvShowFromDb?.released?.format("yyyy-MM-dd") ?: ep.tvShow?.released?.format("yyyy-MM-dd"),
                    imdbId = tvShowFromDb?.imdbId ?: ep.tvShow?.imdbId
                ),
                season = Episode.Season(
                    number = seasonFromDb?.number ?: seasonNumber,
                    title = seasonFromDb?.title ?: ep.season?.title
                )
            )
        }
        return videoEpisodes
    }

    private fun logEpisodes() {
        if (episodes.isEmpty()) {
            Log.i(TAG, "Episodes list is EMPTY")
            return
        }
        val sb = StringBuilder()
        episodes.forEachIndexed { index, ep ->
            val marker = if (index == currentIndex) " ► " else "   "
            sb.append("$marker[$index] S${ep.season.number}E${ep.number} (${ep.id})\n")
        }
        Log.i(TAG, "Episodes list:\n$sb")
    }
}
