package com.mew.wlfmovie.fragments.tv_shows

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
import com.mew.wlfmovie.R
import com.mew.wlfmovie.adapters.AppAdapter
import com.mew.wlfmovie.databinding.FragmentTvShowsMobileBinding
import com.mew.wlfmovie.fragments.search.GenreChipAdapter
import com.mew.wlfmovie.models.Category
import com.mew.wlfmovie.models.Genre
import com.mew.wlfmovie.models.TvShow
import com.mew.wlfmovie.ui.SpacingItemDecoration
import com.mew.wlfmovie.utils.UserPreferences
import com.mew.wlfmovie.utils.dp
import com.mew.wlfmovie.utils.viewModelsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvShowsMobileFragment : Fragment() {

    private var _binding: FragmentTvShowsMobileBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModelsFactory { TvShowsViewModel() }
    private val appAdapter = AppAdapter()
    private var genreChipAdapter: GenreChipAdapter? = null
    private var selectedGenreId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTvShowsMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvTvShows.apply {
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
                    TvShowsViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is TvShowsViewModel.State.SuccessLoading -> {
                        displayCategories(state.categories)
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is TvShowsViewModel.State.FailedLoading -> {
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
                // WLFMOVIE V4: Obtener SOLO géneros de series.
                val language = provider.language
                val tmdbGenres = com.mew.wlfmovie.utils.TMDb3.Genres.tvList(language = language).genres
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
                Log.e("TvShowsFragment", "loadGenres: ${e.message}")
            }
        }
    }

    private fun displayCategories(categories: List<Category>) {
        appAdapter.submitList(categories.onEach { category ->
            category.itemSpacing = 10.dp(requireContext())
            category.itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
            category.list.onEach { item ->
                when (item) {
                    is TvShow -> item.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
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
