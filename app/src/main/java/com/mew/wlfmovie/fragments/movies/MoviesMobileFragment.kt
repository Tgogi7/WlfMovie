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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mew.wlfmovie.R
import com.mew.wlfmovie.adapters.AppAdapter
import com.mew.wlfmovie.databinding.FragmentMoviesMobileBinding
import com.mew.wlfmovie.fragments.search.GenreChipAdapter
import com.mew.wlfmovie.models.Category
import com.mew.wlfmovie.models.Genre
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.ui.SpacingItemDecoration
import com.mew.wlfmovie.utils.UserPreferences
import com.mew.wlfmovie.utils.dp
import com.mew.wlfmovie.utils.viewModelsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MoviesMobileFragment : Fragment() {

    private var _binding: FragmentMoviesMobileBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModelsFactory { MoviesViewModel() }
    private val appAdapter = AppAdapter()
    private var genreChipAdapter: GenreChipAdapter? = null
    private var selectedGenreId: String? = null // null = "Destacados"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMoviesMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvMovies.apply {
            adapter = appAdapter
            addItemDecoration(SpacingItemDecoration(20.dp(requireContext())))
            // WLFMOVIE: Evitar que el RV interno (carrusel de cada sección)
            // reinicie su scroll cuando loadMore añade items.
            // Sin esto, notifyItemChanged en el padre provoca que el
            // DefaultItemAnimator cree un ViewHolder nuevo con un RV interno
            // nuevo en posición 0 — el scroll del carrusel "vuelve atrás".
            (itemAnimator as? androidx.recyclerview.widget.DefaultItemAnimator)?.supportsChangeAnimations = false
        }

        loadGenres()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    MoviesViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is MoviesViewModel.State.SuccessLoading -> {
                        displayCategories(state.categories)
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is MoviesViewModel.State.FailedLoading -> {
                        Toast.makeText(requireContext(), state.error.message ?: "", Toast.LENGTH_SHORT).show()
                        binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                            btnIsLoadingRetry.setOnClickListener { viewModel.loadCategories(selectedGenreId) }
                        }
                    }
                }
            }
        }
    }

    private fun loadGenres() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val provider = UserPreferences.currentProvider ?: return@launch
                // WLFMOVIE V4: Obtener SOLO géneros de películas.
                val language = provider.language
                val tmdbGenres = com.mew.wlfmovie.utils.TMDb3.Genres.movieList(language = language).genres
                val genres = tmdbGenres.map {
                    Genre(id = it.id.toString(), name = it.name)
                }
                val allGenre = Genre(id = "destacados", name = "Destacados")
                val fullGenres = listOf(allGenre) + genres

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.rvGenres.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                    genreChipAdapter = GenreChipAdapter(fullGenres) { genre ->
                        selectedGenreId = if (genre.id == "destacados") null else genre.id
                        genreChipAdapter?.setSelected(genre.id)
                        viewModel.loadCategories(selectedGenreId)
                    }
                    binding.rvGenres.adapter = genreChipAdapter
                    genreChipAdapter?.setSelected("destacados")
                }
            } catch (e: Exception) {
                Log.e("MoviesFragment", "loadGenres: ${e.message}")
            }
        }
    }

    private fun displayCategories(categories: List<Category>) {
        appAdapter.submitList(categories.onEach { category ->
            category.itemSpacing = 10.dp(requireContext())
            category.itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
            category.list.onEach { item ->
                when (item) {
                    is Movie -> item.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                }
            }
            // WLFMOVIE: Scroll infinito
            category.onLoadMore = { viewModel.loadMore(category.name) }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
