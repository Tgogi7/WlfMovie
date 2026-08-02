package com.mew.wlfmovie.fragments.tv_show

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
import com.mew.wlfmovie.R
import com.mew.wlfmovie.adapters.AppAdapter
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.databinding.FragmentTvShowTvBinding
import com.mew.wlfmovie.models.TvShow
import com.mew.wlfmovie.utils.CacheUtils
import com.mew.wlfmovie.utils.LoggingUtils
import com.mew.wlfmovie.utils.UserDataCache
import com.mew.wlfmovie.utils.UserPreferences
import com.mew.wlfmovie.utils.viewModelsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvShowTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentTvShowTvBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<TvShowTvFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory {
        TvShowViewModel(
            id = args.id,
            database = database,
            fallbackPoster = args.poster,
            fallbackBanner = args.banner,
        )
    }

    private val appAdapter = AppAdapter()
    private var currentTvShow: TvShow? = null
    private var isFavorite = false
    private var isWatchLater = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTvShowTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeTvShow()
        setupOverlayButtons()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    TvShowViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is TvShowViewModel.State.SuccessLoading -> {
                        displayTvShow(state.tvShow)
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is TvShowViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.mew.wlfmovie.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.getTvShow(args.id)
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                            btnIsLoadingRetry.setOnClickListener { viewModel.getTvShow(args.id) }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                android.widget.Toast.makeText(requireContext(), getString(com.mew.wlfmovie.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                viewModel.getTvShow(args.id)
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
        appAdapter.onSaveInstanceState(binding.vgvTvShow)
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
        val tvShow = currentTvShow ?: return
        val provider = UserPreferences.currentProvider ?: return

        isFavorite = !isFavorite
        binding.btnOverlayFavorite.isSelected = isFavorite

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    val dbTvShow = database.tvShowDao().getById(tvShow.id)
                    if (dbTvShow != null) {
                        dbTvShow.isFavorite = isFavorite
                        dbTvShow.favoritedAtMillis = if (isFavorite) System.currentTimeMillis() else null
                        database.tvShowDao().insert(dbTvShow)
                    } else {
                        val newTvShow = tvShow.copy()
                        newTvShow.isFavorite = isFavorite
                        newTvShow.favoritedAtMillis = if (isFavorite) System.currentTimeMillis() else null
                        database.tvShowDao().insert(newTvShow)
                    }

                    if (isFavorite) {
                        UserDataCache.addTvShowToFavorites(requireContext(), provider, tvShow)
                    } else {
                        UserDataCache.removeTvShowFromFavorites(requireContext(), provider, tvShow.id)
                    }

                    withContext(Dispatchers.Main) {
                        val msg = if (isFavorite) "Añadido a favoritos" else "Quitado de favoritos"
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                        com.mew.wlfmovie.utils.SyncManager.autoUpload(requireContext())
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
        val tvShow = currentTvShow ?: return
        val prefs = requireContext().getSharedPreferences("wlfmovie_watch_later", android.content.Context.MODE_PRIVATE)
        val set = prefs.getStringSet("tv_shows", emptySet())!!.toMutableSet()

        isWatchLater = !isWatchLater
        if (isWatchLater) {
            set.add(tvShow.id)
            binding.btnOverlayWatchLater.setImageResource(R.drawable.wlf_ic_check)
            Toast.makeText(requireContext(), "Añadido a Ver después", Toast.LENGTH_SHORT).show()
            com.mew.wlfmovie.utils.SyncManager.autoUpload(requireContext())
        } else {
            set.remove(tvShow.id)
            binding.btnOverlayWatchLater.setImageResource(R.drawable.wlf_ic_watch_later)
            Toast.makeText(requireContext(), "Quitado de Ver después", Toast.LENGTH_SHORT).show()
            com.mew.wlfmovie.utils.SyncManager.autoUpload(requireContext())
        }
        prefs.edit().putStringSet("tv_shows", set).apply()
    }

    // =================================================================
    // WLFMOVIE: Inicialización y display
    // =================================================================

    private fun initializeTvShow() {
        binding.vgvTvShow.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(80)
        }
    }

    private fun displayTvShow(tvShow: TvShow) {
        val isFirstLoad = currentTvShow == null
        currentTvShow = tvShow

        if (isFirstLoad) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val dbTvShow = database.tvShowDao().getById(tvShow.id)
                isFavorite = dbTvShow?.isFavorite ?: false

                val prefs = requireContext().getSharedPreferences("wlfmovie_watch_later", android.content.Context.MODE_PRIVATE)
                isWatchLater = tvShow.id in (prefs.getStringSet("tv_shows", emptySet()) ?: emptySet())

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

        binding.ivTvShowBanner.visibility = View.GONE

        appAdapter.submitList(listOfNotNull(
            tvShow.apply { itemType = AppAdapter.Type.TV_SHOW_TV },

            // WLFMOVIE: Sección "Temporadas" eliminada del listado.
            // Ahora se accede con el botón "Ver temporadas" del content_tv_show_tv.xml.

            tvShow.takeIf { it.directors.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_DIRECTORS_TV },

            tvShow.takeIf { it.cast.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_CAST_TV },

            tvShow.takeIf { it.recommendations.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_RECOMMENDATIONS_TV },
        ))
    }
}
