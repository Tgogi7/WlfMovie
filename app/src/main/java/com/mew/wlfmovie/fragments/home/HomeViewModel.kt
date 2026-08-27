package com.mew.wlfmovie.fragments.home

import android.util.Log
import com.mew.wlfmovie.StreamFlixApp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mew.wlfmovie.adapters.AppAdapter
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.models.Category
import com.mew.wlfmovie.models.DownloadedMovie
import com.mew.wlfmovie.models.Episode
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.models.TvShow
import com.mew.wlfmovie.models.WatchItem
import com.mew.wlfmovie.providers.AnimeOnlineNinjaProvider
import com.mew.wlfmovie.providers.Provider
import com.mew.wlfmovie.ui.UserDataNotifier
import com.mew.wlfmovie.utils.HomeCacheStore
import com.mew.wlfmovie.utils.ParentalControlUtils
import com.mew.wlfmovie.utils.ProviderChangeNotifier
import com.mew.wlfmovie.utils.UserDataCache
import com.mew.wlfmovie.utils.UserDataCache.toCached
import com.mew.wlfmovie.utils.UserDataCache.toEpisode
import com.mew.wlfmovie.utils.UserDataCache.toMovie
import com.mew.wlfmovie.utils.UserDataCache.toTvShow
import com.mew.wlfmovie.utils.UserPreferences
import com.mew.wlfmovie.utils.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * WLFMOVIE Update 7: Clave interna de la categoria "Downloads".
 * Se renombra a R.string.home_downloads en HomeMobileFragment.displayHome
 * antes de que se renderice.
 */
private const val DOWNLOADS_CATEGORY_KEY = "Downloads"

class HomeViewModel(database: AppDatabase) : ViewModel() {

    private fun <T> preserveCacheOrder(
        cached: List<T>,
        incoming: List<T>,
        idOf: (T) -> String,
    ): List<T> {
        val incomingById = incoming.associateBy(idOf)
        val orderedExisting = cached.mapNotNull { cachedItem -> incomingById[idOf(cachedItem)] }
        val appendedNew = incoming.filter { incomingItem ->
            cached.none { cachedItem -> idOf(cachedItem) == idOf(incomingItem) }
        }
        return orderedExisting + appendedNew
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    private val continueWatchingTvShowCache = ConcurrentHashMap<String, TvShow>()
    private val continueWatchingSeasonEpisodesCache = ConcurrentHashMap<String, List<Episode>>()
    private val _userDataCache = MutableStateFlow<UserDataCache.UserData?>(null)
    private var currentProvider: Provider? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        // WLFMOVIE Update 7: empaquetamos el estado y la lista de descargas locales
        // en un Pair para no tener que extender la aridad del combine externo.
        combine(
            _state,
            database.downloadDao().getAllAsFlow().flowOn(Dispatchers.IO),
        ) { s, dls -> s to dls },

        // CONTINUE WATCHING - Cache-first (faster on slow DB devices), falls back to DB
        combine(
            _userDataCache.transformLatest { cache ->
                if (cache != null && cache.continueWatchingMovies.isNotEmpty()) {
                    emit(cache.continueWatchingMovies.map { it.toMovie() })
                } else {
                    emitAll(database.movieDao().getWatchingMovies())
                }
            }.flowOn(Dispatchers.IO),
            _userDataCache.transformLatest { cache ->
                if (cache != null && cache.continueWatchingEpisodes.isNotEmpty()) {
                    emit(cache.continueWatchingEpisodes.map { it.toEpisode() })
                } else {
                    emitAll(database.episodeDao().getWatchingEpisodes())
                }
            }.flowOn(Dispatchers.IO),
            _userDataCache.transformLatest { cache ->
                if (cache != null && cache.continueWatchingEpisodes.isNotEmpty()) {
                    emit(cache.continueWatchingEpisodes.map { it.toEpisode() })
                } else {
                    emitAll(database.episodeDao().getNextEpisodesToWatch())
                }
            }.flowOn(Dispatchers.IO),
            database.tvShowDao().getAll().flowOn(Dispatchers.IO),
        ) { watchingMovies, watchingEpisodes, watchNextEpisodes, tvShows ->

            val allEpisodes = (watchingEpisodes + watchNextEpisodes)
                .distinctBy { it.id }

            val seasonIds = allEpisodes.mapNotNull { it.season?.id }.distinct()

            val tvShowsMap = tvShows.associateBy { it.id }

            val seasonsMap = if (seasonIds.isEmpty()) {
                emptyMap()
            } else {
                database.seasonDao()
                    .getByIds(seasonIds)
                    .associateBy { it.id }
            }

            val enrichedEpisodes = enrichContinueWatchingEpisodes(
                episodes = allEpisodes.map { episode ->
                    episode.copy(
                        tvShow = episode.tvShow?.id?.let { tvShowsMap[it] } ?: episode.tvShow,
                        season = episode.season?.id?.let { seasonsMap[it] } ?: episode.season,
                    ).apply {
                        merge(episode)
                    }
                }
            )

            val orderIndex = buildMap<String, Int> {
                _userDataCache.value?.continueWatchingMovies?.forEachIndexed { index, cached ->
                    put("movie:${cached.id}", index)
                }
                _userDataCache.value?.continueWatchingEpisodes?.forEachIndexed { index, cached ->
                    put("episode:${cached.id}", index)
                }
            }

            (watchingMovies + enrichedEpisodes)
                .sortedByDescending { item ->
                    when (item) {
                        is Movie -> item.watchHistory?.lastEngagementTimeUtcMillis
                            ?: item.watchedDate?.timeInMillis
                            ?: 0L
                        is Episode -> item.watchHistory?.lastEngagementTimeUtcMillis
                            ?: item.watchedDate?.timeInMillis
                            ?: 0L
                        else -> 0L
                    }
                } as List<AppAdapter.Item>
        }.flowOn(Dispatchers.IO),

        // FAVORITES - from cache first, DB as fallback
        _userDataCache.transformLatest { cache ->
            if (cache != null && cache.favoritesMovies.isNotEmpty()) {
                emit(cache.favoritesMovies.map { it.toMovie() })
            } else {
                emitAll(database.movieDao().getFavorites())
            }
        }.flowOn(Dispatchers.IO),
        _userDataCache.transformLatest { cache ->
            if (cache != null && cache.favoritesTvShows.isNotEmpty()) {
                emit(cache.favoritesTvShows.map { it.toTvShow() })
            } else {
                emitAll(database.tvShowDao().getFavorites())
            }
        }.flowOn(Dispatchers.IO),

        // MOVIES DB
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val movies = state.categories
                        .flatMap { it.list }
                        .filterIsInstance<Movie>()
                    if (movies.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.movieDao().getByIds(movies.map { it.id }))
                    }
                }
                else -> emit(emptyList<Movie>())
            }
        }.flowOn(Dispatchers.IO),

        // TV SHOWS DB
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val tvShows = state.categories
                        .flatMap { it.list }
                        .filterIsInstance<TvShow>()
                    if (tvShows.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.tvShowDao().getByIds(tvShows.map { it.id }))
                    }
                }
                else -> emit(emptyList<TvShow>())
            }
        }.flowOn(Dispatchers.IO),

        ) { stateAndDownloads, continueWatching, favoritesMovies, favoriteTvShows, moviesDb, tvShowsDb ->

        val state = stateAndDownloads.first
        val downloads = stateAndDownloads.second

        when (state) {
            is State.SuccessLoading -> {

                val moviesMap = moviesDb.associateBy { it.id }
                val tvShowsMap = tvShowsDb.associateBy { it.id }

                fun mergeItem(item: AppAdapter.Item): AppAdapter.Item {
                    return when (item) {
                        is Movie -> moviesMap[item.id]
                            ?.takeIf { !item.isSame(it) }
                            ?.let { item.copy().merge(it) }
                            ?: item

                        is TvShow -> tvShowsMap[item.id]
                            ?.takeIf { !item.isSame(it) }
                            ?.let { item.copy().merge(it) }
                            ?: item

                        else -> item
                    }
                }

                // WLFMOVIE Update 7: Construir lista completa y ordenar TODA junta
                val continueWatchingCategory = Category(
                    name = Category.CONTINUE_WATCHING,
                    list = continueWatching
                        .sortedByDescending {
                            when (it) {
                                is Episode -> it.watchHistory?.lastEngagementTimeUtcMillis
                                    ?: it.watchedDate?.timeInMillis
                                    ?: 0L

                                is Movie -> it.watchHistory?.lastEngagementTimeUtcMillis
                                    ?: it.watchedDate?.timeInMillis
                                    ?: 0L

                                else -> 0L
                            }
                        }
                        .distinctBy {
                            when (it) {
                                is Episode -> it.tvShow?.id
                                is Movie -> it.id
                                else -> null
                            }
                        },
                )

                // WLFMOVIE Update 7: Categoria "Downloads" (aislada del sync).
                // Items marcados con isFromDownloads = true para que el ViewHolder
                // distinga click (ir al player offline) de long-press (borrar).
                val downloadMovies: List<AppAdapter.Item> = downloads.map { d ->
                    Movie(
                        id = d.id,
                        title = d.title,
                        poster = d.poster,
                        banner = d.banner,
                    ).apply {
                        isFromDownloads = true
                        if (d.durationMillis > 0L) {
                            watchHistory = WatchItem.WatchHistory(
                                lastEngagementTimeUtcMillis = d.downloadedAtMillis,
                                lastPlaybackPositionMillis = d.watchPositionMillis,
                                durationMillis = d.durationMillis,
                            )
                        }
                        isWatched = d.isWatched
                    } as AppAdapter.Item
                }
                val downloadCategory = if (downloadMovies.isNotEmpty()) {
                    Category(name = DOWNLOADS_CATEGORY_KEY, list = downloadMovies)
                } else null

                val allCategories = listOfNotNull(continueWatchingCategory, downloadCategory) +
                    state.categories
                        .filter { category ->
                            category.name != Category.FEATURED &&
                            !category.name.contains("Netflix") &&
                            !category.name.contains("Amazon") &&
                            !category.name.contains("Disney") &&
                            !category.name.contains("Hulu") &&
                            !category.name.contains("Apple") &&
                            !category.name.contains("HBO")
                        }

                // WLFMOVIE Update 7: mergeItem NO se aplica a la categoria Downloads
                // (las Movies son sinteticas y NO deben mergearse con el estado de movies
                // table, porque eso sobreescribiria watchHistory/isFromDownloads).
                val mergedCategories = allCategories
                    .sortedWith { a, b ->
                        fun priority(name: String): Int = when {
                            name.contains("TV Shows", true) ||
                            name.contains("Series", true) && !name.contains("Top") -> 1
                            name.contains("Movies", true) ||
                            name.contains("Películas", true) && !name.contains("Top") -> 2
                            name == Category.CONTINUE_WATCHING -> 3
                            name == DOWNLOADS_CATEGORY_KEY -> 3
                            name.contains("Trending", true) ||
                            name.contains("Tendencias", true) -> 4
                            name.contains("Anime", true) -> 5
                            name.contains("Top 10", true) -> 6
                            name.contains("Acción", true) ||
                            name.contains("Comedia", true) ||
                            name.contains("Terror", true) -> 7
                            else -> 8
                        }
                        priority(a.name).compareTo(priority(b.name))
                    }
                    .map { category ->
                        if (category.name == DOWNLOADS_CATEGORY_KEY) {
                            category
                        } else {
                            category.copy(
                                list = category.list.map(::mergeItem)
                            )
                        }
                    }

                // WLFMOVIE Update 7: ParentalControl NO filtra la categoria Downloads
                // (son archivos del usuario local, no se filtran por control parental).
                val (downloadCats, nonDownloadCats) = mergedCategories.partition { it.name == DOWNLOADS_CATEGORY_KEY }
                val filteredNonDownloads = ParentalControlUtils.filterCategories(nonDownloadCats)
                val finalCategories = mergedCategories.map { category ->
                    if (category.name == DOWNLOADS_CATEGORY_KEY) category
                    else filteredNonDownloads.find { it.name == category.name } ?: category
                }

                State.SuccessLoading(finalCategories)
            }

            else -> state
        }
    }.flowOn(Dispatchers.IO)

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(val categories: List<Category>) : State()
        data class FailedLoading(val error: Exception) : State()

        /**
         * WLFMOVIE V6.3.1: El provider fallo con un error de red (sin
         * internet, DNS caido, etc.) PERO hay descargas locales. En lugar
         * de mostrar la pantalla de error, mostramos solo la categoria
         * Downloads para que el usuario pueda ver offline. Acompanado de
         * un toast "Sin conexion, mostrando solo descargas".
         *
         * WLFMOVIE V6.3.4: ya NO limitado a errores de red — si TMDB no
         * responde por cualquier motivo y no hay cache, tambien cae aqui
         * (el usuario pidio no ver la pantalla de error NUNCA en el home
         * cuando hay descargas para ver offline).
         */
        data class OfflineWithDownloads(val categories: List<Category>) : State()

        /**
         * WLFMOVIE V6.3.4: Sin internet / TMDB no responde y NO hay descargas.
         * La UI muestra un aviso simple ("Sin internet o el servidor no
         * responde") SIN botones de Reintentar / Vaciar cache / Detalles
         * del error — el usuario pidio exactamente eso: "solo dirá que el
         * servidor no responde o sin internet y nada más".
         */
        data object OfflineNoDownloads : State()
    }

    init {
        val initialProvider = UserPreferences.currentProvider
        if (initialProvider != null) {
            currentProvider = initialProvider
            loadUserDataCache(initialProvider)
        }
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                getHome()
            }
        }

        viewModelScope.launch {
            UserDataNotifier.updates.collect {
                val provider = UserPreferences.currentProvider ?: return@collect
                loadUserDataCache(provider)
            }
        }
        getHome()
    }

    private suspend fun enrichContinueWatchingEpisodes(episodes: List<Episode>): List<Episode> = coroutineScope {
        val provider = UserPreferences.currentProvider ?: return@coroutineScope episodes

        episodes.map { episode ->
            async {
                val tvShowId = episode.tvShow?.id ?: return@async episode
                val resolvedTvShow = continueWatchingTvShowCache[tvShowId] ?: runCatching {
                    provider.getTvShow(tvShowId)
                }.getOrNull()?.also { fetchedTvShow ->
                    continueWatchingTvShowCache[tvShowId] = fetchedTvShow
                }

                val mergedTvShow = resolvedTvShow?.copy().apply {
                    this?.let { show ->
                        episode.tvShow?.let { existingTvShow -> show.merge(existingTvShow) }
                    }
                } ?: episode.tvShow

                val resolvedSeason = episode.season?.let { season ->
                    mergedTvShow?.seasons?.firstOrNull { it.id == season.id || it.number == season.number }
                        ?: season
                }

                val resolvedEpisode = if (UserPreferences.enableTmdb) {
                    val seasonId = resolvedSeason?.id
                        ?: episode.season?.id
                    seasonId?.let { key ->
                        continueWatchingSeasonEpisodesCache[key] ?: runCatching {
                            provider.getEpisodesBySeason(key)
                        }.getOrDefault(emptyList()).also { fetchedEpisodes ->
                            if (fetchedEpisodes.isNotEmpty()) {
                                continueWatchingSeasonEpisodesCache[key] = fetchedEpisodes
                            }
                        }
                    }?.firstOrNull { seasonEpisode ->
                        seasonEpisode.id == episode.id || seasonEpisode.number == episode.number
                    }
                } else {
                    null
                }

                episode.copy(
                    title = resolvedEpisode?.title ?: episode.title,
                    overview = resolvedEpisode?.overview ?: episode.overview,
                    poster = resolvedEpisode?.poster ?: episode.poster,
                    tvShow = mergedTvShow,
                    season = resolvedSeason,
                ).apply {
                    merge(episode)
                }
            }
        }.awaitAll()
    }

    fun getHome() = viewModelScope.launch(Dispatchers.IO) {
        val provider = UserPreferences.currentProvider ?: run {
            _state.emit(State.FailedLoading(IllegalStateException("No provider selected")))
            return@launch
        }


        currentProvider = provider
        val appContext = StreamFlixApp.instance.applicationContext

        if (provider is AnimeOnlineNinjaProvider) {
            HomeCacheStore.clear(appContext, provider)
        }
        val cachedCategories = HomeCacheStore.read(appContext, provider)
        if (!cachedCategories.isNullOrEmpty()) {
            _state.emit(State.SuccessLoading(cachedCategories))
        } else {
            _state.emit(State.Loading)
        }

        loadUserDataCache(provider)

        // WLFMOVIE V6.3.4: reparar artwork offline de descargas antiguas
        // (rows con URLs remotas de TMDB en poster/banner). Fire-and-forget:
        // cuando termine, Room re-emite el Flow y el home se refresca solo
        // con las caratulas locales. Con flag interna para correr 1 vez por
        // sesion (o reintentar si fallo por falta de red).
        com.mew.wlfmovie.download.MovieDownloadManager
            .backfillDownloadedArtworkIfNeeded(appContext)

        try {
            val categories = provider.getHome()
            HomeCacheStore.write(appContext, provider, categories)
            _state.emit(State.SuccessLoading(categories))
        } catch (e: Exception) {
            Log.e("HomeViewModel", "getHome: ", e)
            // WLFMOVIE V6.3.4: sin cache + fallo del provider (sea sin internet,
            // DNS caido O TMDB caido/error) → NUNCA mas pantalla de error:
            //  - Si hay descargas locales → mostrar SOLO la categoria Downloads
            //    (el usuario reproduce offline).
            //  - Si no hay → aviso simple "sin internet o el servidor no
            //    responde", sin botones de Reintentar/Vaciar cache/Detalles.
            //
            // (V6.3.1 solo cubria errores de red; el usuario reporto que con
            // TMDB caido a veces igual aparecia "Ha ocurrido un error".)
            if (cachedCategories.isNullOrEmpty()) {
                val downloads = AppDatabase.getInstance(appContext)
                    .downloadDao().getAll()
                    .filter { java.io.File(it.filePath).exists() }
                if (downloads.isNotEmpty()) {
                    val downloadMovies: List<AppAdapter.Item> = downloads.map { d ->
                        Movie(
                            id = d.id,
                            title = d.title,
                            poster = d.poster,
                            banner = d.banner,
                        ).apply {
                            isFromDownloads = true
                            if (d.durationMillis > 0L) {
                                watchHistory = WatchItem.WatchHistory(
                                    lastEngagementTimeUtcMillis = d.downloadedAtMillis,
                                    lastPlaybackPositionMillis = d.watchPositionMillis,
                                    durationMillis = d.durationMillis,
                                )
                            }
                            isWatched = d.isWatched
                        } as AppAdapter.Item
                    }
                    val offlineCategory = Category(
                        name = DOWNLOADS_CATEGORY_KEY,
                        list = downloadMovies,
                    )
                    // Emitimos un estado especial para que HomeMobileFragment
                    // muestre la categoria Downloads + un toast "Sin conexion,
                    // mostrando solo descargas".
                    _state.emit(State.OfflineWithDownloads(listOf(offlineCategory)))
                    return@launch
                }
                _state.emit(State.OfflineNoDownloads)
                return@launch
            }
            // Si hay cache, el estado ya quedo en SuccessLoading con las
            // categorias cacheadas — no emitimos error (comportamiento
            // previo y correcto: la cache sirve de modo offline parcial).
        }
    }

    private fun loadUserDataCache(provider: Provider) {
        val appContext = StreamFlixApp.instance.applicationContext
        val cached = UserDataCache.read(appContext, provider)
        _userDataCache.value = cached

        viewModelScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(appContext)
            val moviesDeferred = async { db.movieDao().getFavorites().first() }
            val tvShowsDeferred = async { db.tvShowDao().getFavorites().first() }
            val watchingMoviesDeferred = async { db.movieDao().getWatchingMovies().first() }
            val watchingEpisodesDeferred = async { db.episodeDao().getWatchingEpisodes().first() }

            val movies = moviesDeferred.await()
            val tvShows = tvShowsDeferred.await()
            val watchingMovies = watchingMoviesDeferred.await()
            val watchingEpisodes = watchingEpisodesDeferred.await()

            val newData = UserDataCache.UserData(
                favoritesMovies = preserveCacheOrder(
                    cached = cached?.favoritesMovies ?: emptyList(),
                    incoming = movies.filter { it.isFavorite }.map { it.toCached() },
                    idOf = { it.id },
                ),
                favoritesTvShows = preserveCacheOrder(
                    cached = cached?.favoritesTvShows ?: emptyList(),
                    incoming = tvShows.filter { it.isFavorite }.map { it.toCached() },
                    idOf = { it.id },
                ),
                continueWatchingMovies = preserveCacheOrder(
                    cached = cached?.continueWatchingMovies ?: emptyList(),
                    incoming = (movies + watchingMovies)
                        .filter { it.watchHistory != null }
                        .map { it.toCached() },
                    idOf = { it.id },
                ),
                continueWatchingEpisodes = preserveCacheOrder(
                    cached = cached?.continueWatchingEpisodes ?: emptyList(),
                    incoming = watchingEpisodes
                        .filter { it.watchHistory != null }
                        .map { it.toCached() },
                    idOf = { it.id },
                ),
            )

            UserDataCache.write(appContext, provider, newData)

            if (_userDataCache.value != newData) {
                _userDataCache.value = newData
            }
        }
    }
}
