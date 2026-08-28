package com.mew.wlfmovie.adapters.viewholders

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.mew.wlfmovie.R
import com.mew.wlfmovie.adapters.AppAdapter
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.databinding.ContentMovieCastMobileBinding
import com.mew.wlfmovie.databinding.ContentMovieCastTvBinding
import com.mew.wlfmovie.databinding.ContentMovieMobileBinding
import com.mew.wlfmovie.databinding.ContentMovieRecommendationsMobileBinding
import com.mew.wlfmovie.databinding.ContentMovieRecommendationsTvBinding
import com.mew.wlfmovie.databinding.ContentMovieTvBinding
import com.mew.wlfmovie.databinding.ItemCategorySwiperMobileBinding
import com.mew.wlfmovie.databinding.ItemMovieGridMobileBinding
import com.mew.wlfmovie.databinding.ItemMovieGridTvBinding
import com.mew.wlfmovie.databinding.ItemMovieMobileBinding
import com.mew.wlfmovie.databinding.ItemMovieTvBinding
import com.mew.wlfmovie.download.DownloadProgressState
import com.mew.wlfmovie.download.MovieDownloadManager
import com.mew.wlfmovie.fragments.genre.GenreMobileFragment
import com.mew.wlfmovie.fragments.genre.GenreMobileFragmentDirections
import com.mew.wlfmovie.fragments.genre.GenreTvFragment
import com.mew.wlfmovie.fragments.genre.GenreTvFragmentDirections
import com.mew.wlfmovie.fragments.home.HomeMobileFragment
import com.mew.wlfmovie.fragments.home.HomeMobileFragmentDirections
import com.mew.wlfmovie.fragments.home.HomeTvFragment
import com.mew.wlfmovie.fragments.home.HomeTvFragmentDirections
import com.mew.wlfmovie.fragments.movie.MovieMobileFragment
import com.mew.wlfmovie.fragments.movie.MovieMobileFragmentDirections
import com.mew.wlfmovie.fragments.movie.MovieTvFragment
import com.mew.wlfmovie.fragments.movie.MovieTvFragmentDirections
import com.mew.wlfmovie.fragments.movies.MoviesMobileFragment
import com.mew.wlfmovie.fragments.movies.MoviesMobileFragmentDirections
import com.mew.wlfmovie.fragments.movies.MoviesTvFragment
import com.mew.wlfmovie.fragments.movies.MoviesTvFragmentDirections
import com.mew.wlfmovie.fragments.people.PeopleMobileFragment
import com.mew.wlfmovie.fragments.people.PeopleMobileFragmentDirections
import com.mew.wlfmovie.fragments.people.PeopleTvFragment
import com.mew.wlfmovie.fragments.people.PeopleTvFragmentDirections
import com.mew.wlfmovie.fragments.search.SearchMobileFragment
import com.mew.wlfmovie.fragments.search.SearchMobileFragmentDirections
import com.mew.wlfmovie.fragments.search.SearchTvFragment
import com.mew.wlfmovie.fragments.search.SearchTvFragmentDirections
import com.mew.wlfmovie.fragments.tv_show.TvShowMobileFragment
import com.mew.wlfmovie.fragments.tv_show.TvShowMobileFragmentDirections
import com.mew.wlfmovie.fragments.tv_show.TvShowTvFragment
import com.mew.wlfmovie.fragments.tv_show.TvShowTvFragmentDirections
import com.mew.wlfmovie.fragments.tv_shows.TvShowsTvFragment
import com.mew.wlfmovie.fragments.tv_shows.TvShowsTvFragmentDirections
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.models.TvShow
import com.mew.wlfmovie.models.Video
import com.mew.wlfmovie.ui.MovieDownloadLanguageDialog
import com.mew.wlfmovie.ui.MovieDownloadServerDialog
import com.mew.wlfmovie.ui.ShowOptionsMobileDialog
import com.mew.wlfmovie.ui.ShowOptionsTvDialog
import com.mew.wlfmovie.ui.SlowLinkWarningDialog
import com.mew.wlfmovie.ui.SpacingItemDecoration
import com.mew.wlfmovie.utils.dp
import androidx.preference.Preference
import com.mew.wlfmovie.utils.format
import com.mew.wlfmovie.utils.getCurrentFragment
import com.mew.wlfmovie.utils.loadMovieBanner
import com.mew.wlfmovie.utils.loadMoviePoster
import com.mew.wlfmovie.utils.ArtworkRepair
import com.mew.wlfmovie.utils.toActivity
import java.util.Locale
import com.mew.wlfmovie.utils.UserPreferences
import com.mew.wlfmovie.providers.Provider
import android.view.KeyEvent
import com.mew.wlfmovie.databinding.ContentMovieDirectorsMobileBinding
import com.mew.wlfmovie.databinding.ContentMovieDirectorsTvBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MovieViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private val database: AppDatabase
        get() = AppDatabase.getInstance(context)
    private lateinit var movie: Movie
    private val TAG = "TrailerChoiceDebug" // Logging Tag

    // WLFMOVIE Update 7: Estado de descarga observado en displayMovieMobile.
    private var currentDownloadState: DownloadProgressState = DownloadProgressState.Idle

    // WLFMOVIE V6.3.3: Jobs de los collectors de progreso. Se guardan para
    // poder cancelarlos cuando el ViewHolder se re-bindea a OTRA pelicula
    // (evita que un collector viejo actualice la UI con el estado de la
    // pelicula equivocada).
    private var downloadProgressJob: kotlinx.coroutines.Job? = null
    private var downloadAnyActiveJob: kotlinx.coroutines.Job? = null

    // WLFMOVIE V6.3.1: Cache del botón Descargar + spinner para que
    // funciones de clase (startNewDownloadFlow, updateDownloadButton
    // invocados desde corutinas) puedan acceder a ellos sin necesitar
    // `binding` en scope (que solo está dentro de displayMovieMobile).
    private var downloadButton: android.widget.TextView? = null
    private var downloadSpinner: android.widget.ProgressBar? = null

    // WLFMOVIE V6.3.2: Cache de la tarjeta de progreso en detalles
    // (debajo de los botones Ver película / Descargar). Se muestra SOLO
    // cuando currentDownloadState es Downloading. Contiene:
    //  - llDownloadProgress: contenedor LinearLayout (toggle visible/gone)
    //  - tvDownloadStatus: texto "Descargando X MB · Y MB/s"
    //  - pbDownloadInlineSpinner: spinner indeterminate superpuesto al icono
    //  - pbDownloadInlineProgress: barra horizontal determinate (solo si
    //    conocemos el total, ej. Remux; HLS no lo conoce → oculta)
    //  - btnDownloadCancelInline: botón "Cancelar descarga" dedicado
    //    (el usuario reportó que no encontraba cómo cancelar; antes solo
    //    podía click en el botón "Descargando X MB" que no era obvio).
    private var llDownloadProgress: android.view.View? = null
    private var tvDownloadStatus: android.widget.TextView? = null
    private var pbDownloadInlineSpinner: android.widget.ProgressBar? = null
    private var pbDownloadInlineProgress: android.widget.ProgressBar? = null
    private var btnDownloadCancelInline: android.widget.TextView? = null

    // WLFMOVIE V6.4.5: Estado de conversión TS→MP4 observado en detalles
    // (DownloadConverter). Igual que currentDownloadState pero para la cola
    // de conversión: Queued(posición) / Converting(%) / Completed / Failed.
    private var currentConversionState: com.mew.wlfmovie.download.DownloadConverter.ConversionState =
        com.mew.wlfmovie.download.DownloadConverter.ConversionState.Idle

    // WLFMOVIE V6.4.5: Cache de la tarjeta de conversión en detalles
    // (ll_conversion_progress, debajo de la de descarga). Visible SOLO cuando
    // la película está en la cola de conversión a MP4.
    private var llConversionProgress: android.view.View? = null
    private var tvConversionStatus: android.widget.TextView? = null
    private var pbConversionInlineSpinner: android.widget.ProgressBar? = null
    private var pbConversionInlineProgress: android.widget.ProgressBar? = null

    // WLFMOVIE V6.4.5: Job del collector de estado de conversión (se cancela
    // y re-registra en cada bind, igual que downloadProgressJob).
    private var conversionStateJob: kotlinx.coroutines.Job? = null

    companion object {
        private const val KEY_PREFERRED_PLAYER = "preferred_player"
        private const val KEY_SMARTTUBE_PACKAGE = "preferred_smarttube_package" // New key for saving the exact package
        private const val PLAYER_YOUTUBE = "youtube"
        private const val PLAYER_SMARTTUBE = "smarttube"
        private const val PLAYER_SMARTTUBE_STABLE = "smarttube_stable"
        private const val PLAYER_SMARTTUBE_BETA = "smarttube_beta"
        private const val PLAYER_ASK = "ask"
        private const val SMARTTUBE_STABLE_PACKAGE = "org.smarttube.stable"
        private const val SMARTTUBE_BETA_PACKAGE = "org.smarttube.beta"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val YOUTUBE_TV_PACKAGE = "com.google.android.tv.youtube"
    }

    val childRecyclerView: RecyclerView?
        get() = when (_binding) {
            is ContentMovieCastMobileBinding -> _binding.rvMovieCast
            is ContentMovieCastTvBinding -> _binding.hgvMovieCast
            is ContentMovieRecommendationsMobileBinding -> _binding.rvMovieRecommendations
            is ContentMovieRecommendationsTvBinding -> _binding.hgvMovieRecommendations
            else -> null
        }

    fun bind(movie: Movie) {
        this.movie = movie

        when (_binding) {
            is ItemMovieMobileBinding -> displayMobileItem(_binding)
            is ItemMovieTvBinding -> displayTvItem(_binding)
            is ItemMovieGridMobileBinding -> displayGridMobileItem(_binding)
            is ItemMovieGridTvBinding -> displayGridTvItem(_binding)
            is ItemCategorySwiperMobileBinding -> displaySwiperMobileItem(_binding)

            is ContentMovieMobileBinding -> displayMovieMobile(_binding)
            is ContentMovieTvBinding -> displayMovieTv(_binding)
            is ContentMovieDirectorsMobileBinding -> displayDirectorsMobile(_binding)
            is ContentMovieDirectorsTvBinding -> displayDirectorsTv(_binding)
            is ContentMovieCastMobileBinding -> displayCastMobile(_binding)
            is ContentMovieCastTvBinding -> displayCastTv(_binding)
            is ContentMovieRecommendationsMobileBinding -> displayRecommendationsMobile(_binding)
            is ContentMovieRecommendationsTvBinding -> displayRecommendationsTv(_binding)
        }
    }

    private fun checkProviderAndRun(action: () -> Unit) {
        if (!movie.providerName.isNullOrBlank() && movie.providerName != UserPreferences.currentProvider?.name) {
            Provider.providers.keys.find { it.name == movie.providerName }?.let {
                UserPreferences.currentProvider = it
            }
        }
        action()
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun getInstalledSmartTubePackages(): List<String> {
        val installed = mutableListOf<String>()
        if (isPackageInstalled(SMARTTUBE_STABLE_PACKAGE)) installed.add(SMARTTUBE_STABLE_PACKAGE)
        if (isPackageInstalled(SMARTTUBE_BETA_PACKAGE)) installed.add(SMARTTUBE_BETA_PACKAGE)
        return installed
    }

    private fun launchSmartTube(packageName: String, trailerUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, trailerUrl.toUri())
        intent.setPackage(packageName)
        context.startActivity(intent)
    }

    private fun showSmartTubeVersionDialog(packages: List<String>, trailerUrl: String, shouldSavePreference: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = prefs.edit()
        
        val items = packages.map { pkg ->
            if (pkg == SMARTTUBE_STABLE_PACKAGE) context.getString(R.string.smarttube_stable)
            else context.getString(R.string.smarttube_beta)
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.choose_smarttube_version))
            .setItems(items) { _, which ->
                val selectedPackage = packages[which]
                
                if (shouldSavePreference) {
                    // Salva la scelta dell'utente se la preferenza principale è "smarttube"
                    editor.putString(KEY_SMARTTUBE_PACKAGE, selectedPackage).apply()
                    Log.d(TAG, "SmartTube version saved: $selectedPackage")
                }
                
                launchSmartTube(selectedPackage, trailerUrl)
            }.show()
    }

    private fun safeLaunchYoutube(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch YouTube intent", e)
            Toast.makeText(context, context.getString(R.string.player_external_player_error_video), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSmartTubeSelection(trailerUrl: String, logPrefix: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val savedPackage = prefs.getString(KEY_SMARTTUBE_PACKAGE, null)
        val stPackages = getInstalledSmartTubePackages()

        Log.d(TAG, "$logPrefix: SmartTube packages found: ${stPackages.size}. Saved package: $savedPackage")
        
        if (stPackages.isEmpty()) {
            // Caso 1: Nessuna SmartTube installata. Fallback su YouTube.
            Log.d(TAG, "$logPrefix: No SmartTube installed, falling back to YouTube")
            safeLaunchYoutube(Intent(Intent.ACTION_VIEW, trailerUrl.toUri()))
            return
        }

        if (stPackages.size == 1) {
            // Caso 2: Una sola SmartTube installata. Avvia direttamente.
            Log.d(TAG, "$logPrefix: Only one SmartTube installed: ${stPackages[0]}. Launching directly.")
            launchSmartTube(stPackages[0], trailerUrl)
            return
        }
        
        // Caso 3: Stable e Beta installate.
        if (savedPackage != null && stPackages.contains(savedPackage)) {
            // Caso 3a: Versione preferita è installata. Avvia direttamente la versione salvata.
            Log.d(TAG, "$logPrefix: Saved SmartTube version found: $savedPackage. Launching directly.")
            launchSmartTube(savedPackage, trailerUrl)
        } else {
            // Caso 3b: Nessuna preferenza salvata O la versione salvata non è più installata. Chiedi all'utente e salva la nuova scelta.
            Log.d(TAG, "$logPrefix: Saved version invalid or missing. Asking user which version to use.")
            showSmartTubeVersionDialog(stPackages, trailerUrl, true)
        }
    }

    private fun handleTrailerClick(trailer: String, logPrefix: String) {
        Log.d(TAG, "$logPrefix: Clicked. Trailer URL: $trailer")

        val youtubeIntent = Intent(Intent.ACTION_VIEW, trailer.toUri())
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val preferredPlayer = prefs.getString(KEY_PREFERRED_PLAYER, PLAYER_ASK)
        Log.d(TAG, "$logPrefix: Preferred player from settings: $preferredPlayer")

        when (preferredPlayer) {
            PLAYER_SMARTTUBE -> {
                handleSmartTubeSelection(trailer, logPrefix)
            }
            PLAYER_SMARTTUBE_STABLE -> {
                Log.d(TAG, "$logPrefix: Launching SmartTube Stable (Preferred)")
                launchSmartTube(SMARTTUBE_STABLE_PACKAGE, trailer)
            }
            PLAYER_SMARTTUBE_BETA -> {
                Log.d(TAG, "$logPrefix: Launching SmartTube Beta (Preferred)")
                launchSmartTube(SMARTTUBE_BETA_PACKAGE, trailer)
            }
            PLAYER_YOUTUBE -> {
                Log.d(TAG, "$logPrefix: Launching YouTube (Preferred)")
                safeLaunchYoutube(youtubeIntent)
            }
            else -> { // PLAYER_ASK or nothing set
                val stPackages = getInstalledSmartTubePackages()
                if (stPackages.isNotEmpty()) {
                    Log.d(TAG, "$logPrefix: Showing choice dialog (Ask)")
                    AlertDialog.Builder(context)
                        .setTitle(context.getString(R.string.watch_trailer_with))
                        .setItems(arrayOf(context.getString(R.string.youtube), context.getString(R.string.smarttube))) { _, which ->
                            if (which == 0) {
                                Log.d(TAG, "$logPrefix: Dialog (Ask): YouTube selected")
                                safeLaunchYoutube(youtubeIntent)
                            } else {
                                Log.d(TAG, "$logPrefix: Dialog (Ask): SmartTube selected")
                                // Qui, non salvare la preferenza per la versione SmartTube,
                                // ma chiedi quale usare se ci sono due installazioni.
                                if (stPackages.size > 1) {
                                    showSmartTubeVersionDialog(stPackages, trailer, false)
                                } else {
                                    launchSmartTube(stPackages[0], trailer)
                                }
                            }
                        }.show()
                } else {
                    Log.d(TAG, "$logPrefix: SmartTube not found, launching YouTube directly")
                    safeLaunchYoutube(youtubeIntent)
                }
            }
        }
    }

    private fun displayMobileItem(binding: ItemMovieMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                checkProviderAndRun {
                    when (context.toActivity()?.getCurrentFragment()) {
                    is HomeMobileFragment -> {
                        findNavController().navigate(HomeMobileFragmentDirections.actionHomeToMovie(id = movie.id))
                        if (movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM) {
                            findNavController().navigate(MovieMobileFragmentDirections.actionMovieToPlayer(
                                id = movie.id,
                                title = movie.title,
                                subtitle = movie.released?.format("yyyy") ?: "",
                                videoType = Video.Type.Movie(id = movie.id, title = movie.title, releaseDate = movie.released?.format("yyyy-MM-dd") ?: "", poster = movie.poster ?: "", imdbId = movie.imdbId),
                            ))
                        }
                    }
                    is MoviesMobileFragment -> findNavController().navigate(   // <-- NUEVA LÍNEA
                        MoviesMobileFragmentDirections.actionMoviesToMovie(id = movie.id)
                    )                                                                          // <-- NUEVA LÍNEA
                    is MovieMobileFragment -> findNavController().navigate(MovieMobileFragmentDirections.actionMovieToMovie(id = movie.id))
                    is TvShowMobileFragment -> findNavController().navigate(TvShowMobileFragmentDirections.actionTvShowToMovie(id = movie.id))
                    }
                }
            }
            // WLFMOVIE Update 5: Long-click solo para continue watching (quitar de la lista)
            // WLFMOVIE Update 7: Long-click para items de Downloads → borrar descarga.
            binding.root.setOnLongClickListener {
                when {
                    movie.isFromDownloads -> {
                        showDeleteDownloadDialogForMovie(movie)
                        true
                    }
                    movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM -> {
                        com.mew.wlfmovie.utils.ContinueWatchingRemover.showForMovie(context, movie)
                        true
                    }
                    else -> {
                        true // long-click deshabilitado para otros items
                    }
                }
            }
        }

        binding.ivMoviePoster.loadMoviePoster(movie) {
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }

        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieReleasedYear.text = movie.released?.format("yyyy")
            ?: context.getString(R.string.movie_item_type)

        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvMovieTitle.text = movie.title
    }

    private fun displayTvItem(binding: ItemMovieTvBinding) {
        binding.root.apply {
            isFocusable = true
            setOnClickListener {
                checkProviderAndRun {
                    when (context.toActivity()?.getCurrentFragment()) {
                        is HomeTvFragment -> {
                            if (movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM) {
                                findNavController().navigate(
                                    R.id.action_global_player,
                                    Bundle().apply {
                                        putString("id", movie.id)
                                        putString("title", movie.title)
                                        putString("subtitle", movie.released?.format("yyyy") ?: "")
                                        putSerializable(
                                            "videoType",
                                            Video.Type.Movie(
                                                id = movie.id,
                                                title = movie.title,
                                                releaseDate = movie.released?.format("yyyy-MM-dd") ?: "",
                                                poster = movie.poster ?: movie.banner ?: "",
                                                imdbId = movie.imdbId,
                                            )
                                        )
                                    }
                                )
                            } else {
                                findNavController().navigate(HomeTvFragmentDirections.actionHomeToMovie(id = movie.id))
                            }
                        }
                        is MoviesTvFragment -> findNavController().navigate(MoviesTvFragmentDirections.actionMoviesToMovie(id = movie.id))
                        is GenreTvFragment -> findNavController().navigate(GenreTvFragmentDirections.actionGenreToMovie(id = movie.id))
                        is SearchTvFragment -> findNavController().navigate(SearchTvFragmentDirections.actionSearchToMovie(id = movie.id))
                        is MovieTvFragment -> findNavController().navigate(MovieTvFragmentDirections.actionMovieToMovie(id = movie.id))
                        is TvShowTvFragment -> findNavController().navigate(TvShowTvFragmentDirections.actionTvShowToMovie(id = movie.id))
                        is PeopleTvFragment -> findNavController().navigate(PeopleTvFragmentDirections.actionPeopleToMovie(id = movie.id))
                    }
                }
            }


            // WLFMOVIE Update 5: Long-click solo para continue watching TV (quitar de la lista)
            binding.root.setOnLongClickListener {
                if (movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM) {
                    com.mew.wlfmovie.utils.ContinueWatchingRemover.showForMovie(context, movie)
                    true
                } else {
                    true // long-click deshabilitado para otros items
                }
            }
            setOnFocusChangeListener { _, hasFocus ->
                val animation = when {
                    hasFocus -> AnimationUtils.loadAnimation(context, R.anim.zoom_in)
                    else -> AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                }
                binding.root.startAnimation(animation)
                animation.fillAfter = true

                when (val fragment = context.toActivity()?.getCurrentFragment()) {
                    is HomeTvFragment -> {
                        if (hasFocus) {
                            fragment.pinBackground(movie.banner)
                        } else {
                            fragment.releasePinnedBackground()
                        }
                    }
                }
            }
        }

        binding.ivMoviePoster.loadMoviePoster(movie) {
            fallback(R.drawable.glide_fallback_cover)
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }
        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory
            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }
        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }
        binding.tvMovieReleasedYear.text = movie.released?.format("yyyy")
            ?: context.getString(R.string.movie_item_type)
        binding.tvMovieTitle.text = movie.title
    }

    private fun displayGridMobileItem(binding: ItemMovieGridMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                checkProviderAndRun {
                    when (context.toActivity()?.getCurrentFragment()) {
                        is GenreMobileFragment -> findNavController().navigate(GenreMobileFragmentDirections.actionGenreToMovie(id = movie.id))
                        is MoviesMobileFragment -> findNavController().navigate(MoviesMobileFragmentDirections.actionMoviesToMovie(id = movie.id))
                        is PeopleMobileFragment -> findNavController().navigate(PeopleMobileFragmentDirections.actionPeopleToMovie(id = movie.id))
                        is SearchMobileFragment -> findNavController().navigate(SearchMobileFragmentDirections.actionSearchToMovie(id = movie.id))
                    }
                }
            }
            // WLFMOVIE Update 5: Long-click solo para continue watching (quitar de la lista)
            binding.root.setOnLongClickListener {
                if (movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM) {
                    com.mew.wlfmovie.utils.ContinueWatchingRemover.showForMovie(context, movie)
                    true
                } else {
                    true // long-click deshabilitado para otros items
                }
            }
        }

        binding.ivMoviePoster.loadMoviePoster(movie) {
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }

        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieReleasedYear.text = movie.released?.format("yyyy")
            ?: context.getString(R.string.movie_item_type)

        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvMovieTitle.text = movie.title
    }

    private fun displayGridTvItem(binding: ItemMovieGridTvBinding) {
        binding.root.apply {
            isFocusable = true
            setOnClickListener {
                checkProviderAndRun {
                    when (context.toActivity()?.getCurrentFragment()) {
                        is HomeTvFragment -> findNavController().navigate(HomeTvFragmentDirections.actionHomeToMovie(id = movie.id))
                        is MoviesTvFragment -> findNavController().navigate(MoviesTvFragmentDirections.actionMoviesToMovie(id = movie.id))
                        is GenreTvFragment -> findNavController().navigate(GenreTvFragmentDirections.actionGenreToMovie(id = movie.id))
                        is SearchTvFragment -> findNavController().navigate(SearchTvFragmentDirections.actionSearchToMovie(id = movie.id))
                        is PeopleTvFragment -> findNavController().navigate(PeopleTvFragmentDirections.actionPeopleToMovie(id = movie.id))
                    }
                }
            }

            binding.root.setOnLongClickListener { true } // WLFMOVIE: long-click deshabilitado
            setOnFocusChangeListener { _, hasFocus ->
                val animation = when {
                    hasFocus -> AnimationUtils.loadAnimation(context, R.anim.zoom_in)
                    else -> AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                }
                binding.root.startAnimation(animation)
                animation.fillAfter = true
            }
        }
        binding.ivMoviePoster.loadMoviePoster(movie) {
            fallback(R.drawable.glide_fallback_cover)
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }
        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory
            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }
        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }
        binding.tvMovieReleasedYear.text = movie.released?.format("yyyy")
            ?: context.getString(R.string.movie_item_type)
        binding.tvMovieTitle.text = movie.title
    }

    private fun displaySwiperMobileItem(binding: ItemCategorySwiperMobileBinding) {
        binding.ivSwiperBackground.loadMovieBanner(movie) {
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }

        binding.tvSwiperTitle.text = movie.title

        binding.tvSwiperTvShowLastEpisode.text = context.getString(R.string.movie_item_type)

        binding.tvSwiperQuality.apply {
            text = movie.quality
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvSwiperReleased.apply {
            text = movie.released?.format("yyyy")
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvSwiperRating.apply {
            text = movie.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.ivSwiperRatingIcon.visibility = binding.tvSwiperRating.visibility

        binding.tvSwiperOverview.apply {
            setOnClickListener {
                maxLines = when (maxLines) {
                    2 -> Int.MAX_VALUE
                    else -> 2
                }
            }

            text = movie.overview
        }

        binding.btnSwiperWatchNow.apply {
            setOnClickListener {
                findNavController().navigate(
                    HomeMobileFragmentDirections.actionHomeToMovie(
                        id = movie.id,
                    )
                )
            }
        }

        binding.pbSwiperProgress.apply {
            val watchHistory = movie.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }
    }


    private fun displayMovieMobile(binding: ContentMovieMobileBinding) {
        // WLFMOVIE: Backdrop 16:9 (sin WebView - YouTube bloquea embeds en WebView)
        binding.ivMoviePoster.run {
            loadMovieBanner(movie) {
                transition(DrawableTransitionOptions.withCrossFade())
            }
            visibility = when {
                movie.banner.isNullOrEmpty() && movie.poster.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieTitle.text = movie.title

        binding.tvMovieRating.text = movie.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"

        binding.tvMovieQuality.apply {
            text = movie.quality
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieReleased.apply {
            text = movie.released?.format("yyyy")
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieRuntime.apply {
            text = movie.runtime?.let {
                val hours = it / 60
                val minutes = it % 60
                when {
                    hours > 0 -> context.getString(
                        R.string.movie_runtime_hours_minutes,
                        hours,
                        minutes
                    )
                    else -> context.getString(R.string.movie_runtime_minutes, minutes)
                }
            }
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieGenres.apply {
            text = movie.genres.joinToString(", ") { it.name }
            visibility = when {
                movie.genres.isEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieOverview.text = movie.overview

        binding.btnMovieWatchNow.apply {
            setOnClickListener {
                // Este botón ya navega al reproductor, no a otra página de detalles.
                // Generalmente non necesita el cambio de proveedor, pero lo añadimos por seguridad.
                checkProviderAndRun {
                    findNavController().navigate(MovieMobileFragmentDirections.actionMovieToPlayer(
                        id = movie.id,
                        title = movie.title,
                        subtitle = movie.released?.format("yyyy") ?: "",
                        videoType = Video.Type.Movie(id = movie.id, title = movie.title, releaseDate = movie.released?.format("yyyy-MM-dd") ?: "", poster = movie.poster ?: movie.banner ?: "", imdbId = movie.imdbId),
                    ))
                }
            }
        }

        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.btnMovieTrailer.apply {
            val trailer = movie.trailer
            setOnClickListener {
                if (trailer != null) handleTrailerClick(trailer, "MovieMobile")
            }
            visibility = if (trailer != null) View.VISIBLE else View.GONE
        }

        binding.btnMovieFavorite.apply {

            fun Boolean.drawable() = when (this) {
                true -> R.drawable.ic_favorite_enable
                false -> R.drawable.ic_favorite_disable
            }

            setOnClickListener {
                checkProviderAndRun {
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.movieDao()
                        val current = dao.getById(movie.id)?.isFavorite ?: false
                        val newValue = !current
                        val resolvedMovie = ArtworkRepair.resolveMovieForFavorite(context, movie, newValue)

                        dao.upsertFavorite(resolvedMovie, newValue)

                        withContext(Dispatchers.Main) {
                            movie.poster = resolvedMovie.poster
                            movie.banner = resolvedMovie.banner
                            movie.isFavorite = newValue
                            setImageDrawable(
                                ContextCompat.getDrawable(context, newValue.drawable())
                            )
                        }
                    }
                }
            }

            setImageDrawable(
                ContextCompat.getDrawable(context, movie.isFavorite.drawable())
            )
        }

        // WLFMOVIE V6.3: Boton Descargar (peliculas HLS multi-provider).
        // Observa el estado de descarga en tiempo real y reacciona al click segun estado:
        //  - Idle/Failed/Cancelled → buscar servers HLS en TODOS los providers y
        //    mostrar dialogo de idioma (o dialogo slow-link si no hay HLS).
        //  - Downloading → dialogo "Cancelar descarga".
        //  - Completed → dialogo "Eliminar descarga".
        //
        // WLFMOVIE V6.3: en TV el boton se oculta defensivamente (ademas de que
        // el layout content_movie_tv.xml no lo tiene). Esto previene que alguien
        // sideloadee el APK mobile en una TV y vea un boton que no funciona.
        val isTvDevice = context.packageManager
            .hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        if (isTvDevice) {
            binding.btnMovieDownload.visibility = android.view.View.GONE
            // WLFMOVIE V6.3.2: limpiar cache en TV para evitar referencias
            // muertas a un botón oculto.
            downloadButton = null
            downloadSpinner = null
            // WLFMOVIE V6.3.2: TV no tiene la tarjeta de progreso en
            // content_movie_tv.xml, así que también null.
            llDownloadProgress = null
            tvDownloadStatus = null
            pbDownloadInlineSpinner = null
            pbDownloadInlineProgress = null
            btnDownloadCancelInline = null
            // WLFMOVIE V6.4.5: la tarjeta de conversión tampoco existe en TV.
            llConversionProgress = null
            tvConversionStatus = null
            pbConversionInlineSpinner = null
            pbConversionInlineProgress = null
        } else {
            // WLFMOVIE V6.3.1: Cachear referencias al botón y spinner como
            // fields de clase para que startNewDownloadFlow (invocado desde
            // corutinas de clase) pueda acceder sin necesitar `binding` en
            // scope. Se setean aqui porque este es el unico sitio donde la
            // UI de descarga se inicializa.
            downloadButton = binding.btnMovieDownload
            downloadSpinner = binding.pbMovieDownloadSpinner

            // WLFMOVIE V6.3.2: Cachear las referencias de la tarjeta de
            // progreso en detalles (visible solo en estado Downloading).
            llDownloadProgress = binding.llDownloadProgress
            tvDownloadStatus = binding.tvDownloadStatus
            pbDownloadInlineSpinner = binding.pbDownloadInlineSpinner
            pbDownloadInlineProgress = binding.pbDownloadInlineProgress
            btnDownloadCancelInline = binding.btnCancelDownloadInline

            // WLFMOVIE V6.4.5: cachear las referencias de la tarjeta de
            // conversión a MP4 (visible solo en Queued/Converting/Failed).
            llConversionProgress = binding.llConversionProgress
            tvConversionStatus = binding.tvConversionStatus
            pbConversionInlineSpinner = binding.pbConversionInlineSpinner
            pbConversionInlineProgress = binding.pbConversionInlineProgress

            // WLFMOVIE V6.3.2: Click en el botón Cancelar de la tarjeta
            // de progreso → abre el mismo diálogo que el botón principal
            // en estado Downloading. Lo extraemos a un método dedicado para
            // reusarlo desde ambos lados.
            btnDownloadCancelInline?.setOnClickListener {
                if (currentDownloadState is DownloadProgressState.Downloading) {
                    showCancelDownloadDialog()
                }
            }

            // WLFMOVIE V6.3.1: Observar DOS flujos:
            //  1) observeProgress(movie.id) — estado de ESTA pelicula
            //     (Idle/Downloading/Completed/Failed/Cancelled).
            //  2) observeAnyActiveDownload() — true si CUALQUIER otra pelicula
            //     se esta descargando ahora mismo. Lo usamos para bloquear
            //     el boton y mostrar "Espera..." mientras otra descarga corre.
            //
            // WLFMOVIE V6.3.3 (FIX UI detalles): dos cambios criticos:
            //  a) Si findViewTreeLifecycleOwner() devuelve null (pasa en
            //     algunos layouts/ViewPager donde el arbol de vistas no tiene
            //     LifecycleOwner), los collectors NUNCA se registraban y la
            //     UI de detalles no mostraba NADA de progreso (el usuario lo
            //     reporto dos veces). Fallback: ProcessLifecycleOwner, que
            //     siempre existe y vive tanto como la app.
            //  b) Cancelar los collectors del bind ANTERIOR: si el ViewHolder
            //     se recicla para OTRA pelicula, los collectors viejos seguian
            //     vivos y actualizaban la UI con el estado de la pelicula
            //     equivocada. Ahora cada bind reemplaza a los anteriores.
            //  c) Log de registro para poder diagnosticar por logcat si la
            //     tarjeta vuelve a "no aparecer".
            downloadProgressJob?.cancel()
            downloadAnyActiveJob?.cancel()
            conversionStateJob?.cancel()
            val lifecycleOwner = itemView.findViewTreeLifecycleOwner()
                ?: androidx.lifecycle.ProcessLifecycleOwner.get()
            android.util.Log.d(
                "MovieViewHolder",
                "Registrando collectors de descarga para movie=${movie.id} " +
                    "via ${if (itemView.findViewTreeLifecycleOwner() != null) "ViewTreeLifecycleOwner" else "ProcessLifecycleOwner(fallback)"}"
            )
            downloadProgressJob = lifecycleOwner.lifecycleScope.launch {
                MovieDownloadManager.observeProgress(movie.id).collect { state ->
                    val previousState = currentDownloadState
                    currentDownloadState = state
                    updateDownloadButton(binding.btnMovieDownload, binding.pbMovieDownloadSpinner)
                    // WLFMOVIE V6.3.4: si la descarga de ESTA pelicula fallo y
                    // tenemos mas servers en la cache, re-ofrecer la lista
                    // para que el usuario escoja otro (sin re-buscar 5s).
                    if (previousState is DownloadProgressState.Downloading &&
                        state is DownloadProgressState.Failed
                    ) {
                        maybeOfferAlternativeServers()
                    }
                }
            }
            downloadAnyActiveJob = lifecycleOwner.lifecycleScope.launch {
                MovieDownloadManager.observeAnyActiveDownload().collect { anyActive ->
                    isAnyOtherDownloadActive = anyActive &&
                        currentDownloadState !is DownloadProgressState.Downloading
                    updateDownloadButton(binding.btnMovieDownload, binding.pbMovieDownloadSpinner)
                }
            }
            // WLFMOVIE V6.4.5: observar el estado de conversión TS→MP4 de ESTA
            // película (misma mecánica que downloadProgressJob: se re-registra
            // en cada bind y la tarjeta se oculta para cualquier estado Idle).
            conversionStateJob = lifecycleOwner.lifecycleScope.launch {
                com.mew.wlfmovie.download.DownloadConverter.observeState(movie.id).collect { state ->
                    currentConversionState = state
                    updateConversionCard()
                }
            }
            // Estado inicial inmediato (sin esperar la primera emission del
            // Flow — cubre el caso de abrir detalles CON la descarga ya
            // corriendo y el combine tardando en emitir).
            //
            // WLFMOVIE V6.3.3: esto TAMBIÉN reemplaza el "arrancar oculto" de
            // V6.3.2 — updateDownloadButton/updateDownloadProgressCard ya
            // ocultan la tarjeta para cualquier estado != Downloading, así que
            // no hace falta forzar GONE después (eso borraba el estado que
            // acabábamos de pintar con el snapshot durante ~1s).
            currentDownloadState = MovieDownloadManager.snapshotProgressState(movie.id)
            updateDownloadButton(binding.btnMovieDownload, binding.pbMovieDownloadSpinner)
            // WLFMOVIE V6.4.5: estado inicial de conversión sin esperar la
            // primera emisión del Flow (cubre abrir detalles con la película
            // ya convirtiéndose o en cola).
            currentConversionState = com.mew.wlfmovie.download.DownloadConverter.snapshotState(movie.id)
            updateConversionCard()
            binding.btnMovieDownload.setOnClickListener {
                handleDownloadButtonClick()
            }
        }
    }

    // ====================================================================
    // WLFMOVIE Update 7: Helpers para descargas
    // ====================================================================

    /**
     * WLFMOVIE V6.3.1: Estado global — true si hay CUALQUIER descarga en curso
     * ahora mismo (que no sea esta pelicula). Lo usa [updateDownloadButton]
     * para bloquear el boton y mostrar "Espera...".
     */
    @Volatile
    private var isAnyOtherDownloadActive: Boolean = false

    /**
     * WLFMOVIE V6.3.1: True mientras estamos buscando servers HLS para esta
     * pelicula (entre el click del usuario y la apertura del diálogo de
     * idioma o del slow-link). Lo usamos para mostrar el spinner en el botón
     * y el texto "Buscando…" — antes el botón se quedaba quieto sin feedback.
     */
    @Volatile
    private var isSearching: Boolean = false

    /**
     * Actualiza el texto, el icono y el spinner del boton btn_movie_download
     * segun [currentDownloadState] y [isAnyOtherDownloadActive].
     *
     * WLFMOVIE V6.3.1: cambios principales:
     *  - Recibe el [spinner] ProgressBar por separado para togglear su
     *    visibilidad (el TextView no puede animar drawables por si solo).
     *  - Cuando ESTA pelicula se esta descargando: muestra el spinner +
     *    "Descargando... 23.4 MB" (o "Descargando X% — 23.4 MB" si conocemos
     *    el total — Remux lo sabe, HLS no lo sabe hasta el final).
     *  - Cuando OTRA pelicula se esta descargando y esta esta idle: muestra
     *    "Espera..." y deshabilita el boton (alpha 0.5).
     *  - En Completed: muestra "Ya descargada" con check (no "Descargar").
     *  - En Searching (entre click y dialogo): muestra "Buscando..." + spinner.
     */
    private fun updateDownloadButton(
        button: android.widget.TextView,
        spinner: android.widget.ProgressBar,
    ) {
        // WLFMOVIE V6.3.2: Helper — actualiza la tarjeta de progreso
        // (llDownloadProgress) según el estado. Se invoca DESPUES de que
        // currentDownloadState se setea, así que aquí leemos su valor.
        updateDownloadProgressCard()

        // WLFMOVIE V6.3.1: estado "Buscando…" tiene prioridad sobre el resto.
        // Se setea en startNewDownloadFlow() justo antes de lanzar la corrutina
        // de busqueda y se limpia cuando el diálogo aparece o el slow-link
        // aparece.
        if (isSearching) {
            button.text = context.getString(R.string.download_button_searching)
            spinner.visibility = android.view.View.VISIBLE
            button.isEnabled = false
            button.alpha = 0.7f
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.wlf_ic_download, 0, 0, 0)
            return
        }
        when (currentDownloadState) {
            is DownloadProgressState.Downloading -> {
                val state = currentDownloadState as DownloadProgressState.Downloading
                val downloadedHuman = MovieDownloadManager.humanReadableBytes(state.downloadedBytes)
                button.text = when {
                    // WLFMOVIE V6.3.3: sentinel de fase de guardado — el 100%
                    // de los segmentos ya bajó y estamos concatenando el
                    // archivo final en disco.
                    state.speedBps < 0L && state.totalBytes > 0L -> {
                        val percent = (state.progress * 100).toInt().coerceIn(0, 100)
                        context.getString(R.string.download_button_finalizing, percent)
                    }
                    state.totalBytes > 0L -> {
                        val percent = (state.progress * 100).toInt().coerceIn(0, 100)
                        context.getString(
                            R.string.download_button_progress_full,
                            percent,
                            downloadedHuman,
                        )
                    }
                    else -> {
                        // HLS: no conocemos el total hasta el final. Mostrar
                        // solo los bytes descargados + spinner.
                        context.getString(
                            R.string.download_button_progress_bytes,
                            downloadedHuman,
                        )
                    }
                }
                spinner.visibility = android.view.View.VISIBLE
                button.isEnabled = true
                button.alpha = 1f
            }
            is DownloadProgressState.Completed -> {
                // WLFMOVIE V6.3.1: Cambio de copy. Antes decía "Descargado",
                // que se confundía con "Descargar" (el botón parecía seguir
                // ofreciendo descarga). Ahora dice "Ya descargada" →
                // el usuario entiende que no hay que descargar de nuevo.
                // Click abre el diálogo "Eliminar descarga".
                button.text = context.getString(R.string.download_button_completed)
                spinner.visibility = android.view.View.GONE
                button.isEnabled = true
                button.alpha = 1f
            }
            is DownloadProgressState.Failed,
            is DownloadProgressState.Cancelled,
            is DownloadProgressState.Idle -> {
                // WLFMOVIE V6.3.1: Si OTRA descarga esta corriendo, bloquear
                // el boton con "Espera..." para evitar paralelas. Si no,
                // boton normal listo para iniciar descarga.
                if (isAnyOtherDownloadActive) {
                    button.text = context.getString(R.string.download_button_in_queue)
                    spinner.visibility = android.view.View.GONE
                    button.isEnabled = false
                    button.alpha = 0.5f
                } else {
                    button.text = context.getString(R.string.download_button)
                    spinner.visibility = android.view.View.GONE
                    button.isEnabled = true
                    button.alpha = 1f
                }
            }
        }
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.wlf_ic_download, 0, 0, 0)
    }

    /**
     * WLFMOVIE V6.3.2: Actualiza la tarjeta de progreso en detalles
     * (ll_download_progress). La tarjeta se compone de:
     *  - Icono de descarga con spinner superpuesto (siempre activo en Downloading)
     *  - Texto "Descargando X MB · Y MB/s" (o "Iniciando…" si speedBps=0 al inicio)
     *  - Barra de progreso horizontal (solo si totalBytes > 0 — Remux; HLS no)
     *  - Botón "Cancelar descarga" (siempre visible en Downloading)
     *
     * Se llama desde [updateDownloadButton] en cada emission del Flow.
     *
     * Si la tarjeta no está cacheada (TV o binding no inicializado), es no-op.
     */
    private fun updateDownloadProgressCard() {
        val card = llDownloadProgress ?: return
        val status = tvDownloadStatus ?: return
        val spinner = pbDownloadInlineSpinner ?: return
        val progressBar = pbDownloadInlineProgress ?: return

        when (currentDownloadState) {
            is DownloadProgressState.Downloading -> {
                val state = currentDownloadState as DownloadProgressState.Downloading
                if (card.visibility != android.view.View.VISIBLE) {
                    android.util.Log.d(
                        "MovieViewHolder",
                        "Tarjeta de progreso VISIBLE para movie=${movie.id} " +
                            "(bytes=${state.downloadedBytes}, total=${state.totalBytes})"
                    )
                }
                card.visibility = android.view.View.VISIBLE

                // WLFMOVIE V6.3.3: tres casos de texto:
                //  1. speedBps < 0 → fase de GUARDADO (concatenación local tras
                //     bajar el 100%): "Guardando película… X%".
                //  2. speedBps > 0 → descargando con velocidad: "X · Y/s".
                //  3. speedBps == 0 con bytes > 0 → el watchdog está
                //     reconectando (intervención): "X · reintentando…".
                //  4. bytes == 0 y sin velocidad → "Iniciando descarga…".
                val downloadedHuman = MovieDownloadManager.humanReadableBytes(state.downloadedBytes)
                status.text = when {
                    state.speedBps < 0L && state.totalBytes > 0L -> {
                        val percent = (state.progress * 100).toInt().coerceIn(0, 100)
                        context.getString(R.string.download_inline_status_finalizing, percent)
                    }
                    state.downloadedBytes <= 0L && state.speedBps <= 0L -> {
                        context.getString(R.string.download_inline_status_starting)
                    }
                    state.speedBps > 0L -> {
                        val speedHuman = MovieDownloadManager.humanReadableBytes(state.speedBps) + "/s"
                        context.getString(R.string.download_inline_status, downloadedHuman, speedHuman)
                    }
                    else -> {
                        // Bytes > 0 pero velocidad 0: el ticker no vio progreso
                        // en su última ventana — el watchdog está cancelando
                        // conexiones atascadas y reintentando. Mostrarlo así
                        // evita que parezca congelado.
                        context.getString(
                            R.string.download_inline_status_stalled_bytes,
                            downloadedHuman,
                        )
                    }
                }

                // Spinner siempre activo durante descarga (incluso si
                // conocemos el total, para que se vea "vivo").
                spinner.visibility = android.view.View.VISIBLE

                // Barra de progreso horizontal: solo si conocemos el total.
                // En la fase de guardado (speedBps < 0) el total SI se conoce
                // → el usuario ve la barra llenarse mientras se guarda.
                if (state.totalBytes > 0L) {
                    progressBar.visibility = android.view.View.VISIBLE
                    val percent = (state.progress * 100).toInt().coerceIn(0, 100)
                    progressBar.progress = percent
                } else {
                    // HLS: no conocemos total → ocultamos la barra, dejamos
                    // el spinner como indicador de actividad.
                    progressBar.visibility = android.view.View.GONE
                }
            }
            // Para todos los demás estados, ocultar la tarjeta completa.
            // El botón principal ya muestra "Descargar" / "Ya descargada" /
            // "Espera..." según corresponda. No duplicamos UI aquí.
            else -> {
                card.visibility = android.view.View.GONE
                spinner.visibility = android.view.View.GONE
                progressBar.visibility = android.view.View.GONE
            }
        }
    }

    /**
     * WLFMOVIE V6.4.5: Actualiza la tarjeta de conversión TS→MP4 en detalles
     * (ll_conversion_progress). Estados:
     *  - Queued(pos)     → "En cola para convertir (posición X)"
     *  - Converting(p,t) → "Convirtiendo a MP4… X%" con barra (o "…mm:ss"
     *                      sin porcentaje cuando no se pudo determinar la
     *                      duración del TS)
     *  - Completed       → "MP4 listo" (visible 1.5s, luego desaparece)
     *  - Failed          → "No se pudo convertir a MP4…" (el .ts se conserva)
     *  - Idle            → tarjeta oculta.
     *
     * Se llama desde el collector de DownloadConverter.observeState(movie.id)
     * y una vez al bindear (snapshot). En TV es no-op (refs null).
     */
    private fun updateConversionCard() {
        val card = llConversionProgress ?: return
        val status = tvConversionStatus ?: return
        val spinner = pbConversionInlineSpinner ?: return
        val progressBar = pbConversionInlineProgress ?: return

        when (val state = currentConversionState) {
            is com.mew.wlfmovie.download.DownloadConverter.ConversionState.Queued -> {
                card.visibility = android.view.View.VISIBLE
                status.text = context.getString(R.string.conversion_status_queued, state.position)
                spinner.visibility = android.view.View.VISIBLE
                progressBar.visibility = android.view.View.GONE
            }
            is com.mew.wlfmovie.download.DownloadConverter.ConversionState.Converting -> {
                card.visibility = android.view.View.VISIBLE
                if (state.hasProgress) {
                    val percent = (state.progress * 100).toInt().coerceIn(0, 100)
                    status.text = context.getString(R.string.conversion_status_converting, percent)
                    progressBar.visibility = android.view.View.VISIBLE
                    progressBar.progress = percent
                } else {
                    // Sin duración conocida (TS sin PCR): mostrar tiempo transcurrido.
                    status.text = context.getString(
                        R.string.conversion_status_converting_elapsed,
                        formatConversionElapsed(state.elapsedSec),
                    )
                    progressBar.visibility = android.view.View.GONE
                }
                spinner.visibility = android.view.View.VISIBLE
            }
            is com.mew.wlfmovie.download.DownloadConverter.ConversionState.Completed -> {
                card.visibility = android.view.View.VISIBLE
                status.text = context.getString(R.string.conversion_status_completed)
                spinner.visibility = android.view.View.GONE
                progressBar.visibility = android.view.View.GONE
            }
            is com.mew.wlfmovie.download.DownloadConverter.ConversionState.Failed -> {
                card.visibility = android.view.View.VISIBLE
                status.text = context.getString(R.string.conversion_status_failed)
                spinner.visibility = android.view.View.GONE
                progressBar.visibility = android.view.View.GONE
            }
            else -> {
                card.visibility = android.view.View.GONE
                spinner.visibility = android.view.View.GONE
                progressBar.visibility = android.view.View.GONE
            }
        }
    }

    /** mm:ss para el estado "Convirtiendo…" sin porcentaje. */
    private fun formatConversionElapsed(totalSec: Long): String {
        val safe = totalSec.coerceAtLeast(0L)
        val m = safe / 60L
        val s = safe % 60L
        return String.format(java.util.Locale.US, "%02d:%02d", m, s)
    }

    /**
     * Delegador del click en btn_movie_download segun el estado actual.
     */
    private fun handleDownloadButtonClick() {
        when (currentDownloadState) {
            is DownloadProgressState.Idle,
            is DownloadProgressState.Failed,
            is DownloadProgressState.Cancelled -> startNewDownloadFlow()
            is DownloadProgressState.Downloading -> showCancelDownloadDialog()
            is DownloadProgressState.Completed -> showDeleteDownloadDialog()
        }
    }

    /**
     * WLFMOVIE V6.3.4: Busca servers HLS en TODOS los providers, guarda el
     * resultado en la cache de servers de descarga y muestra el dialogo de
     * seleccion de idioma (o el dialogo slow-link si no hay HLS).
     *
     * Flujo nuevo (idea del usuario):
     *  1. Click en Descargar → se limpia la cache de servers de esta pelicula
     *     (los links cambian constantemente, siempre buscamos frescos) →
     *     busqueda ~5s → cache seteada con TODOS los servers por idioma.
     *  2. Dialogo de idioma.
     *  3. Si el idioma tiene MAS de 1 server → dialogo de servidor para que
     *     el usuario escoja. Si tiene 1 → descarga directa.
     *  4. Si la descarga falla → [maybeOfferAlternativeServers] re-ofrece la
     *     lista desde la cache (sin re-buscar), sin el server que fallo.
     */
    private fun startNewDownloadFlow() {
        // WLFMOVIE V6.3.1: Si ya hay una descarga en curso (cualquier pelicula),
        // bloquear el inicio de una nueva — el boton deberia estar deshabilitado,
        // pero por si el usuario click rapidamente o el estado aun no llego a la UI.
        if (MovieDownloadManager.isAnyDownloadActive()) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.download_already_active),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        val lifecycleOwner = itemView.findViewTreeLifecycleOwner()
            ?: androidx.lifecycle.ProcessLifecycleOwner.get()
        val scope = lifecycleOwner.lifecycleScope

        // WLFMOVIE V6.3.1: Activar el estado "Buscando…" en el botón ANTES
        // de lanzar la corrutina.
        val button = downloadButton
        val spinner = downloadSpinner
        if (button == null || spinner == null) {
            isSearching = false
            return
        }
        isSearching = true
        updateDownloadButton(button, spinner)

        scope.launch(Dispatchers.IO) {
            val videoType = Video.Type.Movie(
                id = movie.id,
                title = movie.title,
                releaseDate = movie.released?.format("yyyy-MM-dd") ?: "",
                poster = movie.poster ?: movie.banner ?: "",
                imdbId = movie.imdbId,
            )

            // WLFMOVIE V6.3.4: el click en Descargar SIEMPRE invalida la cache
            // de servers anterior de esta pelicula y la re-setea con la
            // busqueda fresca. Solo esta cache se toca — el resto de caches de
            // la app (home, userdata) quedan intactas.
            MovieDownloadManager.clearDownloadServerCache(movie.id)
            val candidate = runCatching {
                MovieDownloadManager.findBestDownloadableServer(movie.id, videoType)
            }.getOrNull()

            try {
                withContext(Dispatchers.Main) {
                    // Limpiar el estado "Buscando…" antes de mostrar el diálogo.
                    isSearching = false
                    updateDownloadButton(button, spinner)

                    if (candidate == null) {
                        // No hay HLS en ningun provider. Mostrar dialogo slow-link.
                        SlowLinkWarningDialog.show(
                            context = context,
                            onWatchOnline = {
                                checkProviderAndRun {
                                    itemView.findNavController().navigate(
                                        MovieMobileFragmentDirections.actionMovieToPlayer(
                                            id = movie.id,
                                            title = movie.title,
                                            subtitle = movie.released?.format("yyyy") ?: "",
                                            videoType = Video.Type.Movie(
                                                id = movie.id,
                                                title = movie.title,
                                                releaseDate = movie.released?.format("yyyy-MM-dd") ?: "",
                                                poster = movie.poster ?: movie.banner ?: "",
                                                imdbId = movie.imdbId,
                                            ),
                                        )
                                    )
                                }
                            },
                        )
                        return@withContext
                    }

                    // WLFMOVIE V6.3.4: guardar TODOS los servers encontrados en
                    // la cache de descarga (vive hasta el proximo click en
                    // Descargar o hasta cerrar la app).
                    MovieDownloadManager.setDownloadServerCache(movie.id, candidate)

                    MovieDownloadLanguageDialog.show(
                        context = context,
                        movieTitle = movie.title,
                        candidate = candidate,
                        estimatedSizeBytes = null,
                        onConfirm = { language -> onDownloadLanguageChosen(language) },
                        onCancel = { /* no-op */ },
                    )
                }
            } catch (t: Throwable) {
                // Salvaguarda: si algo falla, limpiar isSearching en Main.
                withContext(Dispatchers.Main) {
                    isSearching = false
                    updateDownloadButton(button, spinner)
                }
                Log.e("MovieViewHolder", "startNewDownloadFlow error", t)
            }
        }
    }

    /**
     * WLFMOVIE V6.3.4: Tras elegir idioma — si ese idioma tiene MAS de 1
     * server disponible, mostrar el dialogo de seleccion de servidor; si
     * tiene solo 1, arrancar la descarga directa.
     *
     * La lista sale de la cache de servers (seteada hace un momento), asi
     * que no hay que re-buscar.
     */
    private fun onDownloadLanguageChosen(language: String) {
        val servers = MovieDownloadManager.availableServersFor(movie.id, language)
        if (servers.isEmpty()) {
            // Sin cache (p.ej. el proceso murio entre el dialogo y ahora) —
            // re-lanzar el flujo completo como fallback.
            startNewDownloadFlow()
            return
        }
        if (servers.size == 1) {
            beginDownloadWithServer(servers.first(), language)
            return
        }
        MovieDownloadServerDialog.show(
            context = context,
            languageLabel = MovieDownloadManager.languageLabel(context, language),
            servers = servers,
            isRetry = false,
            onConfirm = { server -> beginDownloadWithServer(server, language) },
            onCancel = { /* no-op */ },
        )
    }

    /**
     * WLFMOVIE V6.3.4: Arranca la descarga con un server concreto y lo marca
     * como activo en la cache (para saber a quien excluir si falla).
     */
    private fun beginDownloadWithServer(server: Video.Server, language: String) {
        MovieDownloadManager.setActiveDownloadServer(movie.id, language, server.src)
        MovieDownloadManager.startDownload(
            context = context,
            movie = movie,
            language = language,
            url = server.src,
            serverName = server.name,
            kind = MovieDownloadManager.Kind.HLS,
        )
        Toast.makeText(
            context,
            context.getString(R.string.download_started_toast, movie.title),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * WLFMOVIE V6.3.4: La descarga de esta pelicula fallo. Si la cache de
     * servers tiene MAS servers del idioma elegido (excluyendo el que fallo),
     * re-ofrecer la lista al instante — sin volver a buscar 5s. El server que
     * fallo se quita SOLO de esta consulta; en la proxima busqueda volvera a
     * aparecer (puede que ya este reparado).
     */
    private fun maybeOfferAlternativeServers() {
        // Solo si el ViewHolder sigue vivo y adjunto a la ventana (si el
        // usuario salio de detalles, no interrumpimos con un dialogo).
        if (!itemView.isAttachedToWindow) return
        val snapshot = MovieDownloadManager.markFailedAndSnapshot(movie.id) ?: return
        Log.d(
            "MovieViewHolder",
            "Descarga fallo — reofreciendo ${snapshot.servers.size} servers " +
                "alternativos para movie=${movie.id}"
        )
        val langLabel = MovieDownloadManager.languageLabel(context, snapshot.language)
        MovieDownloadServerDialog.show(
            context = context,
            languageLabel = langLabel,
            servers = snapshot.servers,
            isRetry = true,
            onConfirm = { server -> beginDownloadWithServer(server, snapshot.language) },
            onCancel = { /* no-op */ },
        )
    }

    /**
     * WLFMOVIE V6.3.1: Diálogo "Cancelar descarga" con layout on-brand
     * (dialog_cancel_download.xml). Cancela la descarga en curso Y borra
     * todo lo descargado hasta el momento (segmentos + archivo parcial).
     *
     * Antes usaba AlertDialog.Builder nativo con los strings de "Eliminar",
     * lo que confundía al usuario (no quedaba claro que además de detener
     * se borraba el parcial).
     */
    private fun showCancelDownloadDialog() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_cancel_download, null)
        val tvTitle = view.findViewById<android.widget.TextView>(R.id.tv_dialog_title)
        val tvMessage = view.findViewById<android.widget.TextView>(R.id.tv_dialog_message)
        val btnKeep = view.findViewById<android.widget.TextView>(R.id.btn_keep)
        val btnCancel = view.findViewById<android.widget.TextView>(R.id.btn_cancel_download)

        tvTitle.text = context.getString(R.string.download_cancel_dialog_title)
        tvMessage.text = context.getString(R.string.download_cancel_dialog_body, movie.title)

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnKeep.setOnClickListener { dialog.dismiss() }
        btnCancel.setOnClickListener {
            dialog.dismiss()
            MovieDownloadManager.cancelDownload(context, movie.id)
            Toast.makeText(
                context,
                context.getString(R.string.download_cancelled_toast, movie.title),
                Toast.LENGTH_SHORT,
            ).show()
        }
        dialog.show()
    }

    /**
     * WLFMOVIE V6.3.1: Diálogo "Eliminar descarga" para btn_movie_download
     * en estado Completed. Layout on-brand (dialog_delete_download.xml),
     * copia del dialog_remove_continue_watching con strings propios.
     * Borra el archivo físico + row de DB.
     */
    private fun showDeleteDownloadDialog() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_delete_download, null)
        val tvTitle = view.findViewById<android.widget.TextView>(R.id.tv_dialog_title)
        val tvMessage = view.findViewById<android.widget.TextView>(R.id.tv_dialog_message)
        val btnCancel = view.findViewById<android.widget.TextView>(R.id.btn_cancel)
        val btnDelete = view.findViewById<android.widget.TextView>(R.id.btn_delete)

        tvTitle.text = context.getString(R.string.download_delete_dialog_title)
        tvMessage.text = context.getString(R.string.download_delete_dialog_body, movie.title)

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnDelete.setOnClickListener {
            dialog.dismiss()
            itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                MovieDownloadManager.deleteDownload(context, movie.id)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.download_deleted_toast, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    /**
     * WLFMOVIE V6.3.1: Diálogo "Eliminar descarga" para items de la sección
     * Downloads en el home (long-press). Igual que [showDeleteDownloadDialog]
     * pero recibe el movie a eliminar explícitamente (no necesariamente el
     * del ViewHolder). Usa el mismo layout on-brand.
     */
    private fun showDeleteDownloadDialogForMovie(target: Movie) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_delete_download, null)
        val tvTitle = view.findViewById<android.widget.TextView>(R.id.tv_dialog_title)
        val tvMessage = view.findViewById<android.widget.TextView>(R.id.tv_dialog_message)
        val btnCancel = view.findViewById<android.widget.TextView>(R.id.btn_cancel)
        val btnDelete = view.findViewById<android.widget.TextView>(R.id.btn_delete)

        tvTitle.text = context.getString(R.string.download_delete_dialog_title)
        tvMessage.text = context.getString(R.string.download_delete_dialog_body, target.title)

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnDelete.setOnClickListener {
            dialog.dismiss()
            itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                MovieDownloadManager.deleteDownload(context, target.id)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.download_deleted_toast, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun displayMovieTv(binding: ContentMovieTvBinding) {
        // WLFMOVIE: Cargar backdrop (banner horizontal) en vez de poster vertical.
        binding.ivMoviePoster.run {
            loadMovieBanner(movie) {
                transition(DrawableTransitionOptions.withCrossFade())
            }
            visibility = when {
                movie.banner.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieTitle.text = movie.title

        binding.tvMovieRating.text = movie.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"

        binding.tvMovieQuality.apply {
            text = movie.quality
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieReleased.apply {
            text = movie.released?.format("yyyy")
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieRuntime.apply {
            text = movie.runtime?.let {
                val hours = it / 60
                val minutes = it % 60
                when {
                    hours > 0 -> context.getString(
                        R.string.movie_runtime_hours_minutes,
                        hours,
                        minutes
                    )
                    else -> context.getString(R.string.movie_runtime_minutes, minutes)
                }
            }
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieGenres.apply {
            text = movie.genres.joinToString(", ") { it.name }
            visibility = when {
                movie.genres.isEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieOverview.text = movie.overview

        binding.btnMovieWatchNow.apply {
            setOnClickListener {
                checkProviderAndRun {
                    findNavController().navigate(MovieTvFragmentDirections.actionMovieToPlayer(
                        id = movie.id,
                        title = movie.title,
                        subtitle = movie.released?.format("yyyy") ?: "",
                        videoType = Video.Type.Movie(id = movie.id, title = movie.title, releaseDate = movie.released?.format("yyyy-MM-dd") ?: "", poster = movie.poster ?: movie.banner ?: "", imdbId = movie.imdbId),
                    ))
                }
            }
        }

        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        // WLFMOVIE: Texto "Visto XX min · Faltan XX min" en TV (reemplaza barra).
        // El TextView tv_movie_progress_text solo existe en content_movie_tv.xml.
        binding.tvMovieProgressText?.apply {
            val watchHistory = movie.watchHistory
            if (watchHistory != null && watchHistory.durationMillis > 0) {
                val watchedMin = (watchHistory.lastPlaybackPositionMillis / 60000).toInt()
                val remainingMs = watchHistory.durationMillis - watchHistory.lastPlaybackPositionMillis
                val remainingMin = (remainingMs / 60000).toInt()
                text = if (remainingMin > 0) {
                    "Visto ${watchedMin} min · Faltan ${remainingMin} min"
                } else {
                    "Visto · Completado"
                }
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        binding.btnMovieTrailer.apply {
            val trailer = movie.trailer
            setOnClickListener {
                if (trailer != null) handleTrailerClick(trailer, "MovieTv")
            }
            visibility = if (trailer != null) View.VISIBLE else View.GONE
        }

        binding.btnMovieFavorite.apply {

            fun Boolean.drawable() = when (this) {
                true -> R.drawable.ic_favorite_enable
                false -> R.drawable.ic_favorite_disable
            }

            setOnClickListener {
                checkProviderAndRun {
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.movieDao()
                        val current = dao.getById(movie.id)?.isFavorite ?: false
                        val newValue = !current
                        val resolvedMovie = ArtworkRepair.resolveMovieForFavorite(context, movie, newValue)

                        dao.upsertFavorite(resolvedMovie, newValue)

                        withContext(Dispatchers.Main) {
                            movie.poster = resolvedMovie.poster
                            movie.banner = resolvedMovie.banner
                            movie.isFavorite = newValue
                            setImageDrawable(
                                ContextCompat.getDrawable(context, newValue.drawable())
                            )
                        }
                    }
                }
            }

            setImageDrawable(
                ContextCompat.getDrawable(context, movie.isFavorite.drawable())
            )
        }
    }

    private fun displayCastMobile(binding: ContentMovieCastMobileBinding) {
        binding.rvMovieCast.apply {
            adapter = AppAdapter().apply {
                submitList(movie.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
                })
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(20.dp(context)))
            }
        }
    }

    private fun displayCastTv(binding: ContentMovieCastTvBinding) {
        binding.hgvMovieCast.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(movie.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
                })
            }
            setItemSpacing(80)
        }
    }

    private fun displayDirectorsMobile(binding: ContentMovieDirectorsMobileBinding) {
        binding.rvMovieDirectors.text = movie.directors.joinToString (separator =", ") { it.name }
    }
    private fun displayDirectorsTv(binding: ContentMovieDirectorsTvBinding) {
        binding.rvMovieDirectors.text = movie.directors.joinToString (separator =", ") { it.name }
    }

    private fun displayRecommendationsMobile(binding: ContentMovieRecommendationsMobileBinding) {
        binding.rvMovieRecommendations.apply {
            adapter = AppAdapter().apply {
                submitList(movie.recommendations.onEach {
                    when (it) {
                        is Movie -> it.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                        is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                    }
                })
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(10.dp(context)))
            }
        }
    }

    private fun displayRecommendationsTv(binding: ContentMovieRecommendationsTvBinding) {
        binding.hgvMovieRecommendations.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(movie.recommendations.onEach {
                    when (it) {
                        is Movie -> it.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                        is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                    }
                })
            }
            setItemSpacing(20)
        }
    }
}
