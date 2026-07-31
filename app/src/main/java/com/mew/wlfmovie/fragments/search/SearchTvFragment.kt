package com.mew.wlfmovie.fragments.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
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
import com.mew.wlfmovie.databinding.FragmentSearchTvBinding
import com.mew.wlfmovie.fragments.movies.GenreChipTvAdapter
import com.mew.wlfmovie.models.Category
import com.mew.wlfmovie.models.Genre
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.models.TvShow
import com.mew.wlfmovie.utils.CacheUtils
import com.mew.wlfmovie.utils.LoggingUtils
import com.mew.wlfmovie.utils.UserPreferences
import com.mew.wlfmovie.utils.VoiceRecognitionHelper
import com.mew.wlfmovie.utils.hideKeyboard
import com.mew.wlfmovie.utils.viewModelsFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import com.mew.wlfmovie.providers.Provider

class SearchTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false
    private var _binding: FragmentSearchTvBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { SearchViewModel(database) }
    private var currentGridColumns: Int = 1

    private val appAdapter by lazy {
        AppAdapter().apply {
            onMovieClickListener = { movie ->
                if (movie.providerName != UserPreferences.currentProvider?.name) {
                    UserPreferences.currentProvider = Provider.providers.keys.find { it.name == movie.providerName }
                    Toast.makeText(requireContext(), getString(R.string.switching_to_provider, movie.providerName), Toast.LENGTH_SHORT).show()
                }
                findNavController().navigate(
                    SearchTvFragmentDirections.actionSearchToMovie(id = movie.id)
                )
            }
            onTvShowClickListener = { tvShow ->
                if (tvShow.providerName != UserPreferences.currentProvider?.name) {
                    UserPreferences.currentProvider = Provider.providers.keys.find { it.name == tvShow.providerName }
                    Toast.makeText(requireContext(), getString(R.string.switching_to_provider, tvShow.providerName), Toast.LENGTH_SHORT).show()
                }
                findNavController().navigate(
                    SearchTvFragmentDirections.actionSearchToTvShow(
                        id = tvShow.id,
                        poster = tvShow.poster,
                        banner = tvShow.banner,
                    )
                )
            }
        }
    }

    private lateinit var voiceHelper: VoiceRecognitionHelper

    // WLFMOVIE: chips de géneros
    private var genreChipAdapter: GenreChipTvAdapter? = null
    private var selectedGenreId: String? = "all" // "Todos" por defecto
    private var searchJob: Job? = null // debounce auto-search

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeSearch()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is State.Searching -> {
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
                        binding.vgvSearch.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is State.GlobalSearching -> {
                        // WLFMOVIE: Búsqueda global eliminada, no debería llegar aquí.
                    }
                    is State.SuccessGlobalSearching -> {
                        // WLFMOVIE: Búsqueda global eliminada, no debería llegar aquí.
                    }
                    is State.FailedSearching -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            Toast.makeText(requireContext(), getString(R.string.clear_cache_done_409), Toast.LENGTH_SHORT).show()
                            if (appAdapter.isLoading) appAdapter.isLoading = false
                            viewModel.search(viewModel.query)
                            return@collect
                        }
                        Toast.makeText(requireContext(), state.error.message ?: "", Toast.LENGTH_SHORT).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                btnIsLoadingRetry.setOnClickListener { viewModel.search(viewModel.query) }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    Toast.makeText(requireContext(), getString(R.string.clear_cache_done), Toast.LENGTH_SHORT).show()
                                    viewModel.search(viewModel.query)
                                }
                                btnIsLoadingErrorDetails.setOnClickListener {
                                    LoggingUtils.showErrorDialog(requireContext(), state.error)
                                }
                                binding.vgvSearch.visibility = View.INVISIBLE
                                binding.etSearch.nextFocusDownId = binding.isLoading.btnIsLoadingRetry.id
                                binding.isLoading.btnIsLoadingRetry.nextFocusUpId = binding.etSearch.id
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        voiceHelper.stopRecognition()
        // WLFMOVIE: Resetear el genreChipAdapter para que se recreé al volver
        // al fragment (después de entrar a una carátula y devolverse).
        genreChipAdapter = null
        _binding = null
    }

    private fun submitSearch(): Boolean {
        val query = binding.etSearch.text?.toString().orEmpty()
        hideKeyboard()
        viewModel.search(query)
        return true
    }

    private fun initializeSearch() {
        binding.etSearch.hint = getString(R.string.search_input_hint)

        // WLFMOVIE: Navegación D-pad configurada:
        // et_search → rv_genres → vgv_search
        binding.rvGenres.nextFocusUpId = binding.clSearch.id
        binding.vgvSearch.nextFocusUpId = binding.rvGenres.id

        binding.etSearch.apply {
            setOnEditorActionListener { _, actionId, event ->
                val isSubmitAction =
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                        actionId == EditorInfo.IME_ACTION_DONE ||
                        actionId == EditorInfo.IME_NULL
                val isSubmitKey =
                    event?.action == KeyEvent.ACTION_DOWN &&
                        (event.keyCode == KeyEvent.KEYCODE_ENTER ||
                            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)

                if (isSubmitAction || isSubmitKey) {
                    return@setOnEditorActionListener submitSearch()
                }
                return@setOnEditorActionListener false
            }

            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }

                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    return@setOnKeyListener focusSearchContent()
                }

                if (
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                    keyCode == KeyEvent.KEYCODE_SEARCH
                ) {
                    return@setOnKeyListener submitSearch()
                }

                false
            }

            // WLFMOVIE: Auto-search con debounce de 300ms.
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val query = s?.toString()?.trim() ?: ""
                    if (query.isBlank()) {
                        // WLFMOVIE: Si está vacío, mostrar "Todos"
                        selectedGenreId = "all"
                        genreChipAdapter?.setSelected("all")
                        viewModel.search("")
                    } else {
                        // WLFMOVIE: Auto-search con debounce
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

        voiceHelper = VoiceRecognitionHelper(
            fragment = this,
            onResult = { query ->
                binding.btnSearchVoice.clearAnimation()
                binding.etSearch.setText(query)
                viewModel.search(query)
            },
            onError = { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                binding.btnSearchVoice.clearAnimation()
                binding.etSearch.hint = getString(R.string.search_input_hint)
            },
            onListeningStateChanged = { isListening ->
                binding.btnSearchVoice.startAnimation(android.view.animation.AlphaAnimation(1f, 0.3f).apply {
                    duration = 500
                    repeatCount = android.view.animation.Animation.INFINITE
                    repeatMode = android.view.animation.Animation.REVERSE
                })
                binding.etSearch.hint = getString(R.string.voice_prompt)
            }
        )

        binding.btnSearchVoice.apply {
            requestFocus()
            visibility = if (voiceHelper.isAvailable()) View.VISIBLE else View.GONE
            setOnClickListener { if (!voiceHelper.isListening) voiceHelper.startWithPermissionCheck() }
        }

        listOf(binding.btnSearchClear, binding.btnSearchVoice).forEach { view ->
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                    focusSearchContent()
                } else {
                    false
                }
            }
        }

        binding.btnSearchClear.setOnClickListener {
            binding.etSearch.setText("")
            binding.etSearch.hint = getString(R.string.search_input_hint)
            viewModel.search("")
        }

        binding.vgvSearch.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(resources.getDimension(R.dimen.search_spacing).toInt())

            // WLFMOVIE: Fix para subir desde carátulas a pastillas.
            // Leanback ignora el nextFocusUp del VerticalGridView, hay que
            // setearlo en cada item. Lo hacemos cuando el item recibe foco.
            addOnChildViewHolderSelectedListener(object : androidx.leanback.widget.OnChildViewHolderSelectedListener() {
                override fun onChildViewHolderSelected(
                    parent: RecyclerView,
                    child: RecyclerView.ViewHolder?,
                    position: Int,
                    subposition: Int,
                ) {
                    // Setear nextFocusUpId en el item para que al pulsar arriba
                    // vaya a las pastillas (rv_genres).
                    child?.itemView?.nextFocusUpId = binding.rvGenres.id
                }
            })
        }

        binding.root.requestFocus()
    }

    private fun focusSearchContent(): Boolean {
        val hasResults = appAdapter.itemCount > 0 && binding.vgvSearch.visibility == View.VISIBLE
        return when {
            hasResults -> {
                binding.vgvSearch.requestFocus()
            }
            else -> false
        }
    }

    private fun displaySearch(list: List<AppAdapter.Item>, hasMore: Boolean) {
        val genres = list.filterIsInstance<Genre>()
        val shows = list.filterNot { it is Genre }

        // WLFMOVIE: Setup chips de géneros (con "Todos" al inicio).
        if (genres.isNotEmpty()) {
            val allGenre = Genre(id = "all", name = "Todos")
            setupGenreChips(listOf(allGenre) + genres)
        }

        currentGridColumns = if (viewModel.query == "") 5 else 6
        binding.vgvSearch.setNumColumns(currentGridColumns)

        appAdapter.submitList(shows.onEach {
            when (it) {
                is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
                is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
            }
        })

        // WLFMOVIE: Scroll infinito.
        // - Si hay query de texto → loadMore() del ViewModel
        // - Si hay género seleccionado → loadMoreGenre() del ViewModel
        // - Si está en "Todos" (query vacío, sin género) → no hay scroll infinito
        //   (ya trae populares + géneros, no hay paginación de populares en SearchViewModel)
        when {
            viewModel.query != "" && hasMore -> {
                appAdapter.setOnLoadMoreListener { viewModel.loadMore() }
            }
            selectedGenreId != null && selectedGenreId != "all" && hasMore -> {
                appAdapter.setOnLoadMoreListener { viewModel.loadMoreGenre() }
            }
            else -> {
                appAdapter.setOnLoadMoreListener(null)
            }
        }
    }

    // WLFMOVIE: Setup chips de géneros para TV.
    private fun setupGenreChips(genres: List<Genre>) {
        if (genreChipAdapter == null) {
            genreChipAdapter = GenreChipTvAdapter(genres) { genre ->
                if (genre.id == "all") {
                    selectedGenreId = "all"
                    genreChipAdapter?.setSelected("all")
                    viewModel.search("")
                } else {
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
                Log.d("WlfMovie-SearchTv", "Chip click: ${genre.name} → selectedGenreId=$selectedGenreId")
            }
            binding.rvGenres.apply {
                adapter = genreChipAdapter
            }
        }
        genreChipAdapter?.setSelected("all")
    }
}
