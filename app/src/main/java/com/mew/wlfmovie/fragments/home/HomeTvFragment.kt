package com.mew.wlfmovie.fragments.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.mew.wlfmovie.R
import com.mew.wlfmovie.adapters.AppAdapter
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.databinding.FragmentHomeTvBinding
import com.mew.wlfmovie.models.Category
import com.mew.wlfmovie.models.Episode
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.models.TvShow
import com.mew.wlfmovie.utils.CacheUtils
import com.mew.wlfmovie.utils.LoggingUtils
import com.mew.wlfmovie.utils.ProviderChangeNotifier
import com.mew.wlfmovie.utils.UserPreferences
import kotlinx.coroutines.launch

class HomeTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentHomeTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by lazy {
        val providerKey = UserPreferences.currentProvider?.name ?: "default"
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(AppDatabase.getInstance(requireContext())) as T
            }
        }
        ViewModelProvider(this, factory).get(providerKey, HomeViewModel::class.java)
    }

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeHome()

        // Lightweight refresh when provider changes
        viewLifecycleOwner.lifecycleScope.launch {
            ProviderChangeNotifier.providerChangeFlow.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect {
                viewModel.getHome()
            }
        }

        // Initial load
        viewModel.getHome()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    HomeViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is HomeViewModel.State.SuccessLoading -> {
                        displayHome(state.categories)
                        binding.vgvHome.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is HomeViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(
                                requireContext(),
                                getString(com.mew.wlfmovie.R.string.clear_cache_done_409),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            viewModel.getHome()
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
                            btnIsLoadingRetry.setOnClickListener { viewModel.getHome() }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                android.widget.Toast.makeText(
                                    requireContext(),
                                    getString(com.mew.wlfmovie.R.string.clear_cache_done),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                viewModel.getHome()
                            }
                            btnIsLoadingErrorDetails.setOnClickListener {
                                LoggingUtils.showErrorDialog(requireContext(), state.error)
                            }
                            binding.vgvHome.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        appAdapter.onSaveInstanceState(binding.vgvHome)
        _binding = null
    }

    // WLFMOVIE: Metodos vacios para compatibilidad con CategoryViewHolder.
    // CategoryViewHolder.displayTvSwiper() llama a estos metodos cuando
    // muestra un CATEGORY_TV_SWIPER, pero nosotros eliminamos el carousel
    // FEATURED del home TV. Como nunca se muestra un swiper, estos metodos
    // nunca se llaman en la practica — pero deben existir para que compile.
    fun updateBackground(uri: String?, swiperHasFocus: Boolean? = false) {}
    fun pinBackground(uri: String?) {}
    fun releasePinnedBackground() {}
    fun resetSwiperSchedule() {}

    private fun initializeHome() {
        binding.vgvHome.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(resources.getDimension(R.dimen.home_spacing).toInt() * 2)
        }

        // WLFMOVIE: Logo WlfMovie fijo arriba (igual que mobile pero más grande).
        // Al click, scroll al inicio del home.
        binding.ivProviderLogo.apply {
            Glide.with(context)
                .load(R.drawable.ic_wlfmovie_logo)
                .error(R.drawable.ic_provider_default_logo)
                .fitCenter()
                .into(this)

            setOnClickListener {
                binding.vgvHome.smoothScrollToPosition(0)
            }
        }

        // WLFMOVIE: Asegurar que el fondo dinámico esté oculto — usamos
        // el fondo morado del layout (wlf_bg_details_fragment).
        binding.ivHomeBackground.visibility = View.GONE

        binding.root.requestFocus()
    }

    private fun displayHome(categories: List<Category>) {
        // WLFMOVIE: Eliminada la categoria FEATURED (carousel auto-scroll).
        // El home muestra solo carruseles horizontales, igual que mobile.

        categories
            .find { it.name == Category.CONTINUE_WATCHING }
            ?.also {
                it.name = getString(R.string.home_continue_watching)
                it.list.forEach { show ->
                    when (show) {
                        is Episode -> show.itemType = AppAdapter.Type.EPISODE_CONTINUE_WATCHING_TV_ITEM
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM
                    }
                }
            }

        categories
            .find { it.name == Category.FAVORITE_MOVIES }
            ?.also { it.name = getString(R.string.home_favorite_movies) }

        categories
            .find { it.name == Category.FAVORITE_TV_SHOWS }
            ?.also { it.name = getString(R.string.home_favorite_tv_shows) }

        // WLFMOVIE: Filtrar FEATURED (no lo mostramos en TV, igual que mobile).
        val filteredCategories = categories.filter {
            it.name != Category.FEATURED && it.list.isNotEmpty()
        }

        appAdapter.submitList(
            filteredCategories.onEach { category ->
                if (category.name != getString(R.string.home_continue_watching)) {
                    category.list.forEach { show ->
                        when (show) {
                            is Episode -> show.itemType = AppAdapter.Type.EPISODE_TV_ITEM
                            is Movie -> show.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                            is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                        }
                    }
                }
                category.itemSpacing = resources.getDimension(R.dimen.home_spacing).toInt()
                category.itemType = AppAdapter.Type.CATEGORY_TV_ITEM
            }
        )
    }
}
