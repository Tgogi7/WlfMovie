package com.mew.wlfmovie.fragments.tv_shows

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.models.TvShow
import com.mew.wlfmovie.utils.ParentalControlUtils
import com.mew.wlfmovie.utils.UserPreferences
import com.mew.wlfmovie.utils.ProviderChangeNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class TvShowsTvViewModel(database: AppDatabase) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)

    init {
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                getTvShows()
            }
        }
    }

    val state: Flow<State> = _state

    private var page = 1

    sealed class State {
        data object Loading : State()
        data object LoadingMore : State()
        data class SuccessLoading(val tvShows: List<TvShow>, val hasMore: Boolean) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getTvShows()
    }

    fun getTvShows() = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)
        try {
            val tvShows = ParentalControlUtils.filterItems(
                UserPreferences.currentProvider!!.getTvShows()
            ).filterIsInstance<TvShow>()
            page = 1
            _state.emit(State.SuccessLoading(tvShows, tvShows.isNotEmpty()))
        } catch (e: Exception) {
            Log.e("TvShowsTvViewModel", "getTvShows: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }

    fun loadMoreTvShows() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessLoading) {
            _state.emit(State.LoadingMore)
            try {
                val tvShows = ParentalControlUtils.filterItems(
                    UserPreferences.currentProvider!!.getTvShows(page + 1)
                ).filterIsInstance<TvShow>()
                page += 1
                _state.emit(State.SuccessLoading(currentState.tvShows + tvShows, tvShows.isNotEmpty()))
            } catch (e: Exception) {
                Log.e("TvShowsTvViewModel", "loadMoreTvShows: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }
}
