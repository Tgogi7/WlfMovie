package com.mew.wlfmovie.fragments.movies

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.utils.TMDb3
import com.mew.wlfmovie.utils.TMDb3.original
import com.mew.wlfmovie.utils.TMDb3.w500
import com.mew.wlfmovie.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * WLFMOVIE: ViewModel TV para Películas (estado 9.2 — lista plana con TMDB).
 *
 * Reescrito para usar TMDB directo en vez de la API vieja de provider.getMovies().
 * Mantiene el formato de State del fragment original (lista plana + hasMore)
 * para no romper el MoviesTvFragment.
 *
 * El constructor sigue recibiendo `database: AppDatabase` para no romper el
 * `viewModelsFactory { MoviesTvViewModel(database) }` del fragment, pero no
 * lo usamos — todo va por TMDB.
 */
class MoviesTvViewModel(database: AppDatabase) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: Flow<State> = _state

    private var page = 1
    private var hasMore = true
    private var currentGenreId: String? = null // WLFMOVIE: null = sin género (populares)

    sealed class State {
        data object Loading : State()
        data object LoadingMore : State()
        data class SuccessLoading(val movies: List<Movie>, val hasMore: Boolean) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getMovies()
    }

    // WLFMOVIE: Cargar películas. Si genreId es null, carga populares.
    // Si genreId no es null, carga películas de ese género.
    fun getMovies(genreId: String? = null) = viewModelScope.launch(Dispatchers.IO) {
        currentGenreId = genreId
        _state.emit(State.Loading)
        try {
            page = 1
            hasMore = true
            val movies = loadPage(page)
            hasMore = movies.isNotEmpty()
            _state.emit(State.SuccessLoading(movies, hasMore))
        } catch (e: Exception) {
            Log.e("MoviesTvViewModel", "getMovies: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }

    fun loadMoreMovies() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState !is State.SuccessLoading) return@launch
        if (!hasMore) return@launch

        _state.emit(State.LoadingMore)
        try {
            val nextPage = page + 1
            val newMovies = loadPage(nextPage)
            if (newMovies.isNotEmpty()) {
                page = nextPage
                hasMore = newMovies.size >= 18
                _state.emit(State.SuccessLoading(currentState.movies + newMovies, hasMore))
            } else {
                hasMore = false
                _state.emit(State.SuccessLoading(currentState.movies, false))
            }
        } catch (e: Exception) {
            Log.e("MoviesTvViewModel", "loadMoreMovies: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }

    private suspend fun loadPage(pageNum: Int): List<Movie> {
        val language = UserPreferences.currentProvider?.language ?: "es"
        val now = Calendar.getInstance()
        val dateRange = TMDb3.Params.Range<Calendar>(null, now)

        // WLFMOVIE: Si hay género, filtrar por género. Si no, populares.
        val response = if (currentGenreId != null) {
            TMDb3.Discover.movie(
                language = language,
                withGenres = TMDb3.Params.WithBuilder(currentGenreId!!),
                sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                releaseDate = dateRange,
                page = pageNum,
            )
        } else {
            TMDb3.Discover.movie(
                language = language,
                sortBy = TMDb3.Params.SortBy.Movie.POPULARITY_DESC,
                releaseDate = dateRange,
                page = pageNum,
            )
        }

        return filterReleased(response.results).map { tmdbMovie ->
            Movie(
                id = tmdbMovie.id.toString(),
                title = tmdbMovie.title,
                overview = tmdbMovie.overview,
                released = tmdbMovie.releaseDate,
                rating = tmdbMovie.voteAverage.toDouble(),
                poster = tmdbMovie.posterPath?.w500,
                banner = tmdbMovie.backdropPath?.original,
            )
        }
    }

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
}
