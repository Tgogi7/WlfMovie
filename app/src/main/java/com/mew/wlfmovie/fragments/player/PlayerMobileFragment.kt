package com.mew.wlfmovie.fragments.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.ui.PlayerView
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.SubtitleView
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.mew.wlfmovie.R
import com.mew.wlfmovie.activities.tools.BypassWebViewActivity
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.databinding.ContentExoControllerMobileBinding
import com.mew.wlfmovie.databinding.FragmentPlayerMobileBinding
import com.mew.wlfmovie.models.Episode
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.models.Season
import com.mew.wlfmovie.models.TvShow
import com.mew.wlfmovie.models.Video
import com.mew.wlfmovie.models.WatchItem
import com.mew.wlfmovie.providers.SerienStreamProvider
import com.mew.wlfmovie.ui.PlayerMobileView
import com.mew.wlfmovie.utils.MediaServer
import com.mew.wlfmovie.utils.UserPreferences
import com.mew.wlfmovie.utils.UserDataCache
import com.mew.wlfmovie.utils.dp
import com.mew.wlfmovie.utils.getFileName
import com.mew.wlfmovie.utils.next
import com.mew.wlfmovie.utils.plus
import com.mew.wlfmovie.utils.setMediaServerId
import com.mew.wlfmovie.utils.setMediaServers
import com.mew.wlfmovie.utils.toSubtitleMimeType
import com.mew.wlfmovie.utils.viewModelsFactory
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import androidx.core.net.toUri
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.mew.wlfmovie.fragments.player.settings.PlayerSettingsView
import java.util.Base64 
import java.io.File
import java.io.FileOutputStream
import android.webkit.CookieManager
import androidx.core.content.FileProvider
import androidx.navigation.NavOptions
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.mew.wlfmovie.utils.DnsResolver
import com.mew.wlfmovie.utils.NetworkClient
import com.mew.wlfmovie.utils.EpisodeManager
import com.mew.wlfmovie.utils.PlayerGestureHelper
import com.mew.wlfmovie.utils.UserDataCache.toEpisode
import com.mew.wlfmovie.utils.UserDataCache.toMovie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.internal.userAgent
import java.util.Locale
import com.mew.wlfmovie.extractors.TokenManager
// WLFMOVIE: imports para diálogos, gestures y buffering
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout

class PlayerMobileFragment : Fragment() {
    companion object {
        private const val NEXT_EPISODE_PREFETCH_THRESHOLD_MS = 60_000L
        private const val NEXT_EPISODE_OVERLAY_MIN_THRESHOLD_MS = 30_000L
    }

    private var _binding: FragmentPlayerMobileBinding? = null
    private val binding get() = _binding!!
    private var isSetupDone = false

    private val PlayerControlView.binding
        get() = ContentExoControllerMobileBinding.bind(this.findViewById(R.id.cl_exo_controller))

    private val args by navArgs<PlayerMobileFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { PlayerViewModel(args.videoType, args.id) }

    private lateinit var player: ExoPlayer
    private lateinit var httpDataSource: HttpDataSource.Factory
    private lateinit var dataSourceFactory: DataSource.Factory
    private lateinit var mediaSession: MediaSession
    private lateinit var progressHandler: android.os.Handler
    private lateinit var progressRunnable: Runnable
    private var gestureHelper: PlayerGestureHelper? = null  // WLFMOVIE: deshabilitado, en Parte 3 se reemplaza

    private var servers = listOf<Video.Server>()
    private var zoomToast: Toast? = null

    private var currentVideo: Video? = null
    private var currentServer: Video.Server? = null
    private var isIgnoringPip = false
    private var waitingForBypass = false
    private var bypassDone = false
    private var nextEpisodePrefetchTargetId: String? = null
    private var nextEpisodePrefetchJob: Job? = null
    private var nextEpisodeOverlayDismissed = false

    private val bypassWebViewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cookies =
                result.data?.getStringExtra(BypassWebViewActivity.EXTRA_COOKIE_HEADER)?.trim()

            if (result.resultCode != android.app.Activity.RESULT_OK || cookies.isNullOrBlank()) {
                waitingForBypass = false
                return@registerForActivityResult
            }

            val bypassUrl = servers.firstOrNull { isSerienStreamBypassUrl(it.id) }?.id
            if (bypassUrl.isNullOrBlank()) {
                waitingForBypass = false
                return@registerForActivityResult
            }

            applyBypassCookies(bypassUrl, cookies)
            waitingForBypass = false
            bypassDone = true

            lifecycleScope.launch {
                delay(300)
                viewModel.reloadServersAfterBypass()
            }
        }

    private val chooserReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val clickedComponent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, android.content.ComponentName::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
                }
                Log.i("ExternalPlayer", "Mobile - App selezionata: ${clickedComponent?.packageName ?: "Sconosciuta"}")
            }
        }
    }

    private val pickLocalSubtitle = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        val fileName = uri.getFileName(requireContext()) ?: uri.toString()

        val currentPosition = player.currentPosition
        val currentSubtitleConfigurations =
            player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                MediaItem.SubtitleConfiguration.Builder(it.uri)
                    .setMimeType(it.mimeType)
                    .setLabel(it.label)
                    .setLanguage(it.language)
                    .setSelectionFlags(0)
                    .build()
            } ?: listOf()
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(player.currentMediaItem?.localConfiguration?.uri)
                .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                .setSubtitleConfigurations(
                    currentSubtitleConfigurations
                            + MediaItem.SubtitleConfiguration.Builder(uri)
                        .setMimeType(fileName.toSubtitleMimeType())
                        .setLabel(fileName)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
                .setMediaMetadata(player.mediaMetadata)
                .build()
        )
        player.seekTo(currentPosition)
        player.play()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        if (!isSetupDone) {
            requireActivity().requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            
            val window = requireActivity().window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            isSetupDone = true
        }
        isIgnoringPip = false
        if (::player.isInitialized) {
            binding.pvPlayer.useController = true
            // Resume playback after returning from bypass or any pause
            if (!player.isPlaying) {
                player.play()
            }
        }
        
        try {
            val filter = IntentFilter("ACTION_PLAYER_CHOSEN")
            ContextCompat.registerReceiver(
                requireContext(),
                chooserReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (ignored: Exception) {}
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializePlayer(false)
        initializeVideo()
        // WLFMOVIE: PlayerGestureHelper deshabilitado.
        // Las barras nuevas (ll_brightness, ll_volume en content_exo_controller_mobile.xml)
        // son siempre visibles. Su lógica táctil se implementará en Parte 3.
        // Por ahora las barras se ven pero no responden a gestures.
        // gestureHelper = PlayerGestureHelper(
        //     requireContext(),
        //     binding.pvPlayer,
        //     binding.llBrightness,
        //     binding.pbBrightness,
        //     binding.tvBrightnessPercentage,
        //     binding.llVolume,
        //     binding.pbVolume,
        //     binding.tvVolumePercentage
        // )

        // Stato Video
        viewLifecycleOwner.lifecycleScope.launch { 
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.CREATED).collect { state ->
                when (state) {
                    PlayerViewModel.State.LoadingServers -> {}
                    is PlayerViewModel.State.SuccessLoadingServers -> {
                        // =================================================================
                        // WLFMOVIE: Si ya tenemos un server actual reproduciendose,
                        // significa que este es un lote incremental con más servers.
                        // Solo actualizamos la lista del player SIN reiniciar el video.
                        // =================================================================
                        val isIncrementalUpdate = currentServer != null && servers.isNotEmpty()

                        servers = state.servers
                        val sToServer = servers.firstOrNull {
                            isSerienStreamBypassUrl(it.id)
                        }

                        if (sToServer != null && !waitingForBypass && !bypassDone) {
                            val bypassUrl = buildSerienStreamBypassUrl()
                            if (bypassUrl.isNullOrBlank()) {
                                waitingForBypass = false
                                Toast.makeText(requireContext(), "Unable to open s.to bypass page.", Toast.LENGTH_SHORT).show()
                                return@collect
                            }

                            waitingForBypass = true
                            bypassWebViewLauncher.launch(
                                Intent(requireContext(), BypassWebViewActivity::class.java)
                                    .putExtra(BypassWebViewActivity.EXTRA_URL, bypassUrl)
                            )
                        } else {
                            val providerName = UserPreferences.currentProvider?.name ?: ""
                            val isTmdb = providerName.contains("TMDb", ignoreCase = true)
                            val isAD = providerName.contains("AfterDark", ignoreCase = true)

                            if (servers.isEmpty()) {
                                val message = if (isTmdb || isAD) {
                                    val langCode = providerName.substringAfter("(").substringBefore(")")
                                    val locale = Locale.forLanguageTag(langCode)
                                    val langDisplayName = locale.getDisplayLanguage(Locale.getDefault())
                                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                                    if (isTmdb) getString(R.string.player_not_available_lang_message, langDisplayName)
                                    else getString(R.string.player_retry_later_message)
                                } else {
                                    "No servers found for this content."
                                }
                                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                                findNavController().navigateUp()
                                return@collect
                            }

                            // Actualizar la lista de servers en el player SIEMPRE
                            // (esto permite que aparezcan los nuevos servers en el selector)
                            player.playlistMetadata = MediaMetadata.Builder()
                                .setTitle(state.toString())
                                .setMediaServers(state.servers.map {
                                    MediaServer(
                                        id = it.id,
                                        name = it.name,
                                    )
                                })
                                .build()
                            binding.settings.setOnServerSelectedListener { server ->
                                viewModel.getVideo(state.servers.find { server.id == it.id }!!)
                            }

                            if (isIncrementalUpdate) {
                                // Solo actualizar la lista, NO reiniciar el video
                                Log.i("WlfMovie", "[PlayerFragment] Lista actualizada: ${servers.size} servers (sin reiniciar video)")
                            } else {
                                // Primera vez - autoseleccionar el mejor server (el primero, ya ordenado por prioridad)
                                Log.i("WlfMovie", "[PlayerFragment] Iniciando reproducción con: ${state.servers.first().name}")
                                viewModel.getVideo(state.servers.first())
                            }
                        }

                    }

                    is PlayerViewModel.State.FailedLoadingServers -> {
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_LONG
                        ).show()
                        findNavController().navigateUp()
                    }

                    is PlayerViewModel.State.LoadingVideo -> {
                        player.setMediaItem(
                            MediaItem.Builder()
                                .setUri("".toUri())
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setMediaServerId(state.server.id)
                                        .build()
                                )
                                .build()
                        )
                    }

                    is PlayerViewModel.State.SuccessLoadingVideo -> {
                        PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)
                        PlayerSettingsView.Settings.SoftwareDecoder.init(false)
                        displayVideo(state.video, state.server)
                    }

                    is PlayerViewModel.State.FailedLoadingVideo -> {
                        val nextServer = servers.getOrNull(servers.indexOf(state.server) + 1)
                        if (nextServer != null) {
                            viewModel.getVideo(nextServer)
                        } else {
                            val providerName = UserPreferences.currentProvider?.name ?: ""
                            val isTmdb = providerName.contains("TMDb", ignoreCase = true)
                            val isAD = providerName.contains("AfterDark", ignoreCase = true)

                            val message = if (isTmdb || isAD) {
                                val langCode = providerName.substringAfter("(").substringBefore(")")
                                val locale = Locale.forLanguageTag(langCode)
                                val langDisplayName = locale.getDisplayLanguage(Locale.getDefault())
                                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                                if (isTmdb) getString(R.string.player_not_available_lang_message, langDisplayName)
                                else getString(R.string.player_retry_later_message)
                            } else {
                                "All servers failed to load the video."
                            }
                            
                            Toast.makeText(
                                requireContext(),
                                message,
                                Toast.LENGTH_LONG
                            ).show()
                            findNavController().navigateUp()
                        }
                    }
                }
            }
        }

        // Stato Sottotitoli
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.subtitleState.flowWithLifecycle(lifecycle, Lifecycle.State.CREATED).collect { state ->
                when (state) {
                    PlayerViewModel.SubtitleState.Loading -> {}
                    is PlayerViewModel.SubtitleState.SuccessOpenSubtitles -> {
                        binding.settings.openSubtitles = state.subtitles
                    }
                    is PlayerViewModel.SubtitleState.FailedOpenSubtitles -> {}

                    PlayerViewModel.SubtitleState.DownloadingOpenSubtitle -> {}
                    is PlayerViewModel.SubtitleState.SuccessDownloadingOpenSubtitle -> {
                        val fileName = state.uri.getFileName(requireContext()) ?: state.uri.toString()
                        val currentPosition = player.currentPosition
                        val currentSubtitleConfigurations = player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                            MediaItem.SubtitleConfiguration.Builder(it.uri)
                                .setMimeType(it.mimeType)
                                .setLabel(it.label)
                                .setLanguage(it.language)
                                .setSelectionFlags(0)
                                .build()
                        } ?: listOf()
                        player.setMediaItem(
                            MediaItem.Builder()
                                .setUri(player.currentMediaItem?.localConfiguration?.uri)
                                .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                                .setSubtitleConfigurations(
                                    currentSubtitleConfigurations + MediaItem.SubtitleConfiguration.Builder(state.uri)
                                        .setMimeType(fileName.toSubtitleMimeType())
                                        .setLabel(fileName)
                                        .setLanguage(state.subtitle.languageName)
                                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                        .build()
                                )
                                .setMediaMetadata(player.mediaMetadata)
                                .build()
                        )
                        UserPreferences.subtitleName = (state.subtitle.languageName ?: fileName).substringBefore(" ")
                        player.seekTo(currentPosition)
                        player.play()
                    }
                    is PlayerViewModel.SubtitleState.FailedDownloadingOpenSubtitle -> {
                        Toast.makeText(requireContext(), "${state.subtitle.subFileName}: ${state.error.message}", Toast.LENGTH_LONG).show()
                    }

                    is PlayerViewModel.SubtitleState.SuccessSubDLSubtitles -> {
                        binding.settings.subDLSubtitles = state.subtitles
                    }
                    is PlayerViewModel.SubtitleState.FailedSubDLSubtitles -> {}

                    PlayerViewModel.SubtitleState.DownloadingSubDLSubtitle -> {}
                    is PlayerViewModel.SubtitleState.SuccessDownloadingSubDLSubtitle -> {
                        val fileName = state.uri.getFileName(requireContext()) ?: state.uri.toString()
                        val currentPosition = player.currentPosition
                        val currentSubtitleConfigurations = player.currentMediaItem?.localConfiguration?.subtitleConfigurations?.map {
                            MediaItem.SubtitleConfiguration.Builder(it.uri)
                                .setMimeType(it.mimeType)
                                .setLabel(it.label)
                                .setLanguage(it.language)
                                .setSelectionFlags(0)
                                .build()
                        } ?: listOf()
                        player.setMediaItem(
                            MediaItem.Builder()
                                .setUri(player.currentMediaItem?.localConfiguration?.uri)
                                .setMimeType(player.currentMediaItem?.localConfiguration?.mimeType)
                                .setSubtitleConfigurations(
                                    currentSubtitleConfigurations + MediaItem.SubtitleConfiguration.Builder(state.uri)
                                        .setMimeType(fileName.toSubtitleMimeType())
                                        .setLabel(state.subtitle.releaseName ?: state.subtitle.name ?: fileName)
                                        .setLanguage(state.subtitle.lang ?: state.subtitle.language ?: "Unknown")
                                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                        .build()
                                )
                                .setMediaMetadata(player.mediaMetadata)
                                .build()
                        )
                        UserPreferences.subtitleName = (state.subtitle.releaseName ?: state.subtitle.name ?: fileName).substringBefore(" ")
                        player.seekTo(currentPosition)
                        player.play()
                    }
                    is PlayerViewModel.SubtitleState.FailedDownloadingSubDLSubtitle -> {
                        Toast.makeText(requireContext(), "${state.subtitle.name}: ${state.error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.playPreviousOrNextEpisode.collect { nextEpisode ->
                    releasePlayer()
                    isSetupDone = false
                    val action = PlayerMobileFragmentDirections
                        .actionPlayerMobileFragmentSelf(
                            id = nextEpisode.id,
                            videoType = nextEpisode,
                            title = nextEpisode.tvShow.title,
                            subtitle = "S${nextEpisode.season.number} E${nextEpisode.number}  •  ${nextEpisode.title}"
                        )

                    hideNextEpisodeOverlay()
                    findNavController().navigate(
                        action,
                        NavOptions.Builder()
                            .setPopUpTo(
                                findNavController().currentDestination?.id ?: return@collect, true
                            )
                            .setLaunchSingleTop(false) 
                            .build()
                    )
                }
            }
        }


    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        binding.pvPlayer.useController = !isInPictureInPictureMode
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    fun onUserLeaveHint() {
        if (!isIgnoringPip && ::player.isInitialized && player.isPlaying) {
            enterPIPMode()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) {
            player.pause()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        nextEpisodePrefetchJob?.cancel()
        // WLFMOVIE: Hacer esto defensivo. Si la activity ya no está attached,
        // requireActivity() lanza IllegalStateException que crashea la app.
        try {
            val activity = activity ?: return
            val window = activity.window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            WindowCompat.getInsetsController(
                window,
                window.decorView
            ).run {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                show(WindowInsetsCompat.Type.systemBars())
            }
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } catch (e: Exception) {
            Log.e("WlfMovie", "Error en onDestroyView (no crítico): ${e.message}")
        }
        releasePlayer()
        try {
            requireContext().unregisterReceiver(chooserReceiver)
        } catch (ignored: Exception) {}
        _binding = null
        isSetupDone = false
    }

    fun onBackPressed(): Boolean {
        // WLFMOVIE: Protección contra crash cuando _binding es null (puede pasar
        // si el back se presiona muy rápido justo después de onDestroyView)
        val b = _binding ?: return false
        return when {
            b.pvPlayer.isManualZoomEnabled -> {
                b.pvPlayer.exitManualZoomMode()
                true
            }
            b.settings.isVisible -> {
                b.settings.onBackPressed()
            }
            else -> false
        }
    }


    private fun initializeVideo() {
        WindowCompat.getInsetsController(
            requireActivity().window,
            requireActivity().window.decorView
        ).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        when (val type = args.videoType) {
            is Video.Type.Episode -> {
                nextEpisodeOverlayDismissed = false
                nextEpisodePrefetchTargetId = null
                if (EpisodeManager.listIsEmpty(type)) {
                    EpisodeManager.clearEpisodes()
                    lifecycleScope.launch(Dispatchers.IO) {
                        EpisodeManager.addEpisodesFromDb(type, database)
                        withContext(Dispatchers.Main) {
                            EpisodeManager.setCurrentEpisode(type)
                            updatePlayerHeader(type)
                            setupEpisodeNavigationButtons()
                            refreshEpisodeNavigation(type)
                        }
                    }
                } else {
                    EpisodeManager.setCurrentEpisode(type)
                    setupEpisodeNavigationButtons()
                    refreshEpisodeNavigation(type)
                }
            }
            is Video.Type.Movie -> {
                nextEpisodeOverlayDismissed = false
                nextEpisodePrefetchTargetId = null
                EpisodeManager.clearEpisodes()
                hideNextEpisodeOverlay()
            }
        }


        binding.settings.onSubtitlesClicked = {
            viewModel.getSubtitles(args.videoType)
        }
        binding.settings.setOnExtraBufferingSelectedListener {
            displayVideo(
                currentVideo ?: return@setOnExtraBufferingSelectedListener,
                currentServer ?: return@setOnExtraBufferingSelectedListener
            )
        }
        binding.settings.setOnSoftwareDecoderSelectedListener { useSoftware ->
            currentSoftwareDecoder = useSoftware
            displayVideo(
                currentVideo ?: return@setOnSoftwareDecoderSelectedListener,
                currentServer ?: return@setOnSoftwareDecoderSelectedListener
            )
        }
        binding.pvPlayer.resizeMode = UserPreferences.playerResize.resizeMode
        binding.pvPlayer.subtitleView?.apply {
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * UserPreferences.captionTextSize)
            setStyle(UserPreferences.captionStyle)
            setPadding(0, 0, 0, UserPreferences.captionMargin.dp(context))
        }
        setupEpisodeNavigationButtons()

        binding.pvPlayer.controller.binding.btnExoBack.setOnClickListener {
            findNavController().navigateUp()
        }

        updatePlayerHeader()

        // WLFMOVIE: btnExoExternalPlayer eliminado (era "abrir con")

        // WLFMOVIE: exoReplay eliminado (era reiniciar a 0)

        // WLFMOVIE: btnExoLock / btnExoUnlock eliminados (era bloquear)
        // WLFMOVIE: gControlsLock eliminado

        // WLFMOVIE: btnExoPictureInPicture eliminado (era PIP)

        // WLFMOVIE: btnExoAspectRatio renombrado a btnExoZoom
        // Solo 2 modos: Fit (Normal) y Zoom (recorta para rellenar, SIN deformar)
        // Importante: usamos RESIZE_MODE_ZOOM (no FILL que deforma)
        binding.pvPlayer.controller.binding.btnExoZoom?.setOnClickListener {
            val currentResize = UserPreferences.playerResize
            // Alternar entre Fit y Zoom (NO Fill, que deforma)
            val newResize = when (currentResize) {
                UserPreferences.PlayerResize.Fit -> UserPreferences.PlayerResize.Zoom
                UserPreferences.PlayerResize.Zoom -> UserPreferences.PlayerResize.Fit
                // Si por algún reason está en otro modo, ir a Fit
                else -> UserPreferences.PlayerResize.Fit
            }
            UserPreferences.playerResize = newResize
            binding.pvPlayer.controllerShowTimeoutMs = binding.pvPlayer.controllerShowTimeoutMs
            updatePlayerScale()
            // WLFMOVIE: sin toast de modo zoom (lo eliminamos)
        }

        // WLFMOVIE: Botones ±10 con IDs nuevos (para que ExoPlayer no sobrescriba el icono)
        binding.pvPlayer.controller.binding.btnRew10?.setOnClickListener {
            player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
        }
        binding.pvPlayer.controller.binding.btnFfwd10?.setOnClickListener {
            player.seekTo((player.currentPosition + 10_000).coerceAtLeast(0))
        }

        // WLFMOVIE: Cada botón abre su propio diálogo centrado (no el settings viejo)
        binding.pvPlayer.controller.binding.btnExoSubs?.setOnClickListener {
            showSubtitlesDialog()
        }
        binding.pvPlayer.controller.binding.btnExoAudio?.setOnClickListener {
            showAudioDialog()
        }
        binding.pvPlayer.controller.binding.btnExoServers?.setOnClickListener {
            showServersDialog()
        }

        // WLFMOVIE: Spinner morado-fucsia + gestures de barras
        setupCustomBufferingIndicator()
        setupBrightnessVolumeGestures()

        // WLFMOVIE: Sincronizar visibilidad de las barras nuevas con el controller
        // Cuando el controller se oculta, las barras también se ocultan (junto con todo).
        // Esto evita que las barras queden visibles 1-2s después del resto.
        binding.pvPlayer.setControllerVisibilityListener(object : PlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                // WLFMOVIE: Protección contra NPE cuando el fragment se está destruyendo
                // y ExoPlayer aún invoca este callback.
                val b = _binding ?: return
                val isVisible = visibility == android.view.View.VISIBLE
                val newVisibility = if (isVisible) android.view.View.VISIBLE else android.view.View.GONE
                b.pvPlayer.controller.binding.llBrightness?.visibility = newVisibility
                b.pvPlayer.controller.binding.llVolume?.visibility = newVisibility
                b.pvPlayer.controller.binding.btnExoServers?.visibility = newVisibility
                // btnNextEpisode mantiene su lógica de visibilidad (solo en series con siguiente episodio)
                if (!isVisible) {
                    b.pvPlayer.controller.binding.btnNextEpisode?.visibility = android.view.View.GONE
                } else if (args.videoType is Video.Type.Episode && EpisodeManager.hasNextEpisode()) {
                    b.pvPlayer.controller.binding.btnNextEpisode?.visibility = android.view.View.VISIBLE
                }
            }
        })

        binding.settings.setOnLocalSubtitlesClickedListener {
            isIgnoringPip = true
            pickLocalSubtitle.launch(
                arrayOf(
                    "text/plain",
                    "text/str",
                    "application/octet-stream",
                    MimeTypes.TEXT_UNKNOWN,
                    MimeTypes.TEXT_VTT,
                    MimeTypes.TEXT_SSA,
                    MimeTypes.APPLICATION_TTML,
                    MimeTypes.APPLICATION_MP4VTT,
                    MimeTypes.APPLICATION_SUBRIP,
                )
            )
        }

        binding.settings.setOnOpenSubtitleSelectedListener { subtitle ->
            viewModel.downloadSubtitle(subtitle.openSubtitle)
        }

        binding.settings.setOnSubDLSubtitleSelectedListener { subtitle ->
            viewModel.downloadSubDLSubtitle(subtitle.subDLSubtitle)
        }

        binding.settings.setOnExtraBufferingSelectedListener {
            displayVideo(
                currentVideo ?: return@setOnExtraBufferingSelectedListener,
                currentServer ?: return@setOnExtraBufferingSelectedListener
            )
        }

        // WLFMOVIE: btnSkipIntro eliminado por completo (omitir intro)
        // Era: binding.pvPlayer.controller.binding.btnSkipIntro.setOnClickListener { ... }

        binding.btnNextEpisodeAction.setOnClickListener {
            hideNextEpisodeOverlay()
            playNextEpisodeAcrossSeasons()
        }
        binding.btnNextEpisodeDismiss.setOnClickListener {
            nextEpisodeOverlayDismissed = true
            hideNextEpisodeOverlay()
        }

        binding.settings.onManualZoomClicked = {
            binding.settings.hide()
            binding.pvPlayer.hideController()
            binding.pvPlayer.enterManualZoomMode()
        }
    }

 private fun updatePlayerScale() {
        val videoSurfaceView = binding.pvPlayer.videoSurfaceView
        val playerResize = UserPreferences.playerResize 

        binding.pvPlayer.resizeMode = playerResize.resizeMode 

        when (playerResize) { 
            UserPreferences.PlayerResize.Stretch43 -> {
                val scale = 1.33f 
                videoSurfaceView?.scaleX = scale
                videoSurfaceView?.scaleY = 1f
            }
            UserPreferences.PlayerResize.StretchVertical -> {
                videoSurfaceView?.scaleX = 1f
                videoSurfaceView?.scaleY = 1.25f
            }
            UserPreferences.PlayerResize.SuperZoom -> {
                videoSurfaceView?.scaleX = 1.5f
                videoSurfaceView?.scaleY = 1.5f
            }
            else -> {
                videoSurfaceView?.scaleX = 1f
                videoSurfaceView?.scaleY = 1f
            }
        }
    }

    fun setupEpisodeNavigationButtons() {
        // WLFMOVIE: btnCustomPrev eliminado (era doble flecha izq en centro).
        // Solo mantenemos el botón "siguiente episodio" arriba a la derecha.
        // Para episodio previo usamos D-pad izquierdo desde el botón central izq.
        val btnNext = binding.pvPlayer.controller.binding.btnNextEpisode

        // Si no es episodio, ocultar el botón
        if (args.videoType !is Video.Type.Episode) {
            btnNext?.isGone = true
            return
        }

        if (!EpisodeManager.hasNextEpisode()) {
            btnNext?.isGone = true
            return
        }

        btnNext?.isGone = false
        btnNext?.setOnClickListener listener@{
            if (!EpisodeManager.hasNextEpisode()) return@listener

            val videoType = args.videoType

            val watchItem: WatchItem? = when (videoType) {
                is Video.Type.Movie -> database.movieDao().getById(videoType.id)
                is Video.Type.Episode -> database.episodeDao().getById(videoType.id)
            }

            when (videoType) {
                is Video.Type.Movie -> {
                    val provider = UserPreferences.currentProvider ?: return@listener
                    val movie = watchItem as? Movie
                    movie?.let { database.movieDao().update(it) }
                    movie?.let { UserDataCache.addMovieToContinueWatching(requireContext(), provider, it) }
                }

                is Video.Type.Episode -> {
                    val provider = UserPreferences.currentProvider ?: return@listener
                    val episode = watchItem as? Episode
                    episode?.let {
                        if (player.hasFinished()) {
                            database.episodeDao().resetProgressionFromEpisode(videoType.id)
                            UserDataCache.removeEpisodeFromContinueWatching(requireContext(), provider, it.id)
                        }
                        database.episodeDao().update(it)

                        if (!player.hasFinished()) {
                            UserDataCache.addEpisodeToContinueWatching(requireContext(), provider, it)
                        }

                        it.tvShow?.let { tvShow ->
                            database.tvShowDao().getById(tvShow.id)
                        }?.let { tvShow ->
                            val episodeDao = database.episodeDao()
                            val isStillWatching = episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)

                            database.tvShowDao().save(tvShow.copy().apply {
                                merge(tvShow)
                                isWatching = !player.hasReallyFinished() || isStillWatching
                            })
                        }
                    }
                }
            }

            playNextEpisodeAcrossSeasons()
        }
    }

    private fun refreshEpisodeNavigation(type: Video.Type.Episode) {
        lifecycleScope.launch(Dispatchers.IO) {
            EpisodeManager.ensureNextEpisodeAvailable(type, database)
            withContext(Dispatchers.Main) {
                setupEpisodeNavigationButtons()
            }
        }
    }

    private fun playNextEpisodeAcrossSeasons(autoplay: Boolean = false) {
        val type = args.videoType as? Video.Type.Episode ?: return

        lifecycleScope.launch {
            val hasNextEpisode = withContext(Dispatchers.IO) {
                EpisodeManager.ensureNextEpisodeAvailable(type, database)
            }

            setupEpisodeNavigationButtons()

            if (!hasNextEpisode) return@launch
            if (autoplay && !UserPreferences.autoplay) return@launch

            viewModel.playNextEpisode()
        }
    }

    private fun decodeBase64Uri(uri: String): String? {
        return try {
            val parts = uri.split(",")
            if (parts.size == 2 && parts[0].contains(";base64")) {
                val base64Data = parts[1]
                val decodedBytes = Base64.getDecoder().decode(base64Data)
                String(decodedBytes, Charsets.UTF_8)
            } else {
                null
            }
        } catch (ignored: Exception) {
            null
        }
    }

    private fun extractUrlFromPlaylist(playlist: String): String? {
        return try {
            val lines = playlist.lines().map { it.trim() }
            lines.firstOrNull { it.startsWith("http") }
                ?: lines.firstNotNullOfOrNull { line ->
                    val regex = """URI=["'](http[^"']+)["']""".toRegex()
                    regex.find(line)?.groupValues?.get(1)
                }
        } catch (ignored: Exception) {
            null
        }
    }


    private fun displayVideo(video: Video, server: Video.Server) {
        currentVideo = video
        currentServer = server
        updatePlayerHeader()

        val extraBuffering = PlayerSettingsView.Settings.ExtraBuffering.isEnabled

        val softwareDecoder = PlayerSettingsView.Settings.SoftwareDecoder.isEnabled
        val needsReinit =
            extraBuffering != currentExtraBuffering || softwareDecoder != currentSoftwareDecoder
        if (needsReinit) {
            initializePlayer(extraBuffering, softwareDecoder)
            player.playlistMetadata = MediaMetadata.Builder()
                .setTitle(resolvePlayerTitle())
                .setMediaServers(servers.map {
                    MediaServer(
                        id = it.id,
                        name = it.name,
                    )
                })
                .build()
        }

        val currentPosition = player.currentPosition

        httpDataSource.setDefaultRequestProperties(
            mapOf(
                "User-Agent" to userAgent,
            ) + (video.headers ?: emptyMap())
        )

        player.setMediaItem(
            MediaItem.Builder()
                .setUri(video.source.toUri())
                .setMimeType(video.type)
                .setSubtitleConfigurations(video.subtitles.map { subtitle ->
                    MediaItem.SubtitleConfiguration.Builder(subtitle.file.toUri())
                        .setMimeType(subtitle.file.toSubtitleMimeType())
                        .setLabel(subtitle.label)
                        .setSelectionFlags(if (subtitle.default) C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                })
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setMediaServerId(server.id)
                        .build()
                )
                .build()
        )

        // WLFMOVIE: External player eliminado por completo (era "abrir con")
        // Toda la lógica de enviar el video a otra app se quitó.

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                super.onPlaybackStateChanged(state)
                // WLFMOVIE: Mostrar/ocultar spinner de buffering personalizado
                updateBufferingState(state == Player.STATE_BUFFERING)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                binding.pvPlayer.keepScreenOn = isPlaying || UserPreferences.keepScreenOnWhenPaused

                if (isPlaying) {
                    startProgressHandler()
                } else {
                    stopProgressHandler()
                }

                val hasUri = player.currentMediaItem?.localConfiguration?.uri
                    ?.toString()?.isNotEmpty()
                    ?: false

                if (!isPlaying && hasUri) {
                    val videoType = args.videoType
                    val watchItem: WatchItem? = when (videoType) {
                        is Video.Type.Movie -> database.movieDao().getById(videoType.id)
                        is Video.Type.Episode -> database.episodeDao().getById(videoType.id)
                    }

                    when {
                        player.hasStarted() && !player.hasFinished() -> {
                            watchItem?.isWatched = false
                            watchItem?.watchedDate = null
                            watchItem?.watchHistory = WatchItem.WatchHistory(
                                lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                                lastPlaybackPositionMillis = player.currentPosition,
                                durationMillis = player.duration,
                            )
                        }

                        player.hasFinished() -> {
                            watchItem?.isWatched = true
                            watchItem?.watchedDate = Calendar.getInstance()
                            watchItem?.watchHistory = null
                        }
                    }

                            when (videoType) {
                                is Video.Type.Movie -> {
                                    val provider = UserPreferences.currentProvider ?: return
                                    val movie = watchItem as? Movie
                                    movie?.let {
                                        database.movieDao().update(it)
                                        UserDataCache.syncMovieToCache(requireContext(), provider, it)
                                    }
                                }

                                is Video.Type.Episode -> {
                                    val provider = UserPreferences.currentProvider ?: return
                                    val episode = watchItem as? Episode
                                    episode?.let {
                                        if (player.hasFinished()) {
                                            database.episodeDao().resetProgressionFromEpisode(videoType.id)
                                            UserDataCache.removeEpisodeFromContinueWatching(requireContext(), provider, it.id)
                                            queueNextEpisodeForContinueWatching(provider)
                                        }
                                        database.episodeDao().update(it)
                                        if (!player.hasFinished()) {
                                            UserDataCache.syncEpisodeToCache(requireContext(), provider, it)
                                        }

                                        it.tvShow?.let { tvShow ->
                                            database.tvShowDao().getById(tvShow.id)
                                        }?.let { tvShow ->
                                            val episodeDao = database.episodeDao()
                                            val isStillWatching = episodeDao.hasAnyWatchHistoryForTvShow(tvShow.id)
                                            
                                            database.tvShowDao().save(tvShow.copy().apply {
                                                merge(tvShow)
                                                isWatching = !player.hasReallyFinished() || isStillWatching
                                            })
                                        }
                                    }
                                }
                            }
                    if (player.hasReallyFinished()) {
                        if (UserPreferences.autoplay) {
                            playNextEpisodeAcrossSeasons(autoplay = true)
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                Log.e("PlayerMobileFragment", "onPlayerError: ", error)
                
                val nextServer = servers.getOrNull(servers.indexOf(currentServer) + 1)
                if (nextServer != null) {
                    Log.i("PlayerMobileFragment", "Playback failed, trying next server: ${nextServer.name}")
                    viewModel.getVideo(nextServer)
                }
            }
        })

        if (currentPosition == 0L) {
            val videoType = args.videoType
            val provider = UserPreferences.currentProvider
            
            val watchItem: WatchItem? = when (videoType) {
                is Video.Type.Movie -> {
                    // Try cache first, then DB
                    var movie = if (provider != null) {
                        UserDataCache.read(requireContext(), provider)?.continueWatchingMovies
                            ?.find { it.id == videoType.id }?.toMovie()
                    } else null
                    movie ?: database.movieDao().getById(videoType.id)
                }
                is Video.Type.Episode -> {
                    // Try cache first, then DB
                    var episode = if (provider != null) {
                        UserDataCache.read(requireContext(), provider)?.continueWatchingEpisodes
                            ?.find { it.id == videoType.id }?.toEpisode()
                    } else null
                    episode ?: database.episodeDao().getById(videoType.id)
                }
            }
            
            val lastPlaybackPositionMillis = watchItem?.watchHistory
                ?.let { it.lastPlaybackPositionMillis - 10.seconds.inWholeMilliseconds }

            player.seekTo(lastPlaybackPositionMillis ?: 0)
        } else {
            player.seekTo(currentPosition)
        }

        player.prepare()
        player.play()
    }

    private fun enterPIPMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.pvPlayer.useController = false
            requireActivity().enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .build()
            )
        }
    }


    private fun ExoPlayer.hasStarted(): Boolean {
        return (this.currentPosition > (this.duration * 0.005) || this.currentPosition > 20.seconds.inWholeMilliseconds)
    }

    private fun ExoPlayer.hasFinished(): Boolean {
        return (this.currentPosition > (this.duration * 0.90))
    }

    private fun ExoPlayer.hasReallyFinished(): Boolean {
        return this.duration > 0 &&
                this.currentPosition >= (this.duration - UserPreferences.autoplayBuffer * 1000)
    }

    private fun currentVideoTypeForUi(): Video.Type = when (val type = args.videoType) {
        is Video.Type.Episode -> EpisodeManager.getCurrentEpisode()
            ?.takeIf { currentEpisode -> currentEpisode.id == type.id }
            ?: type
        is Video.Type.Movie -> type
    }

    private fun resolvePlayerTitle(videoType: Video.Type = currentVideoTypeForUi()): String {
        return when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title.ifBlank { args.title }
        }
    }

    private fun resolvePlayerSubtitle(videoType: Video.Type = currentVideoTypeForUi()): String {
        return when (videoType) {
            is Video.Type.Movie -> args.subtitle
            is Video.Type.Episode -> {
                val episodeTitle = videoType.title?.takeUnless { it.isBlank() } ?: args.subtitle
                "S${videoType.season.number} E${videoType.number}  •  $episodeTitle"
            }
        }
    }

    private fun updatePlayerHeader(videoType: Video.Type = currentVideoTypeForUi()) {
        binding.pvPlayer.controller.binding.tvExoTitle.text = resolvePlayerTitle(videoType)
        binding.pvPlayer.controller.binding.tvExoSubtitle.text = resolvePlayerSubtitle(videoType)
    }

    private fun queueNextEpisodeForContinueWatching(provider: com.mew.wlfmovie.providers.Provider) {
        val nextEpisode = EpisodeManager.peekNextEpisode() ?: return
        val episodeDao = database.episodeDao()
        val persistedNextEpisode = episodeDao.getById(nextEpisode.id)?.apply {
            isWatched = false
            watchedDate = null
            watchHistory = WatchItem.WatchHistory(
                lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                lastPlaybackPositionMillis = 0L,
                durationMillis = 0L,
            )
        } ?: Episode(
            id = nextEpisode.id,
            number = nextEpisode.number,
            title = nextEpisode.title,
            poster = nextEpisode.poster,
            overview = nextEpisode.overview,
            tvShow = database.tvShowDao().getById(nextEpisode.tvShow.id) ?: TvShow(
                id = nextEpisode.tvShow.id,
                title = nextEpisode.tvShow.title,
                poster = nextEpisode.tvShow.poster,
                banner = nextEpisode.tvShow.banner,
            ),
            season = Season(
                number = nextEpisode.season.number,
                title = nextEpisode.season.title,
            ),
        ).apply {
            isWatched = false
            watchedDate = null
            watchHistory = WatchItem.WatchHistory(
                lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                lastPlaybackPositionMillis = 0L,
                durationMillis = 0L,
            )
        }

        episodeDao.save(persistedNextEpisode)
        UserDataCache.syncEpisodeToCache(requireContext(), provider, persistedNextEpisode)
    }
    private fun startProgressHandler() {
        progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        progressRunnable = Runnable {
            if (player.isPlaying) {
                val show = player.currentPosition in 3000..120000
                showSkipIntroButton(show)
                updateNextEpisodeOverlay()

                // WLFMOVIE V4: Guardar progreso cada 10 segundos + auto-upload
                val currentPos = player.currentPosition
                if (currentPos > 0 && currentPos % 10_000 < 1_000) {
                    saveWatchProgress()
                }
            }
            progressHandler.postDelayed(progressRunnable, 1000)
        }
        progressHandler.post(progressRunnable)
    }

    // WLFMOVIE V4: Guarda el progreso de reproducción en la DB local + auto-upload a la nube
    private fun saveWatchProgress() {
        try {
            val videoType = args.videoType
            val position = player.currentPosition
            val duration = player.duration

            if (position <= 0 || duration <= 0) return

            val watchHistory = WatchItem.WatchHistory(
                lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                lastPlaybackPositionMillis = position,
                durationMillis = duration
            )

            when (videoType) {
                is Video.Type.Movie -> {
                    val movie = database.movieDao().getById(videoType.id)
                    movie?.let {
                        it.watchHistory = watchHistory
                        it.isWatched = false
                        database.movieDao().update(it)
                    }
                }
                is Video.Type.Episode -> {
                    val episode = database.episodeDao().getById(videoType.id)
                    episode?.let {
                        it.watchHistory = watchHistory
                        it.isWatched = false
                        database.episodeDao().update(it)
                    }
                }
            }

            // WLFMOVIE V4: Auto-upload a la nube
            com.mew.wlfmovie.utils.SyncManager.autoUpload(requireContext())
        } catch (e: Exception) {
            Log.e("WlfMovie-Player", "Error saveWatchProgress: ${e.message}")
        }
    }

    private fun stopProgressHandler() {
        if (::progressHandler.isInitialized) {
            progressHandler.removeCallbacks(progressRunnable)
        }
    }

    private fun updateNextEpisodeOverlay() {
        val currentEpisode = currentVideoTypeForUi() as? Video.Type.Episode ?: run {
            hideNextEpisodeOverlay()
            return
        }
        val duration = player.duration.takeIf { it > 0 } ?: run {
            hideNextEpisodeOverlay()
            return
        }
        val remainingMs = (duration - player.currentPosition).coerceAtLeast(0L)

        if (nextEpisodeOverlayDismissed) {
            hideNextEpisodeOverlay()
            return
        }

        if (remainingMs <= NEXT_EPISODE_PREFETCH_THRESHOLD_MS) {
            ensureNextEpisodePrepared(currentEpisode)
        }

        val nextEpisode = EpisodeManager.peekNextEpisode()
        val overlayThresholdMs = maxOf(
            NEXT_EPISODE_OVERLAY_MIN_THRESHOLD_MS,
            UserPreferences.autoplayBuffer * 1000L
        )
        if (nextEpisode == null || remainingMs == 0L || remainingMs > overlayThresholdMs) {
            hideNextEpisodeOverlay()
            return
        }

        showNextEpisodeOverlay(nextEpisode, remainingMs)
    }

    private fun ensureNextEpisodePrepared(currentEpisode: Video.Type.Episode) {
        if (EpisodeManager.peekNextEpisode() != null) return
        if (nextEpisodePrefetchTargetId == currentEpisode.id && nextEpisodePrefetchJob?.isActive == true) {
            return
        }

        nextEpisodePrefetchTargetId = currentEpisode.id
        nextEpisodePrefetchJob?.cancel()
        nextEpisodePrefetchJob = lifecycleScope.launch(Dispatchers.IO) {
            val loaded = EpisodeManager.ensureNextEpisodeAvailable(currentEpisode, database)
            withContext(Dispatchers.Main) {
                if (!isAdded || _binding == null) return@withContext
                setupEpisodeNavigationButtons()
                if (loaded && player.isPlaying) {
                    updateNextEpisodeOverlay()
                }
            }
        }
    }

    private fun showNextEpisodeOverlay(nextEpisode: Video.Type.Episode, remainingMs: Long) {
        binding.tvNextEpisodeMeta.text = getString(
            R.string.tv_show_item_season_number_episode_number,
            nextEpisode.season.number,
            nextEpisode.number
        )
        binding.tvNextEpisodeTitle.text = nextEpisode.title
            ?: getString(R.string.episode_number, nextEpisode.number)
        binding.tvNextEpisodeCountdown.text = if (UserPreferences.autoplay) {
            getString(
                R.string.player_next_episode_autoplay_in,
                ((remainingMs + 999L) / 1000L).toInt()
            )
        } else {
            getString(R.string.player_next_episode_ready)
        }

        Glide.with(this)
            .load(nextEpisode.poster ?: nextEpisode.tvShow.poster)
            .error(R.drawable.glide_fallback_cover)
            .fallback(R.drawable.glide_fallback_cover)
            .centerCrop()
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(binding.ivNextEpisodePoster)

        if (binding.layoutNextEpisodeOverlay.isGone) {
            val fadeIn = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in)
            binding.layoutNextEpisodeOverlay.startAnimation(fadeIn)
            binding.layoutNextEpisodeOverlay.isVisible = true
        }
    }

    private fun hideNextEpisodeOverlay() {
        if (_binding == null) return
        if (binding.layoutNextEpisodeOverlay.isVisible) {
            val fadeOut = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.fade_out)
            binding.layoutNextEpisodeOverlay.startAnimation(fadeOut)
            binding.layoutNextEpisodeOverlay.isGone = true
        }
    }

    private fun showSkipIntroButton(show: Boolean) {
        // WLFMOVIE: btnSkipIntro eliminado por completo (omitir intro).
        // Esta función se mantiene vacía para no romper otras llamadas,
        // pero no hace nada.
    }

    // =================================================================
    // WLFMOVIE: Diálogos personalizados para Subtítulos, Audio y Servers
    // =================================================================

    // =================================================================
    // WLFMOVIE: Diálogo personalizado WlfMovie (reemplaza AlertDialog feo)
    // =================================================================

    /**
     * Muestra un diálogo centrado con estilo WlfMovie (gradiente morado-fucsia).
     * Pausa el video, oculta system UI, y reanuda al cerrar.
     */
    private fun showWlfDialog(
        title: String,
        items: List<Pair<String, (() -> Unit)>>
    ) {
        if (items.isEmpty()) return

        // Pausar video mientras el diálogo está abierto
        player.pause()

        val dialog = Dialog(requireContext())
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.wlf_dialog_list, null)

        dialogView.findViewById<TextView>(R.id.tv_dialog_title).text = title

        val container = dialogView.findViewById<LinearLayout>(R.id.ll_dialog_items)
        items.forEach { (label, action) ->
            val item = LayoutInflater.from(requireContext())
                .inflate(R.layout.wlf_dialog_list_item, container, false) as TextView
            item.text = label
            item.setOnClickListener {
                action.invoke()
                dialog.dismiss()
            }
            container.addView(item)
        }

        dialog.setContentView(dialogView)
        // Fondo transparente para que se vea solo nuestro card con gradiente
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // WLFMOVIE: Ocultar system bars usando API moderna (WindowInsetsControllerCompat)
        // Los flags SYSTEM_UI_FLAG_* están deprecados y causan problemas en Android 11+.
        // También aplicamos FLAG_NOT_FOCUSABLE temporalmente para evitar que el diálogo
        // dispare onResume de la Activity (que mostraría las system bars).
        dialog.window?.let { window ->
            // Mantener immersive mode con API moderna
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        // IMPORTANTE: Re-ocultar system bars al cerrar el diálogo (porque el dialog
        // al desaparecer puede causar que Android muestre las system bars)
        dialog.setOnDismissListener {
            // Reanudar video
            player.play()
            // Re-ocultar system bars del player
            try {
                val activity = requireActivity()
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            } catch (e: Exception) {
                Log.e("WlfMovie", "Error re-ocultando system UI: ${e.message}")
            }
        }
        dialog.show()
    }

    private fun showServersDialog() {
        if (servers.isEmpty()) {
            Toast.makeText(requireContext(), "No hay servidores disponibles", Toast.LENGTH_SHORT).show()
            return
        }

        val items = servers.mapIndexed { index, server ->
            "Server ${index + 1} · ${server.name.ifBlank { "Opción ${index + 1}" }}" to {
                Log.i("WlfMovie", "[Player] Server seleccionado: ${server.name}")
                viewModel.getVideo(server)
                binding.pvPlayer.controller.binding.btnExoServers?.text = "Server ${index + 1}"
            }
        }
        showWlfDialog("Servidores", items)
    }

    private fun showSubtitlesDialog() {
        val video = currentVideo
        val items = mutableListOf<Pair<String, (() -> Unit)>>()

        // Opción: Desactivado
        items.add("Desactivado" to {
            try {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            } catch (e: Exception) {
                Log.e("WlfMovie", "Error desactivando subs: ${e.message}")
            }
        })

        // Subtítulos del video
        video?.subtitles?.forEach { sub ->
            items.add(sub.label to {
                try {
                    val subtitleUri = sub.file.toUri()
                    val newMediaItem = player.currentMediaItem?.buildUpon()
                        ?.setSubtitleConfigurations(
                            listOf(
                                MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                                    .setLabel(sub.label)
                                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                    .build()
                            )
                        )
                        ?.build()
                    newMediaItem?.let {
                        val currentPosition = player.currentPosition
                        player.setMediaItem(it, currentPosition)
                        player.prepare()
                        player.play()
                    }
                } catch (e: Exception) {
                    Log.e("WlfMovie", "Error aplicando subtítulo: ${e.message}")
                }
            })
        }

        // Subtítulos locales
        items.add("Cargar archivo local..." to {
            isIgnoringPip = true
            pickLocalSubtitle.launch(
                arrayOf(
                    "text/plain",
                    "text/str",
                    "application/octet-stream",
                    MimeTypes.TEXT_UNKNOWN,
                    MimeTypes.TEXT_VTT,
                    MimeTypes.TEXT_SSA,
                    MimeTypes.APPLICATION_TTML,
                    MimeTypes.APPLICATION_MP4VTT,
                    MimeTypes.APPLICATION_SUBRIP,
                )
            )
        })

        showWlfDialog("Subtítulos", items)
    }

    private fun showAudioDialog() {
        val items = mutableListOf<Pair<String, (() -> Unit)>>()

        // Pistas de audio del video actual (si hay múltiples)
        try {
            val audioTracks = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            if (audioTracks.isNotEmpty()) {
                audioTracks.forEachIndexed { index, group ->
                    if (group.length > 0) {
                        val name = group.getTrackFormat(0)?.label ?: "Pista ${index + 1}"
                        items.add(name to {
                            try {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                    .build()
                            } catch (e: Exception) {
                                Log.e("WlfMovie", "Error audio: ${e.message}")
                            }
                        })
                    }
                }
            } else {
                items.add("Audio por defecto" to { })
            }
        } catch (e: Exception) {
            items.add("Audio por defecto" to { })
        }

        showWlfDialog("Audio", items)
    }

    // =================================================================
    // WLFMOVIE: Spinner de buffering personalizado (morado-fucsia)
    // =================================================================

    private var wlfBufferingSpinner: ProgressBar? = null

    private fun setupCustomBufferingIndicator() {
        try {
            // Deshabilitar el spinner de buffering por defecto de ExoPlayer
            binding.pvPlayer.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)

            // Crear nuestro propio ProgressBar con el drawable morado-fucsia
            val spinner = ProgressBar(requireContext()).apply {
                id = View.generateViewId()
                indeterminateDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.wlf_loading_spinner)
                visibility = View.GONE
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    56.dp(requireContext()),
                    56.dp(requireContext()),
                    android.view.Gravity.CENTER
                )
            }

            // Agregar al PlayerView (que es un FrameLayout internamente)
            (binding.pvPlayer as? ViewGroup)?.addView(spinner)
            wlfBufferingSpinner = spinner
        } catch (e: Exception) {
            Log.e("WlfMovie", "Error setup spinner: ${e.message}")
        }
    }

    private fun updateBufferingState(isBuffering: Boolean) {
        wlfBufferingSpinner?.visibility = if (isBuffering) View.VISIBLE else View.GONE
    }

    // =================================================================
    // WLFMOVIE: Gestures para barras de brillo y volumen
    // =================================================================

    private var wlfAudioManager: AudioManager? = null
    private var wlfMaxVolume = 0

    private fun setupBrightnessVolumeGestures() {
        wlfAudioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        wlfMaxVolume = wlfAudioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15

        // === BARRA DE BRILLO (izquierda) ===
        val brightnessContainer = binding.pvPlayer.controller.binding.llBrightness
        brightnessContainer?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val height = brightnessContainer.height.toFloat()
                    if (height > 0) {
                        // Y=0 arriba (100%), Y=height abajo (0%)
                        val ratio = 1f - (event.y / height).coerceIn(0f, 1f)
                        updateBrightness(ratio)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    brightnessContainer.performClick()
                    true
                }
                else -> false
            }
        }

        // === BARRA DE VOLUMEN (derecha) ===
        val volumeContainer = binding.pvPlayer.controller.binding.llVolume
        volumeContainer?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val height = volumeContainer.height.toFloat()
                    if (height > 0) {
                        val ratio = 1f - (event.y / height).coerceIn(0f, 1f)
                        updateVolume(ratio)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    volumeContainer.performClick()
                    true
                }
                else -> false
            }
        }

        // Inicializar valores mostrados
        initBrightnessDisplay()
        initVolumeDisplay()
    }

    private fun updateBrightness(ratio: Float) {
        val percent = (ratio * 100).toInt().coerceIn(0, 100)
        try {
            // WLFMOVIE: Actualizar UI PRIMERO (siempre funciona)
            binding.pvPlayer.controller.binding.tvBrightnessPercentage?.text = "$percent%"
            val progressView = binding.pvPlayer.controller.binding.vBrightnessProgress
            progressView?.let {
                val params2 = it.layoutParams as ConstraintLayout.LayoutParams
                params2.matchConstraintPercentHeight = ratio
                it.layoutParams = params2
                it.requestLayout()
            }

            // Aplicar brillo a la ventana (esto sí funciona sin permisos)
            val window = requireActivity().window
            val params = window.attributes
            params.screenBrightness = ratio
            window.attributes = params

            // NOTA: No usamos Settings.System.putInt porque requiere WRITE_SETTINGS
            // permission que no tenemos. Con window.attributes.screenBrightness es suficiente
            // para el player.
        } catch (e: Exception) {
            Log.e("WlfMovie", "Error brillo: ${e.message}")
        }
    }

    private fun updateVolume(ratio: Float) {
        val percent = (ratio * 100).toInt().coerceIn(0, 100)
        try {
            val newVolume = (ratio * wlfMaxVolume).toInt().coerceIn(0, wlfMaxVolume)
            wlfAudioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

            // Actualizar UI
            binding.pvPlayer.controller.binding.tvVolumePercentage?.text = "$percent%"
            val progressView = binding.pvPlayer.controller.binding.vVolumeProgress
            progressView?.let {
                val params = it.layoutParams as ConstraintLayout.LayoutParams
                params.matchConstraintPercentHeight = ratio
                it.layoutParams = params
                it.requestLayout()
            }
        } catch (e: Exception) {
            Log.e("WlfMovie", "Error volumen: ${e.message}")
        }
    }

    private fun initBrightnessDisplay() {
        try {
            val current = Settings.System.getInt(
                requireContext().contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            val ratio = current / 255f
            binding.pvPlayer.controller.binding.tvBrightnessPercentage?.text = "${(ratio * 100).toInt()}%"
            val progressView = binding.pvPlayer.controller.binding.vBrightnessProgress
            progressView?.let {
                val params = it.layoutParams as ConstraintLayout.LayoutParams
                params.matchConstraintPercentHeight = ratio
                it.layoutParams = params
                it.requestLayout()
            }
        } catch (_: Exception) { }
    }

    private fun initVolumeDisplay() {
        try {
            val current = wlfAudioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
            val ratio = if (wlfMaxVolume > 0) current.toFloat() / wlfMaxVolume else 0f
            binding.pvPlayer.controller.binding.tvVolumePercentage?.text = "${(ratio * 100).toInt()}%"
            val progressView = binding.pvPlayer.controller.binding.vVolumeProgress
            progressView?.let {
                val params = it.layoutParams as ConstraintLayout.LayoutParams
                params.matchConstraintPercentHeight = ratio
                it.layoutParams = params
                it.requestLayout()
            }
        } catch (_: Exception) { }
    }



    override fun onPause() {
        super.onPause()
        stopProgressHandler()
        hideNextEpisodeOverlay()
    }

    private var currentExtraBuffering = false
    private var currentSoftwareDecoder = false

    private fun buildPlayer(extraBuffering: Boolean): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                if (extraBuffering) 300_000 else DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()

        val baseBuilder = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1 && !currentSoftwareDecoder) {
            ExoPlayer.Builder(requireContext())
        } else {
            val renderersFactory = DefaultRenderersFactory(requireContext()).apply {
                setEnableDecoderFallback(true)
                if (currentSoftwareDecoder) {
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                }
            }
            ExoPlayer.Builder(requireContext(), renderersFactory)
        }

        return baseBuilder
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
    }

    private fun initializePlayer(extraBuffering: Boolean, softwareDecoder: Boolean = currentSoftwareDecoder) {
        releasePlayer()
        currentExtraBuffering = extraBuffering
        currentSoftwareDecoder = softwareDecoder

        var tokenLogged = false
        val okHttpClient = NetworkClient.default.newBuilder()
            .addInterceptor { chain ->
                var request = chain.request()
                
                if (currentVideo?.maintainToken == true) {
                    val latestQuery = TokenManager.latestQuery
                    if (latestQuery != null) {
                        val origHttpUrl = request.url
                        val updatedHttpUrl = origHttpUrl.newBuilder().query(latestQuery).build()
                        request = request.newBuilder().url(updatedHttpUrl).build()
                        if (!tokenLogged) {
                            android.util.Log.d("TokenManager", "[MOBILE-INTERCEPTOR] Token successfully injected (applied to all segments)")
                            tokenLogged = true
                        }
                    } else {
                        android.util.Log.w("TokenManager", "[MOBILE-INTERCEPTOR] maintainToken=true but latestQuery is null! URL: ${request.url.host}")
                    }
                }
                
                chain.proceed(request)
            }
            .build()
        httpDataSource = OkHttpDataSource.Factory(okHttpClient)

        dataSourceFactory = DefaultDataSource.Factory(requireContext(), httpDataSource)

        player = buildPlayer(extraBuffering).also { player ->
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )

                val lang = UserPreferences.currentProvider?.language?.substringBefore("-")
                if (lang == "es") {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setPreferredAudioLanguage("spa")
                        .build()
                }

                mediaSession = MediaSession.Builder(requireContext(), player)
                    .build()
            }

        binding.pvPlayer.player = player
        binding.settings.player = player
        binding.settings.subtitleView = binding.pvPlayer.subtitleView
        binding.settings.onSubtitlesClicked = {
            viewModel.getSubtitles(args.videoType)
        }
    }

    private fun releasePlayer() {
        stopProgressHandler()
        binding.pvPlayer.player = null
        binding.settings.player = null
        binding.settings.subtitleView = null
        if (::player.isInitialized) {
            player.release()
        }
        if (::mediaSession.isInitialized) {
            mediaSession.release()
        }
    }

    private fun isSerienStreamBypassUrl(url: String): Boolean {
        return runCatching {
            Uri.parse(url).host.equals("serienstream.to", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun buildSerienStreamBypassUrl(): String? {
        val provider = UserPreferences.currentProvider ?: return null
        if (provider != SerienStreamProvider) return null

        val episodeId = when (val type = args.videoType) {
            is Video.Type.Episode -> type.id
            is Video.Type.Movie -> return null
        }

        return "${SerienStreamProvider.baseUrl}serie/$episodeId"
    }

    private fun applyBypassCookies(url: String, cookieHeader: String) {
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        val targets = linkedSetOf<String>().apply {
            add(url)
            if (host.isNotBlank()) {
                add("https://$host/")
                add("http://$host/")
            }
        }

        val cookieManager = CookieManager.getInstance()
        cookieHeader.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .forEach { cookie ->
                targets.forEach { target ->
                    cookieManager.setCookie(target, cookie)
                }
            }
        cookieManager.flush()
    }
}
