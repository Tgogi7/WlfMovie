package com.mew.wlfmovie.fragments.tv_shows

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mew.wlfmovie.adapters.AppAdapter
import com.mew.wlfmovie.models.Category
import com.mew.wlfmovie.models.TvShow
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

class TvShowsViewModel : ViewModel() {

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

    // WLFMOVIE: Scroll infinito
    fun loadMore(sectionName: String) {
        if (sectionName in loadingSections) return
        if (sectionName.startsWith("Top 10")) return

        loadingSections.add(sectionName)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val language = UserPreferences.currentProvider?.language ?: "es"
                val currentPage = sectionPages[sectionName] ?: 3
                val nextPage = currentPage + 1

                val newItems: List<TMDb3.Tv> = when {
                    currentGenreId == null -> when (sectionName) {
                        "Tendencias" -> {
                            TMDb3.Trending.all(TMDb3.Params.TimeWindow.WEEK, page = nextPage, language = language)
                                .results.filterIsInstance<TMDb3.Tv>()
                        }
                        "Lo Más Nuevo" -> {
                            TMDb3.Discover.tv(language = language,
                                sortBy = TMDb3.Params.SortBy.Tv.FIRST_AIR_DATE_DESC,
                                page = nextPage).results
                        }
                        "Series Populares" -> {
                            TMDb3.TvSeriesLists.popular(page = nextPage, language = language).results
                        }
                        "Quizás Te Guste" -> return@launch
                        else -> return@launch
                    }
                    else -> when (sectionName) {
                        "Lo Más Nuevo" -> {
                            TMDb3.Discover.tv(language = language,
                                withGenres = TMDb3.Params.WithBuilder(currentGenreId!!),
                                sortBy = TMDb3.Params.SortBy.Tv.FIRST_AIR_DATE_DESC,
                                page = nextPage).results
                        }
                        "Populares" -> {
                            TMDb3.Discover.tv(language = language,
                                withGenres = TMDb3.Params.WithBuilder(currentGenreId!!),
                                sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC,
                                page = nextPage).results
                        }
                        "Grandes Éxitos" -> {
                            TMDb3.Discover.tv(language = language,
                                withGenres = TMDb3.Params.WithBuilder(currentGenreId!!),
                                sortBy = TMDb3.Params.SortBy.Tv.VOTE_COUNT_DESC,
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
                                list = cat.list + filtered.map { toTvShow(it) },
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
                Log.e("TvShowsViewModel", "loadMore: ${e.message}")
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
                Log.e("TvShowsViewModel", "loadCategories: ", e)
                _state.emit(State.FailedLoading(e))
            }
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

    private fun toTvShow(it: TMDb3.Tv) = TvShow(
        id = it.id.toString(), title = it.name, overview = it.overview,
        released = it.firstAirDate, rating = it.voteAverage.toDouble(),
        poster = it.posterPath?.w500, banner = it.backdropPath?.original,
    )

    private suspend fun loadDestacados(language: String): List<Category> = coroutineScope {
        val categories = mutableListOf<Category>()
        val now = Calendar.getInstance()
        val dateRange = TMDb3.Params.Range<Calendar>(null, now)

        val topRatedDeferred = async {
            val results = mutableListOf<TMDb3.Tv>()
            for (p in 1..3) {
                val page = TMDb3.TvSeriesLists.topRated(page = p, language = language)
                results.addAll(page.results)
            }
            filterReleased(results)
        }
        val popularDeferred = async {
            val results = mutableListOf<TMDb3.Tv>()
            for (p in 1..3) {
                val page = TMDb3.TvSeriesLists.popular(page = p, language = language)
                results.addAll(page.results)
            }
            filterReleased(results)
        }
        val newestDeferred = async {
            val results = mutableListOf<TMDb3.Tv>()
            for (p in 1..3) {
                val page = TMDb3.Discover.tv(
                    language = language,
                    sortBy = TMDb3.Params.SortBy.Tv.FIRST_AIR_DATE_DESC,
                )
                results.addAll(page.results)
            }
            filterReleased(results)
        }
        val trendingDeferred = async {
            val results = mutableListOf<TMDb3.Tv>()
            for (p in 1..3) {
                val page = TMDb3.Trending.all(TMDb3.Params.TimeWindow.WEEK, page = p, language = language)
                results.addAll(page.results.filterIsInstance<TMDb3.Tv>())
            }
            filterReleased(results)
        }

        categories.add(Category(name = "Top 10 Series", list = topRatedDeferred.await().take(10).map { toTvShow(it) }))
        sectionPages["Top 10 Series"] = 3

        categories.add(Category(name = "Tendencias", list = trendingDeferred.await().map { toTvShow(it) }))
        sectionPages["Tendencias"] = 3

        categories.add(Category(name = "Lo Más Nuevo", list = newestDeferred.await().map { toTvShow(it) }))
        sectionPages["Lo Más Nuevo"] = 3

        categories.add(Category(name = "Series Populares", list = popularDeferred.await().map { toTvShow(it) }))
        sectionPages["Series Populares"] = 3

        // Quizás te guste
        val dramaDeferred = async { TMDb3.Discover.tv(language = language, withGenres = TMDb3.Params.WithBuilder("18")) }
        val comedyDeferred = async { TMDb3.Discover.tv(language = language, withGenres = TMDb3.Params.WithBuilder("35")) }
        val scifiDeferred = async { TMDb3.Discover.tv(language = language, withGenres = TMDb3.Params.WithBuilder("10765")) }
        val mixed = mutableListOf<AppAdapter.Item>()
        val drama = filterReleased(dramaDeferred.await().results)
        val comedy = filterReleased(comedyDeferred.await().results)
        val scifi = filterReleased(scifiDeferred.await().results)
        val maxSize = maxOf(drama.size, comedy.size, scifi.size)
        for (i in 0 until maxSize) {
            if (i < drama.size) mixed.add(toTvShow(drama[i]))
            if (i < comedy.size) mixed.add(toTvShow(comedy[i]))
            if (i < scifi.size) mixed.add(toTvShow(scifi[i]))
        }
        categories.add(Category(name = "Quizás Te Guste", list = mixed.take(20)))
        sectionPages["Quizás Te Guste"] = 1

        categories.filter { it.list.isNotEmpty() }
    }

    private suspend fun loadByGenre(genreId: String, language: String): List<Category> = coroutineScope {
        val categories = mutableListOf<Category>()
        val now = Calendar.getInstance()
        val dateRange = TMDb3.Params.Range<Calendar>(null, now)

        val topDeferred = async {
            val results = mutableListOf<TMDb3.Tv>()
            for (p in 1..3) {
                val page = TMDb3.Discover.tv(language = language, withGenres = TMDb3.Params.WithBuilder(genreId),
                    sortBy = TMDb3.Params.SortBy.Tv.VOTE_AVERAGE_DESC, voteCount = TMDb3.Params.Range(200, null))
                results.addAll(page.results)
            }
            filterReleased(results)
        }
        val popularDeferred = async {
            val results = mutableListOf<TMDb3.Tv>()
            for (p in 1..3) {
                val page = TMDb3.Discover.tv(language = language, withGenres = TMDb3.Params.WithBuilder(genreId),
                    sortBy = TMDb3.Params.SortBy.Tv.POPULARITY_DESC)
                results.addAll(page.results)
            }
            filterReleased(results)
        }
        val newestDeferred = async {
            val results = mutableListOf<TMDb3.Tv>()
            for (p in 1..3) {
                val page = TMDb3.Discover.tv(language = language, withGenres = TMDb3.Params.WithBuilder(genreId),
                    sortBy = TMDb3.Params.SortBy.Tv.FIRST_AIR_DATE_DESC)
                results.addAll(page.results)
            }
            filterReleased(results)
        }
        val acclaimedDeferred = async {
            val results = mutableListOf<TMDb3.Tv>()
            for (p in 1..3) {
                val page = TMDb3.Discover.tv(language = language, withGenres = TMDb3.Params.WithBuilder(genreId),
                    sortBy = TMDb3.Params.SortBy.Tv.VOTE_COUNT_DESC)
                results.addAll(page.results)
            }
            filterReleased(results)
        }

        categories.add(Category(name = "Top 10", list = topDeferred.await().take(10).map { toTvShow(it) }))
        sectionPages["Top 10"] = 3

        categories.add(Category(name = "Lo Más Nuevo", list = newestDeferred.await().map { toTvShow(it) }))
        sectionPages["Lo Más Nuevo"] = 3

        categories.add(Category(name = "Populares", list = popularDeferred.await().map { toTvShow(it) }))
        sectionPages["Populares"] = 3

        categories.add(Category(name = "Grandes Éxitos", list = acclaimedDeferred.await().map { toTvShow(it) }))
        sectionPages["Grandes Éxitos"] = 3

        categories.filter { it.list.isNotEmpty() }
    }
}
