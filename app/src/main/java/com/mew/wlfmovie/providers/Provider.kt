package com.mew.wlfmovie.providers

import com.mew.wlfmovie.adapters.AppAdapter
import com.mew.wlfmovie.models.Category
import com.mew.wlfmovie.models.Episode
import com.mew.wlfmovie.models.Genre
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.models.People
import com.mew.wlfmovie.models.TvShow
import com.mew.wlfmovie.models.Video
import kotlinx.coroutines.sync.Mutex

interface ProviderPortalUrl {
    val portalUrl: String
    val defaultPortalUrl: String
}

interface ProviderConfigUrl {
    val defaultBaseUrl: String

    suspend fun onChangeUrl(forceRefresh: Boolean = false): String
    val changeUrlMutex: Mutex
}

interface IptvProvider : Provider

interface Provider {

    val baseUrl: String
    val name: String
    val logo: String
    val language: String

    suspend fun getHome(): List<Category>

    suspend fun search(query: String, page: Int = 1): List<AppAdapter.Item>

    suspend fun getMovies(page: Int = 1): List<Movie>

    suspend fun getTvShows(page: Int = 1): List<TvShow>

    suspend fun getMovie(id: String): Movie

    suspend fun getTvShow(id: String): TvShow

    suspend fun getEpisodesBySeason(seasonId: String): List<Episode>

    suspend fun getGenre(id: String, page: Int = 1): Genre

    suspend fun getPeople(id: String, page: Int = 1): People

    suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server>

    suspend fun getVideo(server: Video.Server): Video

    companion object {
        data class ProviderSupport(
            val movies: Boolean,
            val tvShows: Boolean
        )

        // =====================================================================
        // WLFMOVIE: Solo providers de PELICULAS/SERIES en ESPAÑOL.
        // NO se incluyen: anime, IPTV puro, ni providers de otros idiomas.
        // Cine24h removido por dar 403 siempre.
        // TMDB (es) se gestiona por separado (ver UserPreferences.currentProvider)
        // =====================================================================
        val providers = mapOf(
            CuevanaEuProvider     to ProviderSupport(movies = true, tvShows = true),
            CineCalidadProvider   to ProviderSupport(movies = true, tvShows = true),
            PoseidonHD2Provider   to ProviderSupport(movies = true, tvShows = true),
            PelisplustoProvider   to ProviderSupport(movies = true, tvShows = true),
            PelisflixHdProvider   to ProviderSupport(movies = true, tvShows = true),
            SeriesFlixProvider    to ProviderSupport(movies = false, tvShows = true),
            FlixLatamProvider     to ProviderSupport(movies = true, tvShows = true),
            SoloLatinoProvider    to ProviderSupport(movies = true, tvShows = true),
            CableVisionHDProvider to ProviderSupport(movies = false, tvShows = true),
            CineCityProvider      to ProviderSupport(movies = false, tvShows = true),
            DoramasflixProvider   to ProviderSupport(movies = true, tvShows = true),
        )

        // Helper functions to check support
        fun supportsMovies(provider: Provider): Boolean {
            val support = providers[provider] ?: ProviderSupport(movies = true, tvShows = true)
            return support.movies
        }

        fun supportsTvShows(provider: Provider): Boolean {
            val support = providers[provider] ?: ProviderSupport(movies = true, tvShows = true)
            return support.tvShows
        }

        fun findByName(name: String): Provider? {
            return providers.keys.find { it.name == name }
        }
    }
}
