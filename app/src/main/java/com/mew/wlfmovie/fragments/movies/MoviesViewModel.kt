package com.mew.wlfmovie.fragments.movies

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mew.wlfmovie.adapters.AppAdapter
import com.mew.wlfmovie.models.Category
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.utils.TMDb3
import com.mew.wlfmovie.utils.TMDb3.original
import com.mew.wlfmovie.utils.TMDb3.w500
import com.mew.wlfmovie.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

class MoviesViewModel : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    private var currentGenreId: String? = null
    private val sectionPages = mutableMapOf<String, Int>()
    private val loadingSections = mutableSetOf<String>()

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(val categories: List<Category>) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        loadCategories(null)
    }

    // WLFMOVIE: Scroll infinito - cargar más items para una sección
    fun loadMore(sectionName: String) {
        if (sectionName in loadingSections) return
        if (sectionName.startsWith("Top 10")) return // Top 10 no es infinito

        loadingSections.add(sectionName)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val language = UserPreferences.currentProvider?.language ?: "es"
                val now = Calendar.getInstance()
                val dateRange = TMDb3.Params.Range<Calendar>(null, now)
                val currentPage = sectionPages[sectionName] ?: 3
                val nextPage = currentPage + 1

                val newItems: List<TMDb3.Movie> = when {
                    currentGenreId == null -> when (sectionName) {
                        "Tendencias" -> {
                            TMDb3.Trending.all(TMDb3.Params.TimeWindow.WEEK, page = nextPage, language = language)
                                .results.filterIsInstance<TMDb3.Movie>()
                        }
                        "Lo Más Nuevo" -> {
                            TMDb3.Discover.movie(language = language,
                                sortBy = TMDb3.Params.SortBy.Movie.PRIMARY_RELEASE_DATE_DESC,
                                releaseDate = dateRange, page = nextPage).results
                        }
                        "Películas Populares" -> {
                            TMDb3.MovieLists.popular(page = nextPage, language = language).results
                        }
                        "Quizás Te Guste" -> return@launch
                        else -> return@launch
                    }
                    else -> when (sectionName) {
                        "Lo Más Nuevo" -> {
                            TMDb3.Discover.movie(language = language,
                                withGenres = TMDb3.Params.WithBuilder(currentGenreId!!),
                                sortBy = TMDb3.Params.SortBy.Movie.PRIMARY_RELEASE_DATE_DESC,
                                releaseDate = dateRange, page = nextPage).results
                        }
                        "Populares" -> {
                            TMDb3.Discover.movie(language = language,
                                withGenres = TMDb3.Params.WithBuilder(currentGenreId!!),
                                sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                                page = nextPage).results
                        }
                        "Grandes Éxitos" -> {
                            TMDb3.Discover.movie(language = language,
                                withGenres = TMDb3.Params.WithBuilder(currentGenreId!!),
                                sortBy = TMDb3.Params.SortBy.Movie.VOTE_COUNT_DESC,
                                page = nextPage).results
                        }
                        else -> return@launch
                    }
                }

                val filtered = filterReleased(newItems)
                if (filtered.isNotEmpty()) {
                    sectionPages[sectionName] = nextPage
                    val currentState = _state.value as? State.SuccessLoading ?: return@launch
                    // WLFMOVIE: Crear NUEVOS objetos Category (no mutar in-place).
                    // Si reutilizamos el mismo Category, DiffUtil.areContentsTheSame
                    // devuelve true (porque Category.equals usa `this === other`),
                    // y la inner RecyclerView nunca se actualiza con los nuevos items.
                    val updatedCategories = currentState.categories.map { cat ->
                        if (cat.name == sectionName) {
                            Category(
                                name = cat.name,
                                list = cat.list + filtered.map { toMovie(it) },
                            ).apply {
                                onLoadMore = cat.onLoadMore
                                selectedIndex = cat.selectedIndex
                                itemSpacing = cat.itemSpacing
                                itemType = cat.itemType
                            }
                        } else cat
                    }
                    _state.emit(State.SuccessLoading(updatedCategories))
                }
            } catch (e: Exception) {
                Log.e("MoviesViewModel", "loadMore: ${e.message}")
            } finally {
                loadingSections.remove(sectionName)
            }
        }
    }

    fun loadCategories(genreId: String?) {
        currentGenreId = genreId
        sectionPages.clear()
        viewModelScope.launch(Dispatchers.IO) {
            _state.emit(State.Loading)
            try {
                val language = UserPreferences.currentProvider?.language ?: "es"
                val categories = if (genreId == null) {
                    loadDestacados(language)
                } else {
                    loadByGenre(genreId, language)
                }
                _state.emit(State.SuccessLoading(categories))
            } catch (e: Exception) {
                Log.e("MoviesViewModel", "loadCategories: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }

    // Filtro: solo películas que ya salieron (fecha de estreno <= hoy)
    private fun filterReleased(movies: List<TMDb3.Movie>): List<TMDb3.Movie> {
        val today = Calendar.getInstance()
        return movies.filter { movie ->
            movie.releaseDate?.let { dateStr ->
                try {
                    val parts = dateStr.split("-")
                    if (parts.size >= 1) {
                        val year = parts[0].toIntOrNull() ?: return@let false
                        val cal = Calendar.getInstance().apply {
                            clear()
                            if (parts.size >= 3) {
                                set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                            } else if (parts.size >= 2) {
                                set(parts[0].toInt(), parts[1].toInt() - 1, 1)
                            } else {
                                set(year, 0, 1)
                            }
                        }
                        !cal.after(today)
                    } else false
                } catch (e: Exception) { false }
            } ?: false
        }
    }

    private fun toMovie(it: TMDb3.Movie) = Movie(
        id = it.id.toString(), title = it.title, overview = it.overview,
        released = it.releaseDate, rating = it.voteAverage.toDouble(),
        poster = it.posterPath?.w500, banner = it.backdropPath?.original,
    )

    private suspend fun loadDestacados(language: String): List<Category> = coroutineScope {
        val categories = mutableListOf<Category>()
        val now = Calendar.getInstance()
        val dateRange = TMDb3.Params.Range<Calendar>(null, now)

        // Cargar páginas 1-3 de cada categoría en paralelo para tener más contenido
        val topRatedDeferred = async {
            val results = mutableListOf<TMDb3.Movie>()
            for (p in 1..3) {
                val page = TMDb3.MovieLists.topRated(page = p, language = language)
                results.addAll(page.results)
            }
            filterReleased(results)
        }
        val popularDeferred = async {
            val results = mutableListOf<TMDb3.Movie>()
            for (p in 1..3) {
                val page = TMDb3.MovieLists.popular(page = p, language = language)
                results.addAll(page.results)
            }
            filterReleased(results)
        }
        val newestDeferred = async {
            val results = mutableListOf<TMDb3.Movie>()
            for (p in 1..3) {
                val page = TMDb3.Discover.movie(
                    language = language,
                    sortBy = TMDb3.Params.SortBy.Movie.PRIMARY_RELEASE_DATE_DESC,
                    releaseDate = dateRange,
                )
                results.addAll(page.results)
            }
            filterReleased(results)
        }
        val trendingDeferred = async {
            val results = mutableListOf<TMDb3.Movie>()
            for (p in 1..3) {
                val page = TMDb3.Trending.all(TMDb3.Params.TimeWindow.WEEK, page = p, language = language)
                results.addAll(page.results.filterIsInstance<TMDb3.Movie>())
            }
            filterReleased(results)
        }

        categories.add(Category(name = "Top 10 Películas", list = topRatedDeferred.await().take(10).map { toMovie(it) }))
        sectionPages["Top 10 Películas"] = 3

        categories.add(Category(name = "Tendencias", list = trendingDeferred.await().map { toMovie(it) }))
        sectionPages["Tendencias"] = 3

        categories.add(Category(name = "Lo Más Nuevo", list = newestDeferred.await().map { toMovie(it) }))
        sectionPages["Lo Más Nuevo"] = 3

        categories.add(Category(name = "Películas Populares", list = popularDeferred.await().map { toMovie(it) }))
        sectionPages["Películas Populares"] = 3

        // Quizás te guste
        val actionDeferred = async { TMDb3.Discover.movie(language = language, withGenres = TMDb3.Params.WithBuilder("28"), releaseDate = dateRange) }
        val comedyDeferred = async { TMDb3.Discover.movie(language = language, withGenres = TMDb3.Params.WithBuilder("35"), releaseDate = dateRange) }
        val horrorDeferred = async { TMDb3.Discover.movie(language = language, withGenres = TMDb3.Params.WithBuilder("27"), releaseDate = dateRange) }
        val mixed = mutableListOf<AppAdapter.Item>()
        val action = filterReleased(actionDeferred.await().results)
        val comedy = filterReleased(comedyDeferred.await().results)
        val horror = filterReleased(horrorDeferred.await().results)
        val maxSize = maxOf(action.size, comedy.size, horror.size)
        for (i in 0 until maxSize) {
            if (i < action.size) mixed.add(toMovie(action[i]))
            if (i < comedy.size) mixed.add(toMovie(comedy[i]))
            if (i < horror.size) mixed.add(toMovie(horror[i]))
        }
        categories.add(Category(name = "Quizás Te Guste", list = mixed.take(20)))
        sectionPages["Quizás Te Guste"] = 1

        categories.filter { it.list.isNotEmpty() }
    }

    private suspend fun loadByGenre(genreId: String, language: String): List<Category> = coroutineScope {
        val categories = mutableListOf<Category>()
        val now = Calendar.getInstance()
        val dateRange = TMDb3.Params.Range<Calendar>(null, now)

        fun loadGenreSorted(sortBy: TMDb3.Params.SortBy.Movie, pages: Int = 3): List<TMDb3.Movie> {
            val results = mutableListOf<TMDb3.Movie>()
            // Ejecutar de forma síncrona (ya estamos en IO)
            for (p in 1..pages) {
                val page = kotlinx.coroutines.runBlocking {
                    TMDb3.Discover.movie(
                        language = language,
                        withGenres = TMDb3.Params.WithBuilder(genreId),
                        sortBy = sortBy,
                        releaseDate = dateRange,
                    )
                }
                results.addAll(page.results)
            }
            return filterReleased(results)
        }

        val topDeferred = async {
            val results = mutableListOf<TMDb3.Movie>()
            for (p in 1..3) {
                val page = TMDb3.Discover.movie(
                    language = language, withGenres = TMDb3.Params.WithBuilder(genreId),
                    sortBy = TMDb3.Params.SortBy.Movie.VOTE_AVERAGE_DESC,
                    voteCount = TMDb3.Params.Range(200, null), releaseDate = dateRange,
                )
                results.addAll(page.results)
            }
            filterReleased(results)
        }
        val popularDeferred = async {
            val results = mutableListOf<TMDb3.Movie>()
            for (p in 1..3) {
                val page = TMDb3.Discover.movie(
                    language = language, withGenres = TMDb3.Params.WithBuilder(genreId),
                    sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC, releaseDate = dateRange,
                )
                results.addAll(page.results)
            }
            filterReleased(results)
        }
        val newestDeferred = async {
            val results = mutableListOf<TMDb3.Movie>()
            for (p in 1..3) {
                val page = TMDb3.Discover.movie(
                    language = language, withGenres = TMDb3.Params.WithBuilder(genreId),
                    sortBy = TMDb3.Params.SortBy.Movie.PRIMARY_RELEASE_DATE_DESC, releaseDate = dateRange,
                )
                results.addAll(page.results)
            }
            filterReleased(results)
        }
        val acclaimedDeferred = async {
            val results = mutableListOf<TMDb3.Movie>()
            for (p in 1..3) {
                val page = TMDb3.Discover.movie(
                    language = language, withGenres = TMDb3.Params.WithBuilder(genreId),
                    sortBy = TMDb3.Params.SortBy.Movie.VOTE_COUNT_DESC, releaseDate = dateRange,
                )
                results.addAll(page.results)
            }
            filterReleased(results)
        }

        categories.add(Category(name = "Top 10", list = topDeferred.await().take(10).map { toMovie(it) }))
        sectionPages["Top 10"] = 3

        categories.add(Category(name = "Lo Más Nuevo", list = newestDeferred.await().map { toMovie(it) }))
        sectionPages["Lo Más Nuevo"] = 3

        categories.add(Category(name = "Populares", list = popularDeferred.await().map { toMovie(it) }))
        sectionPages["Populares"] = 3

        categories.add(Category(name = "Grandes Éxitos", list = acclaimedDeferred.await().map { toMovie(it) }))
        sectionPages["Grandes Éxitos"] = 3

        categories.filter { it.list.isNotEmpty() }
    }
}