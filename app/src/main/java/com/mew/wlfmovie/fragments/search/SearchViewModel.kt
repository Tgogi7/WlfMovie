package com.mew.wlfmovie.fragments.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mew.wlfmovie.adapters.AppAdapter
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.models.Genre
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.models.TvShow
import com.mew.wlfmovie.providers.IptvProvider
import com.mew.wlfmovie.providers.Provider
import com.mew.wlfmovie.utils.ParentalControlUtils
import com.mew.wlfmovie.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

// DEFINICIONES DE ESTADO Y RESULTADOS (Fuera de la clase para mejor acceso)
sealed class State {
    data object Searching : State()
    data object SearchingMore : State()
    data class SuccessSearching(val results: List<AppAdapter.Item>, val hasMore: Boolean) : State()
    data class FailedSearching(val error: Exception) : State()
    data object GlobalSearching : State()
    data class SuccessGlobalSearching(val providerResults: List<ProviderResult>) : State()
}

data class ProviderResult(
    val provider: Provider,
    val state: State,
) {
    sealed class State {
        data object Loading : State()
        data class Success(val results: List<AppAdapter.Item>) : State()
        data class Error(val error: Exception) : State()
    }
}


class SearchViewModel(database: AppDatabase) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Searching)
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessSearching -> {
                    val movies = state.results
                        .filterIsInstance<Movie>()
                    if (movies.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.movieDao().getByIds(movies.map { it.id }))
                    }
                }
                else -> emit(emptyList<Movie>())
            }
        },
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessSearching -> {
                    val tvShows = state.results
                        .filterIsInstance<TvShow>()
                    if (tvShows.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.tvShowDao().getByIds(tvShows.map { it.id }))
                    }
                }
                else -> emit(emptyList<TvShow>())
            }
        },
    ) { state, moviesDb, tvShowsDb ->
        when (state) {
            is State.SuccessSearching -> {
                val moviesById = moviesDb.associateBy { it.id }
                val tvShowsById = tvShowsDb.associateBy { it.id }

                State.SuccessSearching(
                    results = state.results.map { item ->
                        when (item) {
                            is Movie -> moviesById[item.id]
                                ?.takeIf { !item.isSame(it) }
                                ?.let { item.copy().merge(it) }
                                ?: item
                            is TvShow -> tvShowsById[item.id]
                                ?.takeIf { !item.isSame(it) }
                                ?.let { item.copy().merge(it) }
                                ?: item
                            else -> item
                        }
                    },
                    hasMore = state.hasMore
                )
            }
            else -> state
        }
    }.flowOn(Dispatchers.IO)

    var query = ""
    private var page = 1

    init {
        search(query)
    }

    fun search(query: String) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Searching)

        try {
            val provider = UserPreferences.currentProvider!!
            // WLFMOVIE: Si la query está vacía ("Todos"), traer géneros + contenido popular
            val results = if (query.isEmpty()) {
                // Primero obtener los géneros (para los chips)
                val genres = provider.search("", 1).filterIsInstance<Genre>()
                // Después mezclar películas y series populares
                val movies = provider.getMovies(1)
                val tvShows = provider.getTvShows(1)
                val mixed = mutableListOf<AppAdapter.Item>()
                mixed.addAll(genres)
                val maxSize = maxOf(movies.size, tvShows.size)
                for (i in 0 until maxSize) {
                    if (i < movies.size) mixed.add(movies[i])
                    if (i < tvShows.size) mixed.add(tvShows[i])
                }
                ParentalControlUtils.filterItems(mixed)
            } else {
                ParentalControlUtils.filterItems(provider.search(query))
            }
            this@SearchViewModel.query = query
            page = 1
            _state.emit(State.SuccessSearching(results, results.isNotEmpty()))
        } catch (e: Exception) {
            Log.e("SearchViewModel", "search: ", e)
            _state.emit(State.FailedSearching(e))
        }
    }

    fun loadMore() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessSearching) {
            _state.emit(State.SearchingMore)
            try {
                val results = ParentalControlUtils.filterItems(
                    UserPreferences.currentProvider!!.search(query, page + 1)
                )
                val existingKeys = currentState.results
                    .asSequence()
                    .map { it.searchIdentityKey() }
                    .toHashSet()
                val newUniqueResults = results.filterNot { it.searchIdentityKey() in existingKeys }
                page += 1
                _state.emit(
                    State.SuccessSearching(
                        results = currentState.results + newUniqueResults,
                        hasMore = newUniqueResults.isNotEmpty(),
                    )
                )
            } catch (e: Exception) {
                Log.e("SearchViewModel", "loadMore: ", e)
                _state.emit(State.FailedSearching(e))
            }
        }
    }

    // FUNCIÓN DE BÚSQUEDA GLOBAL AÑADIDA
    fun searchGlobal(query: String, currentLanguage: String) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.GlobalSearching)

        val isCurrentProviderIptv = UserPreferences.currentProvider is IptvProvider
        val targetProviders = Provider.providers.keys
            .filter { it.language == currentLanguage && (it is IptvProvider) == isCurrentProviderIptv }
            .toList()

        if (targetProviders.isEmpty()) {
            _state.emit(State.SuccessGlobalSearching(emptyList()))
            return@launch
        }

        val initialResults = targetProviders.map { provider ->
            ProviderResult(provider, ProviderResult.State.Loading)
        }
        _state.emit(State.SuccessGlobalSearching(initialResults))

        val mutableResults = initialResults.toMutableList()

        val stateComparator = compareBy<ProviderResult> { providerResult ->
            when (val state = providerResult.state) {
                is ProviderResult.State.Success -> if (state.results.isNotEmpty()) 1 else 3
                is ProviderResult.State.Loading -> 2
                is ProviderResult.State.Error -> 4
            }
        }

        targetProviders.forEachIndexed { index, provider ->
            launch {
                try {
                    val results = ParentalControlUtils.filterItems(provider.search(query).onEach { item ->
                        // ========= ¡AQUÍ ESTÁ LA MAGIA! =========
                        // Le ponemos el sello a cada resultado
                        when (item) {
                            is Movie -> item.providerName = provider.name
                            is TvShow -> item.providerName = provider.name
                        }
                        // =======================================
                    })
                    mutableResults[index] = ProviderResult(provider, ProviderResult.State.Success(results))
                } catch (e: Exception) {
                    Log.e("SearchViewModel", "searchGlobal for ${provider.name}: ", e)
                    mutableResults[index] = ProviderResult(provider, ProviderResult.State.Error(e))
                }

                _state.emit(State.SuccessGlobalSearching(mutableResults.sortedWith(stateComparator)))
            }
        }
    }

    // WLFMOVIE: Buscar por género (carga infinita)
    private var genrePage = 1
    private var currentGenreId: String? = null

    fun searchByGenre(genreId: String) = viewModelScope.launch(Dispatchers.IO) {
        currentGenreId = genreId
        genrePage = 1
        _state.emit(State.Searching)
        try {
            val provider = UserPreferences.currentProvider ?: return@launch
            val genre = provider.getGenre(genreId, genrePage)
            _state.emit(State.SuccessSearching(genre.shows, genre.shows.isNotEmpty()))
        } catch (e: Exception) {
            _state.emit(State.FailedSearching(e))
        }
    }

    fun loadMoreGenre() = viewModelScope.launch(Dispatchers.IO) {
        val genreId = currentGenreId ?: return@launch
        genrePage++
        try {
            val provider = UserPreferences.currentProvider ?: return@launch
            val genre = provider.getGenre(genreId, genrePage)
            val currentState = _state.value as? State.SuccessSearching ?: return@launch
            val newList = currentState.results + genre.shows
            _state.emit(State.SuccessSearching(newList, genre.shows.isNotEmpty()))
        } catch (e: Exception) {
            genrePage--
        }
    }
}

private fun AppAdapter.Item.searchIdentityKey(): String = when (this) {
    is Movie -> "movie:$id"
    is TvShow -> "tvshow:$id"
    else -> "${this::class.java.name}:${hashCode()}"
}
