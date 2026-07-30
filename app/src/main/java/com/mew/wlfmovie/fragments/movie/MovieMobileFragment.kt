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
import com.bumptech.glide.Glide
import com.mew.wlfmovie.R
import com.mew.wlfmovie.adapters.AppAdapter
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.databinding.FragmentMovieMobileBinding
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.ui.SpacingItemDecoration
import com.mew.wlfmovie.utils.CacheUtils
import com.mew.wlfmovie.utils.LoggingUtils
import com.mew.wlfmovie.utils.UserDataCache
import com.mew.wlfmovie.utils.UserPreferences
import com.mew.wlfmovie.utils.dp
import com.mew.wlfmovie.utils.viewModelsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MovieMobileFragment : Fragment() {

    private var _binding: FragmentMovieMobileBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<MovieMobileFragmentArgs>()
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
        _binding = FragmentMovieMobileBinding.inflate(inflater, container, false)
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
                                val doRetry = { viewModel.getMovie(args.id) }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    doRetry()
                                }
                                btnIsLoadingErrorDetails.setOnClickListener {
                                    LoggingUtils.showErrorDialog(requireContext(), state.error)
                                }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        appAdapter.onSaveInstanceState(binding.rvMovie)
        _binding = null
    }

    // =================================================================
    // WLFMOVIE: Overlay buttons (back, +, ♥)
    // =================================================================

    private fun setupOverlayButtons() {
        // Back: vuelve atrás
        binding.btnOverlayBack.setOnClickListener {
            findNavController().navigateUp()
        }

        // + (Ver después): por ahora solo muestra palomita 2s, lógica real en FASE 3
        binding.btnOverlayWatchLater.setOnClickListener {
            toggleWatchLater()
        }

        // ♥ (Favorito): toggle favorito
        binding.btnOverlayFavorite.setOnClickListener {
            toggleFavorite()
        }
    }

    private fun toggleFavorite() {
        val movie = currentMovie ?: return
        val provider = UserPreferences.currentProvider ?: return

        isFavorite = !isFavorite
        binding.btnOverlayFavorite.isSelected = isFavorite

        // WLFMOVIE: Usar lifecycleScope del fragment (no viewLifecycleOwner) + NonCancellable
        // para que la escritura a DB se complete incluso si el usuario presiona back rápido.
        lifecycleScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    // Obtener la movie de la DB (tiene todos los campos: watchHistory, etc.)
                    val dbMovie = database.movieDao().getById(movie.id)
                    if (dbMovie != null) {
                        // Movie ya está en DB, solo cambiar isFavorite
                        dbMovie.isFavorite = isFavorite
                        dbMovie.favoritedAtMillis = if (isFavorite) System.currentTimeMillis() else null
                        database.movieDao().insert(dbMovie)
                    } else {
                        // Movie no está en DB, insertar con isFavorite
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
                        // Revertir el estado visual si falló
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
        binding.rvMovie.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(20.dp(requireContext()))
            )
        }
    }

    private fun displayMovie(movie: Movie) {
        // WLFMOVIE: Solo cargar favorito la primera vez. Si ya tenemos currentMovie,
        // es porque el state se re-emitió (por ejemplo, después de un toggle) y no
        // queremos sobreescribir el estado visual del favorito.
        val isFirstLoad = currentMovie == null
        currentMovie = movie

        if (isFirstLoad) {
            // Cargar estado de favorito desde la DB solo la primera vez
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val dbMovie = database.movieDao().getById(movie.id)
                isFavorite = dbMovie?.isFavorite ?: false

                // WLFMOVIE: Cargar estado de watch later desde SharedPreferences
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

        // WLFMOVIE: El backdrop lo carga el ViewHolder (iv_movie_poster en content_movie_mobile.xml)
        appAdapter.submitList(listOfNotNull(
            movie.apply { itemType = AppAdapter.Type.MOVIE_MOBILE },

            movie.takeIf { it.directors.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.MOVIE_DIRECTORS_MOBILE },

            movie.takeIf { it.cast.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.MOVIE_CAST_MOBILE },

            movie.takeIf { it.recommendations.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.MOVIE_RECOMMENDATIONS_MOBILE },
        ))
    }
}
