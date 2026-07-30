package com.mew.wlfmovie.fragments.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.mew.wlfmovie.R
import com.mew.wlfmovie.adapters.AppAdapter
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.databinding.FragmentSearchMobileBinding
import com.mew.wlfmovie.models.Category
import com.mew.wlfmovie.models.Genre
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.models.TvShow
import com.mew.wlfmovie.ui.SpacingItemDecoration
import com.mew.wlfmovie.utils.CacheUtils
import com.mew.wlfmovie.utils.LoggingUtils
import com.mew.wlfmovie.utils.UserPreferences // <-- IMPORT AÑADIDO
import com.mew.wlfmovie.utils.VoiceRecognitionHelper
import com.mew.wlfmovie.utils.dp
import com.mew.wlfmovie.utils.hideKeyboard
import com.mew.wlfmovie.utils.viewModelsFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mew.wlfmovie.providers.IptvProvider

class SearchMobileFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentSearchMobileBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { SearchViewModel(database) }

    private var appAdapter = AppAdapter()
    private var searchJob: Job? = null // WLFMOVIE: debounce auto-search

    private lateinit var voiceHelper: VoiceRecognitionHelper

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeSearch()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                // ========= BLOQUE WHEN MODIFICADO =========
                when (state) {
                    is State.Searching, is State.GlobalSearching -> {
                        binding.isLoading.apply {
                            root.visibility = View.VISIBLE
                            pbIsLoading.visibility = View.VISIBLE
                            gIsLoadingRetry.visibility = View.GONE
                        }
                        appAdapter.isLoading = false
                        appAdapter.setOnLoadMoreListener(null)
                    }
                    is State.SearchingMore -> appAdapter.isLoading = true
                    is State.SuccessSearching -> {
                        displaySearch(state.results, state.hasMore)
                        appAdapter.isLoading = false
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is State.SuccessGlobalSearching -> {
                        displayGlobalSearch(state.providerResults)
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is State.FailedSearching -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.mew.wlfmovie.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.search(viewModel.query)
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
                                val doRetry = { viewModel.search(viewModel.query) }
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
                // ===========================================
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        voiceHelper.stopRecognition()
        _binding = null
    }

    private fun initializeSearch() {
        val isIptv = UserPreferences.currentProvider is IptvProvider
        val hintStringRes = if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
        binding.etSearch.hint = getString(hintStringRes)

        binding.etSearch.apply {
            // ========= LÓGICA DE BÚSQUEDA MODIFICADA =========
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    val query = binding.etSearch.text.toString()
                    hideKeyboard()

                    if (query.isBlank()) {
                        Toast.makeText(requireContext(), getString(R.string.search_empty_query), Toast.LENGTH_SHORT).show()
                        return@setOnEditorActionListener true
                    }

                    if (binding.swGlobalSearch.isChecked) {
                        val currentLanguage = UserPreferences.currentProvider?.language ?: "es"
                        viewModel.searchGlobal(query, currentLanguage)
                    } else {
                        viewModel.search(query)
                    }
                    return@setOnEditorActionListener true
                }
                return@setOnEditorActionListener false
            }
            // =================================================

            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val query = s?.toString()?.trim() ?: ""
                    if (query.isBlank()) {
                        val isIptv = UserPreferences.currentProvider is IptvProvider
                        val hintStringRes = if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
                        binding.etSearch.hint = getString(hintStringRes)
                        // WLFMOVIE: Si está vacío, mostrar "Todos"
                        viewModel.search("")
                    } else {
                        // WLFMOVIE: Auto-search con debounce de 300ms
                        searchJob?.cancel()
                        searchJob = viewLifecycleOwner.lifecycleScope.launch {
                            delay(300)
                            viewModel.search(query)
                        }
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        val blink = AlphaAnimation(1f, 0.3f).apply {
            duration = 500
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }

        voiceHelper = VoiceRecognitionHelper(
            fragment = this,
            onResult = { query ->
                // binding.btnSearchVoice.clearAnimation() // WLFMOVIE
                binding.etSearch.setText(query)
                viewModel.search(query)
            },
            onError = { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                // binding.btnSearchVoice.clearAnimation() // WLFMOVIE
                        val isIptv = UserPreferences.currentProvider is IptvProvider
        val hintStringRes = if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
        binding.etSearch.hint = getString(hintStringRes)
            },
            onListeningStateChanged = { isListening ->
                // binding.btnSearchVoice.startAnimation(blink) // WLFMOVIE
                binding.etSearch.hint = getString(R.string.voice_prompt)
            }
        )

        // WLFMOVIE: btnSearchVoice removido del layout

        binding.btnSearchClear.setOnClickListener {
            binding.etSearch.setText("")
                    val isIptv = UserPreferences.currentProvider is IptvProvider
        val hintStringRes = if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint
        binding.etSearch.hint = getString(hintStringRes)
            viewModel.search("")
        }

        binding.rvSearch.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(10.dp(requireContext()))
            )
        }
    }

    private fun displaySearch(list: List<AppAdapter.Item>, hasMore: Boolean) {
        val genres = list.filterIsInstance<Genre>()
        val shows = list.filterNot { it is Genre }

        if (genres.isNotEmpty()) {
            // WLFMOVIE: Agregar "Todos" al inicio de los géneros
            val allGenre = Genre(id = "all", name = "Todos")
            setupGenreChips(listOf(allGenre) + genres)
        }

        appAdapter.submitList(shows.onEach {
            when (it) {
                is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
            }
        })

        if (hasMore && viewModel.query != "") {
            appAdapter.setOnLoadMoreListener { viewModel.loadMore() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }

    private var selectedGenreId: String? = "all" // WLFMOVIE: "Todos" seleccionado por defecto
    private var genreChipAdapter: GenreChipAdapter? = null

    private fun setupGenreChips(genres: List<Genre>) {
        val rvGenres = binding.rvGenres
        rvGenres.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
            requireContext(),
            androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
            false
        )
        genreChipAdapter = GenreChipAdapter(genres) { genre ->
            if (genre.id == "all") {
                // WLFMOVIE: "Todos" siempre se selecciona, no se puede deseleccionar
                selectedGenreId = "all"
                genreChipAdapter?.setSelected("all")
                viewModel.search("")
            } else {
                // WLFMOVIE: Si el género ya estaba seleccionado, volver a "Todos"
                if (selectedGenreId == genre.id) {
                    selectedGenreId = "all"
                    genreChipAdapter?.setSelected("all")
                    viewModel.search("")
                } else {
                    selectedGenreId = genre.id
                    genreChipAdapter?.setSelected(selectedGenreId)
                    viewModel.searchByGenre(genre.id)
                }
            }
        }
        rvGenres.adapter = genreChipAdapter
        // WLFMOVIE: Seleccionar "Todos" por defecto
        genreChipAdapter?.setSelected("all")
    }

    // ========= NUEVA FUNCIÓN PARA MOSTRAR RESULTADOS GLOBALES =========
    private fun displayGlobalSearch(providerResults: List<ProviderResult>) {
        val allItems = mutableListOf<AppAdapter.Item>()

        providerResults.forEach { providerResult ->
            val headerTitle = when (val state = providerResult.state) {
                is ProviderResult.State.Loading -> "${providerResult.provider.name} - ${getString(R.string.searching)}"
                is ProviderResult.State.Error -> "${providerResult.provider.name} - ${getString(R.string.search_error)}"
                is ProviderResult.State.Success -> {
                    val count = state.results.size
                    val resultText = if (count == 1) getString(R.string.result) else getString(R.string.results)
                    "${providerResult.provider.name} - $count $resultText"
                }
            }

            val header = Category(
                name = headerTitle,
                list = emptyList()
            ).apply {
                itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
            }
            allItems.add(header)

            if (providerResult.state is ProviderResult.State.Success) {
                val results = providerResult.state.results.onEach {
                    when (it) {
                        is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                        is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
                    }
                }
                allItems.addAll(results)
            }
        }

        appAdapter.submitList(allItems)
        appAdapter.setOnLoadMoreListener(null)
    }
}

// WLFMOVIE: Adapter para chips de géneros
class GenreChipAdapter(
    private val genres: List<Genre>,
    private val onGenreClick: (Genre) -> Unit
) : RecyclerView.Adapter<GenreChipAdapter.ChipViewHolder>() {

    private var selectedId: String? = null

    fun setSelected(id: String?) {
        selectedId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.wlf_item_genre_chip, parent, false)
        return ChipViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
        holder.bind(genres[position], selectedId, onGenreClick)
    }

    override fun getItemCount() = genres.size

    class ChipViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: android.widget.TextView = view.findViewById(R.id.tv_genre_chip)

        fun bind(genre: Genre, selectedId: String?, onClick: (Genre) -> Unit) {
            text.text = genre.name
            text.isSelected = genre.id == selectedId
            text.setOnClickListener { onClick(genre) }
        }
    }
}
