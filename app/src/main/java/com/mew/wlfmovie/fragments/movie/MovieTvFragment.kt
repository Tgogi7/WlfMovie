package com.mew.wlfmovie.fragments.movie

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.mew.wlfmovie.R
import com.mew.wlfmovie.adapters.AppAdapter
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.databinding.FragmentMovieTvBinding
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.utils.LoggingUtils
import com.mew.wlfmovie.utils.UserDataCache
import com.mew.wlfmovie.utils.UserPreferences
import com.mew.wlfmovie.utils.loadMovieBanner
import com.mew.wlfmovie.utils.viewModelsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MovieTvFragment : Fragment() {

    private var _binding: FragmentMovieTvBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<MovieTvFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { MovieViewModel(args.id, database) }

    private val appAdapter = AppAdapter()
    private var currentMovie: Movie? = null
    private var isFavorite = false
    private var isWatchLater = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMovieTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeMovie()
        setupOverlayButtons()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    MovieViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is MovieViewModel.State.SuccessLoading -> {
                        displayMovie(state.movie)
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is MovieViewModel.State.FailedLoading -> {
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                            btnIsLoadingRetry.setOnClickListener {
                                viewModel.getMovie(args.id)
                            }
                            btnIsLoadingErrorDetails.setOnClickListener {
                                LoggingUtils.showErrorDialog(requireContext(), state.error)
                            }
                            btnIsLoadingRetry.requestFocus()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        appAdapter.onSaveInstanceState(binding.vgvMovie)
        _binding = null
    }

    // =================================================================
    // WLFMOVIE: Overlay buttons (back, +, ♥)
    // =================================================================

    private fun setupOverlayButtons() {
        binding.btnOverlayBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnOverlayWatchLater.setOnClickListener {
            toggleWatchLater()
        }

        binding.btnOverlayFavorite.setOnClickListener {
            toggleFavorite()
        }
    }

    private fun toggleFavorite() {
        val movie = currentMovie ?: return
        val provider = UserPreferences.currentProvider ?: return

        isFavorite = !isFavorite
        binding.btnOverlayFavorite.isSelected = isFavorite

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    val dbMovie = database.movieDao().getById(movie.id)
                    if (dbMovie != null) {
                        dbMovie.isFavorite = isFavorite
                        dbMovie.favoritedAtMillis = if (isFavorite) System.currentTimeMillis() else null
                        database.movieDao().insert(dbMovie)
                    } else {
                        val newMovie = movie.copy()
                        newMovie.isFavorite = isFavorite
                        newMovie.favoritedAtMillis = if (isFavorite) System.currentTimeMillis() else null
                        database.movieDao().insert(newMovie)
                    }

                    if (isFavorite) {
                        UserDataCache.addMovieToFavorites(requireContext(), provider, movie)
                    } else {
                        UserDataCache.removeMovieFromFavorites(requireContext(), provider, movie.id)
                    }

                    withContext(Dispatchers.Main) {
                        val msg = if (isFavorite) "Añadido a favoritos" else "Quitado de favoritos"
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isFavorite = !isFavorite
                        if (_binding != null) {
                            binding.btnOverlayFavorite.isSelected = isFavorite
                        }
                        Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun toggleWatchLater() {
        val movie = currentMovie ?: return
        val prefs = requireContext().getSharedPreferences("wlfmovie_watch_later", android.content.Context.MODE_PRIVATE)
        val set = prefs.getStringSet("movies", emptySet())!!.toMutableSet()

        isWatchLater = !isWatchLater
        if (isWatchLater) {
            set.add(movie.id)
            binding.btnOverlayWatchLater.setImageResource(R.drawable.wlf_ic_check)
            Toast.makeText(requireContext(), "Añadido a Ver después", Toast.LENGTH_SHORT).show()
        } else {
            set.remove(movie.id)
            binding.btnOverlayWatchLater.setImageResource(R.drawable.wlf_ic_watch_later)
            Toast.makeText(requireContext(), "Quitado de Ver después", Toast.LENGTH_SHORT).show()
        }
        prefs.edit().putStringSet("movies", set).apply()
    }

    // =================================================================
    // WLFMOVIE: Inicialización y display
    // =================================================================

    private fun initializeMovie() {
        binding.vgvMovie.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(80)
        }
    }

    private fun displayMovie(movie: Movie) {
        val isFirstLoad = currentMovie == null
        currentMovie = movie

        if (isFirstLoad) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val dbMovie = database.movieDao().getById(movie.id)
                isFavorite = dbMovie?.isFavorite ?: false

                val prefs = requireContext().getSharedPreferences("wlfmovie_watch_later", android.content.Context.MODE_PRIVATE)
                isWatchLater = movie.id in (prefs.getStringSet("movies", emptySet()) ?: emptySet())

                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        binding.btnOverlayFavorite.isSelected = isFavorite
                        binding.btnOverlayWatchLater.setImageResource(
                            if (isWatchLater) R.drawable.wlf_ic_check else R.drawable.wlf_ic_watch_later
                        )
                    }
                }
            }
        }

        binding.ivMovieBanner.loadMovieBanner(movie) {
            transition(DrawableTransitionOptions.withCrossFade())
        }

        appAdapter.submitList(listOfNotNull(
            movie.apply { itemType = AppAdapter.Type.MOVIE_TV },

            movie.takeIf { it.directors.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.MOVIE_DIRECTORS_TV },

            movie.takeIf { it.cast.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.MOVIE_CAST_TV },

            movie.takeIf { it.recommendations.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.MOVIE_RECOMMENDATIONS_TV },
        ))
    }
}
