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
import com.mew.wlfmovie.databinding.FragmentTvShowMobileBinding
import com.mew.wlfmovie.models.TvShow
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

class TvShowMobileFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentTvShowMobileBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<TvShowMobileFragmentArgs>()
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
        _binding = FragmentTvShowMobileBinding.inflate(inflater, container, false)
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
                                val doRetry = { viewModel.getTvShow(args.id) }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    android.widget.Toast.makeText(requireContext(), getString(com.mew.wlfmovie.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
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
        appAdapter.onSaveInstanceState(binding.rvTvShow)
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

        // + (Ver después)
        binding.btnOverlayWatchLater.setOnClickListener {
            toggleWatchLater()
        }

        // ♥ (Favorito)
        binding.btnOverlayFavorite.setOnClickListener {
            toggleFavorite()
        }
    }

    // =================================================================
    // WLFMOVIE: Botón "Ver temporadas" - diálogo selector + lista de episodios
    // =================================================================

    private fun setupSeasonsButton(tvShow: TvShow) {
        // El botón "Ver temporadas" está dentro del content_tv_show_mobile.xml
        // que maneja el ViewHolder. Pero el ViewHolder no tiene acceso al NavController.
        // Por eso lo manejamos desde el Fragment buscando la view por ID en el RecyclerView.
        // Mejor: usamos el callback del ViewHolder.
        // Como el ViewHolder ya existe y maneja btn_tv_show_watch_now,
        // vamos a inyectar el listener del botón seasons desde aquí.
    }

    private fun toggleFavorite() {
        val tvShow = currentTvShow ?: return
        val provider = UserPreferences.currentProvider ?: return

        isFavorite = !isFavorite
        binding.btnOverlayFavorite.isSelected = isFavorite

        // WLFMOVIE: Usar lifecycleScope del fragment + NonCancellable
        lifecycleScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
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
        } else {
            set.remove(tvShow.id)
            binding.btnOverlayWatchLater.setImageResource(R.drawable.wlf_ic_watch_later)
            Toast.makeText(requireContext(), "Quitado de Ver después", Toast.LENGTH_SHORT).show()
        }
        // WLFMOVIE V5.1: Guardar en prefs ANTES de subir a la nube (evita race condition)
        prefs.edit().putStringSet("tv_shows", set).apply()
        com.mew.wlfmovie.utils.SyncManager.autoUpload(requireContext())
    }

    // =================================================================
    // WLFMOVIE: Inicialización y display
    // =================================================================

    private fun initializeTvShow() {
        binding.rvTvShow.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(20.dp(requireContext()))
            )
        }
    }

    private fun displayTvShow(tvShow: TvShow) {
        // WLFMOVIE: Solo cargar favorito la primera vez (evita sobreescribir el toggle)
        val isFirstLoad = currentTvShow == null
        currentTvShow = tvShow

        if (isFirstLoad) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val dbTvShow = database.tvShowDao().getById(tvShow.id)
                isFavorite = dbTvShow?.isFavorite ?: false

                // WLFMOVIE: Cargar estado de watch later desde SharedPreferences
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

        // WLFMOVIE: El backdrop lo carga el ViewHolder (iv_tv_show_poster)
        // WLFMOVIE: Sección "Temporadas" eliminada del listado (se maneja con el botón)
        appAdapter.submitList(listOfNotNull(
            tvShow.apply { itemType = AppAdapter.Type.TV_SHOW_MOBILE },

            // SECCIÓN TEMPORADAS ELIMINADA - se accede con el botón "Ver temporadas"

            tvShow.takeIf { it.directors.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_DIRECTORS_MOBILE },

            tvShow.takeIf { it.cast.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_CAST_MOBILE },

            tvShow.takeIf { it.recommendations.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_RECOMMENDATIONS_MOBILE },
        ))
    }
}
