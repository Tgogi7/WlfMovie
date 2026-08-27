package com.mew.wlfmovie.fragments.movie

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.models.TvShow
import com.mew.wlfmovie.utils.EpisodeManager
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

class MovieViewModel(id: String, private val database: AppDatabase) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        database.movieDao().getByIdAsFlow(id),
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val movies = state.movie.recommendations
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
                is State.SuccessLoading -> {
                    val tvShows = state.movie.recommendations
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
    ) { state, movieDb, moviesDb, tvShowsDb ->
        when (state) {
            is State.SuccessLoading -> {
                val moviesById = moviesDb.associateBy { it.id }
                val tvShowsById = tvShowsDb.associateBy { it.id }
                State.SuccessLoading(
                    movie = state.movie.copy(
                        recommendations = state.movie.recommendations.map { show ->
                            when (show) {
                                is Movie -> moviesById[show.id]
                                    ?.takeIf { !show.isSame(it) }
                                    ?.let { show.copy().merge(it) }
                                    ?: show
                                is TvShow -> tvShowsById[show.id]
                                    ?.takeIf { !show.isSame(it) }
                                    ?.let { show.copy().merge(it) }
                                    ?: show
                            }
                        },
                    ).also { movie ->
                        movieDb?.let { movie.merge(it) }
                    }
                )
            }
            else -> state
        }
    }.flowOn(Dispatchers.IO)

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(val movie: Movie) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        EpisodeManager.clearEpisodes()
        getMovie(id)
    }


    fun getMovie(id: String) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)

        try {
            val movie = UserPreferences.currentProvider!!.getMovie(id)

            database.movieDao().getById(id)?.let { movieDb ->
                movie.merge(movieDb)
            }
            database.movieDao().insert(movie)

            _state.emit(State.SuccessLoading(movie))
        } catch (e: Exception) {
            Log.e("MovieViewModel", "getMovie: ", e)
            // WLFMOVIE V6.3.4: sin internet / TMDB caido → NO mostrar la
            // pantalla de error si tenemos algo local que mostrar:
            //  1. Datos de la pelicula cacheados en DB (sesiones online previas)
            //     → detalles desde cache (sin overview nueva, pero con todo lo
            //     guardado: titulo, poster, generos, etc.).
            //  2. Pelicula DESCARGADA → ademas sobreescribir poster/banner con
            //     las rutas LOCALES guardadas junto al video, para que la
            //     caratula se vea aunque no haya red.
            // Solo si no hay NADA (no cacheada y no descargada) caemos en
            // FailedLoading.
            try {
                val dbMovie = database.movieDao().getById(id)
                val downloaded = database.downloadDao().getById(id)
                    ?.takeIf { java.io.File(it.filePath).exists() }
                val fallbackMovie: Movie? = when {
                    dbMovie != null -> dbMovie
                    downloaded != null -> Movie(
                        id = downloaded.id,
                        title = downloaded.title,
                        poster = downloaded.poster,
                        banner = downloaded.banner,
                    )
                    else -> null
                }
                if (fallbackMovie != null) {
                    if (downloaded != null) {
                        if (!downloaded.poster.isNullOrBlank()) {
                            fallbackMovie.poster = downloaded.poster
                        }
                        if (!downloaded.banner.isNullOrBlank()) {
                            fallbackMovie.banner = downloaded.banner
                        }
                    }
                    Log.i(
                        "MovieViewModel",
                        "getMovie: TMDB fallo pero hay datos locales para '$id' " +
                            "(db=${dbMovie != null}, descargada=${downloaded != null}) — " +
                            "mostrando detalles offline"
                    )
                    _state.emit(State.SuccessLoading(fallbackMovie))
                    return@launch
                }
            } catch (fallbackError: Exception) {
                Log.w("MovieViewModel", "getMovie fallback local tambien fallo: ${fallbackError.message}")
            }
            _state.emit(State.FailedLoading(e))
        }
    }
}
