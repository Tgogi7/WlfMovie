package com.mew.wlfmovie.fragments.movies

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.utils.ParentalControlUtils
import com.mew.wlfmovie.utils.UserPreferences
import com.mew.wlfmovie.utils.ProviderChangeNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MoviesTvViewModel(database: AppDatabase) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)

    init {
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                getMovies()
            }
        }
    }

    val state: Flow<State> = _state

    private var page = 1

    sealed class State {
        data object Loading : State()
        data object LoadingMore : State()
        data class SuccessLoading(val movies: List<Movie>, val hasMore: Boolean) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getMovies()
    }

    fun getMovies() = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)
        try {
            val movies = ParentalControlUtils.filterItems(
                UserPreferences.currentProvider!!.getMovies()
            ).filterIsInstance<Movie>()
            page = 1
            _state.emit(State.SuccessLoading(movies, movies.isNotEmpty()))
        } catch (e: Exception) {
            Log.e("MoviesTvViewModel", "getMovies: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }

    fun loadMoreMovies() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessLoading) {
            _state.emit(State.LoadingMore)
            try {
                val movies = ParentalControlUtils.filterItems(
                    UserPreferences.currentProvider!!.getMovies(page + 1)
                ).filterIsInstance<Movie>()
                page += 1
                _state.emit(State.SuccessLoading(currentState.movies + movies, movies.isNotEmpty()))
            } catch (e: Exception) {
                Log.e("MoviesTvViewModel", "loadMoreMovies: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }
}
