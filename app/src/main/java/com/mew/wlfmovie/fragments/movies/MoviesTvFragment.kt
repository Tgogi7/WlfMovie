package com.mew.wlfmovie.fragments.movies

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.mew.wlfmovie.R
import com.mew.wlfmovie.adapters.AppAdapter
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.databinding.FragmentMoviesTvBinding
import com.mew.wlfmovie.models.Genre
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.utils.CacheUtils
import com.mew.wlfmovie.utils.UserPreferences
import com.mew.wlfmovie.utils.viewModelsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MoviesTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentMoviesTvBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { MoviesTvViewModel(database) }

    private val appAdapter = AppAdapter()
    private var genreChipAdapter: GenreChipTvAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMoviesTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeMovies()
        loadGenres()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    MoviesTvViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    MoviesTvViewModel.State.LoadingMore -> appAdapter.isLoading = true
                    is MoviesTvViewModel.State.SuccessLoading -> {
                        displayMovies(state.movies, state.hasMore)
                        appAdapter.isLoading = false
                        binding.vgvMovies.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is MoviesTvViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.mew.wlfmovie.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            if (appAdapter.isLoading) appAdapter.isLoading = false
                            viewModel.getMovies()
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                btnIsLoadingRetry.setOnClickListener { viewModel.getMovies() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    android.widget.Toast.makeText(requireContext(), getString(com.mew.wlfmovie.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                    viewModel.getMovies()
                                }
                                binding.vgvMovies.visibility = View.GONE
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // WLFMOVIE: Resetear el genreChipAdapter para que se recreé al volver
        // al fragment (después de entrar a una carátula y devolverse).
        // Si no lo reseteamos, el adapter viejo queda attachado al RecyclerView
        // viejo (ya destruido) y las pastillas no aparecen al volver.
        genreChipAdapter = null
        _binding = null
    }

    private fun initializeMovies() {
        binding.vgvMovies.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(requireContext().resources.getDimension(R.dimen.movies_spacing).toInt())
        }

        binding.root.requestFocus()
    }

    // WLFMOVIE: Paso 2 — cargar géneros en el RecyclerView de chips.
    // Al click, filtra el grid por género.
    private fun loadGenres() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val provider = UserPreferences.currentProvider ?: return@launch
                // WLFMOVIE V4: Obtener SOLO géneros de películas (no de series).
                // Antes usabamos provider.search("", 1) que traía géneros de
                // movies Y tv mezclados, lo que causaba que algunos chips
                // (como "Action & Adventure" que es de TV) no devolvieran
                // resultados al filtrar películas.
                val language = provider.language
                val tmdbGenres = com.mew.wlfmovie.utils.TMDb3.Genres.movieList(language = language).genres
                val genres = tmdbGenres.map {
                    Genre(id = it.id.toString(), name = it.name)
                }

                val allGenre = Genre(id = "destacados", name = "Destacados")
                val fullGenres = listOf(allGenre) + genres

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    if (genreChipAdapter == null) {
                        genreChipAdapter = GenreChipTvAdapter(fullGenres) { genre ->
                            // WLFMOVIE: Filtrar por género.
                            // "Destacados" = sin género (populares).
                            val genreId = if (genre.id == "destacados") null else genre.id
                            genreChipAdapter?.setSelected(genre.id)
                            viewModel.getMovies(genreId)
                            Log.d("WlfMovie-MoviesTv", "Chip click: ${genre.name} → genreId=$genreId")
                        }
                        binding.hgvGenres.apply {
                            adapter = genreChipAdapter
                        }
                        genreChipAdapter?.setSelected("destacados")
                    }
                }
            } catch (e: Exception) {
                Log.e("WlfMovie-MoviesTv", "loadGenres: ${e.message}")
            }
        }
    }

    private fun displayMovies(movies: List<Movie>, hasMore: Boolean) {
        appAdapter.submitList(movies.onEach {
            it.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
        })

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMoreMovies() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }
}
