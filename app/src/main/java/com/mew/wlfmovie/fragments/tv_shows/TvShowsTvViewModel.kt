package com.mew.wlfmovie.fragments.tv_shows

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.models.TvShow
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
 * WLFMOVIE: ViewModel TV para Series (estado 9.2 — lista plana con TMDB).
 *
 * Reescrito para usar TMDB directo en vez de la API vieja de provider.getTvShows().
 * Mantiene el formato de State del fragment original (lista plana + hasMore)
 * para no romper el TvShowsTvFragment.
 *
 * El constructor sigue recibiendo `database: AppDatabase` para no romper el
 * `viewModelsFactory { TvShowsTvViewModel(database) }` del fragment, pero no
 * lo usamos — todo va por TMDB.
 */
class TvShowsTvViewModel(database: AppDatabase) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: Flow<State> = _state

    private var page = 1
    private var hasMore = true
    private var currentGenreId: String? = null // WLFMOVIE: null = sin género (populares)

    sealed class State {
        data object Loading : State()
        data object LoadingMore : State()
        data class SuccessLoading(val tvShows: List<TvShow>, val hasMore: Boolean) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getTvShows()
    }

    // WLFMOVIE: Cargar series. Si genreId es null, carga populares.
    // Si genreId no es null, carga series de ese género.
    fun getTvShows(genreId: String? = null) = viewModelScope.launch(Dispatchers.IO) {
        currentGenreId = genreId
        _state.emit(State.Loading)
        try {
            page = 1
            hasMore = true
            val tvShows = loadPage(page)
            hasMore = tvShows.isNotEmpty()
            _state.emit(State.SuccessLoading(tvShows, hasMore))
        } catch (e: Exception) {
            Log.e("TvShowsTvViewModel", "getTvShows: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }

    fun loadMoreTvShows() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState !is State.SuccessLoading) return@launch
        if (!hasMore) return@launch

        _state.emit(State.LoadingMore)
        try {
            val nextPage = page + 1
            val newTvShows = loadPage(nextPage)
            if (newTvShows.isNotEmpty()) {
                page = nextPage
                hasMore = newTvShows.size >= 18
                _state.emit(State.SuccessLoading(currentState.tvShows + newTvShows, hasMore))
            } else {
                hasMore = false
                _state.emit(State.SuccessLoading(currentState.tvShows, false))
            }
        } catch (e: Exception) {
            Log.e("TvShowsTvViewModel", "loadMoreTvShows: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }

    private suspend fun loadPage(pageNum: Int): List<TvShow> {
        val language = UserPreferences.currentProvider?.language ?: "es"
        val now = Calendar.getInstance()
        val dateRange = TMDb3.Params.Range<Calendar>(null, now)

        // WLFMOVIE: Si hay género, filtrar por género. Si no, populares.
        val response = if (currentGenreId != null) {
            TMDb3.Discover.tv(
                language = language,
                withGenres = TMDb3.Params.WithBuilder(currentGenreId!!),
                sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
                airDate = dateRange,
                page = pageNum,
            )
        } else {
            TMDb3.Discover.tv(
                language = language,
                sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
                airDate = dateRange,
                page = pageNum,
            )
        }

        return filterReleased(response.results).map { tmdbTv ->
            TvShow(
                id = tmdbTv.id.toString(),
                title = tmdbTv.name,
                overview = tmdbTv.overview,
                released = tmdbTv.firstAirDate,
                rating = tmdbTv.voteAverage.toDouble(),
                poster = tmdbTv.posterPath?.w500,
                banner = tmdbTv.backdropPath?.original,
            )
        }
    }

    private fun filterReleased(tvShows: List<TMDb3.Tv>): List<TMDb3.Tv> {
        val today = Calendar.getInstance()
        return tvShows.filter { tv ->
            tv.firstAirDate?.let { dateStr ->
                try {
                    val parts = dateStr.split("-")
                    if (parts.size >= 1) {
                        val cal = Calendar.getInstance().apply {
                            clear()
                            if (parts.size >= 3) {
                                set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                            } else if (parts.size >= 2) {
                                set(parts[0].toInt(), parts[1].toInt() - 1, 1)
                            } else {
                                set(parts[0].toInt(), 0, 1)
                            }
                        }
                        !cal.after(today)
                    } else false
                } catch (e: Exception) { false }
            } ?: false
        }
    }
}
