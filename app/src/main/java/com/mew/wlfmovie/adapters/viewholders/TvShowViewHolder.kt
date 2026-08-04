package com.mew.wlfmovie.adapters.viewholders

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.Toast
import android.util.Log
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.mew.wlfmovie.providers.IptvProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.mew.wlfmovie.R
import com.mew.wlfmovie.adapters.AppAdapter
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.databinding.*
import com.mew.wlfmovie.fragments.home.HomeTvFragment
import com.mew.wlfmovie.fragments.home.HomeTvFragmentDirections
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import android.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mew.wlfmovie.fragments.movie.MovieMobileFragmentDirections
import com.mew.wlfmovie.fragments.tv_show.TvShowMobileFragmentDirections
import com.mew.wlfmovie.fragments.tv_show.TvShowTvFragmentDirections
import com.mew.wlfmovie.fragments.movies.MoviesMobileFragmentDirections
import com.mew.wlfmovie.fragments.movies.MoviesTvFragmentDirections
import com.mew.wlfmovie.fragments.search.SearchMobileFragmentDirections
import com.mew.wlfmovie.fragments.search.SearchTvFragmentDirections
import com.mew.wlfmovie.fragments.genre.GenreMobileFragmentDirections
import com.mew.wlfmovie.fragments.genre.GenreTvFragmentDirections
import com.mew.wlfmovie.fragments.people.PeopleMobileFragmentDirections
import com.mew.wlfmovie.fragments.people.PeopleTvFragmentDirections
import com.mew.wlfmovie.fragments.home.HomeMobileFragmentDirections
import com.mew.wlfmovie.models.Episode
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.models.Season
import com.mew.wlfmovie.models.TvShow
import com.mew.wlfmovie.models.Video
import com.mew.wlfmovie.ui.SpacingItemDecoration
import com.mew.wlfmovie.ui.ShowOptionsMobileDialog
import com.mew.wlfmovie.ui.ShowOptionsTvDialog
import com.mew.wlfmovie.utils.UserPreferences
import com.mew.wlfmovie.utils.format
import com.mew.wlfmovie.utils.toActivity
import com.mew.wlfmovie.utils.getCurrentFragment
import com.mew.wlfmovie.utils.dp
import com.mew.wlfmovie.utils.loadTvShowBanner
import com.mew.wlfmovie.utils.loadTvShowPoster
import com.mew.wlfmovie.utils.ArtworkRepair
import com.mew.wlfmovie.providers.Provider
import java.util.Locale

class TvShowViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private val database: AppDatabase
        get() = AppDatabase.getInstance(context)
    private lateinit var tvShow: TvShow

    val childRecyclerView: RecyclerView?
        get() = when (_binding) {
            is ContentTvShowSeasonsMobileBinding -> _binding.rvTvShowSeasons
            is ContentTvShowSeasonsTvBinding -> _binding.hgvTvShowSeasons
            is ContentTvShowCastMobileBinding -> _binding.rvTvShowCast
            is ContentTvShowCastTvBinding -> _binding.hgvTvShowCast
            is ContentTvShowRecommendationsMobileBinding -> _binding.rvTvShowRecommendations
            is ContentTvShowRecommendationsTvBinding -> _binding.hgvTvShowRecommendations
            else -> null
        }

    fun bind(tvShow: TvShow) {
        this.tvShow = tvShow

        when (_binding) {
            is ItemTvShowMobileBinding -> displayMobileItem(_binding)
            is ItemTvShowTvBinding -> displayTvItem(_binding)
            is ItemTvShowGridMobileBinding -> displayGridMobileItem(_binding)
            is ItemTvShowGridBinding -> displayGridTvItem(_binding)
            is ItemCategorySwiperMobileBinding -> displaySwiperMobileItem(_binding)

            is ContentTvShowMobileBinding -> displayTvShowMobile(_binding)
            is ContentTvShowTvBinding -> displayTvShowTv(_binding)
            is ContentTvShowSeasonsMobileBinding -> displaySeasonsMobile(_binding)
            is ContentTvShowSeasonsTvBinding -> displaySeasonsTv(_binding)
            is ContentTvShowDirectorsMobileBinding -> displayDirectorsMobile(_binding)
            is ContentTvShowDirectorsTvBinding -> displayDirectorsTv(_binding)
            is ContentTvShowCastMobileBinding -> displayCastMobile(_binding)
            is ContentTvShowCastTvBinding -> displayCastTv(_binding)
            is ContentTvShowRecommendationsMobileBinding -> displayRecommendationsMobile(_binding)
            is ContentTvShowRecommendationsTvBinding -> displayRecommendationsTv(_binding)
        }
    }

    private fun isIptvProvider(): Boolean {
        val name = tvShow.providerName ?: UserPreferences.currentProvider?.name ?: ""
        val provider = Provider.providers.keys.find { it.name == name }
        return provider is IptvProvider
    }

    private fun checkProviderAndRun(action: () -> Unit) {
        if (!tvShow.providerName.isNullOrBlank() && tvShow.providerName != UserPreferences.currentProvider?.name) {
            Provider.providers.keys.find { it.name == tvShow.providerName }?.let {
                UserPreferences.currentProvider = it
            }
        }
        action()
    }

    private fun handleDirectPlay(navController: NavController) {
        val videoType = Video.Type.Episode(
            id = tvShow.id,
            number = 1,
            title = tvShow.title,
            poster = tvShow.poster,
            overview = tvShow.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = tvShow.id,
                title = tvShow.title,
                poster = tvShow.poster,
                banner = tvShow.banner,
                releaseDate = tvShow.released?.format("yyyy-MM-dd"),
                imdbId = tvShow.imdbId,
            ),
            season = Video.Type.Episode.Season(
                number = 1,
                title = "Live",
            ),
        )

        val args = Bundle().apply {
            putString("id", tvShow.id)
            putString("title", tvShow.title)
            putString("subtitle", tvShow.title)
            putSerializable("videoType", videoType)
        }
        navController.navigate(R.id.player, args)
    }

    private fun tvShowArgs(): Bundle {
        return Bundle().apply {
            putString("id", tvShow.id)
            putString("poster", tvShow.poster)
            putString("banner", tvShow.banner)
        }
    }

    private fun resolveEpisodeSeason(episode: Episode?): Season? {
        if (episode == null) return null

        val currentSeason = episode.season
        val seasonKey = episode.id.substringBeforeLast("/", "")
            .takeIf { it.isNotBlank() }
        if (currentSeason != null && currentSeason.number != 0) {
            return currentSeason
        }

        return tvShow.seasons.firstOrNull { season ->
            season.id == seasonKey ||
                season.id == currentSeason?.id ||
                season.episodes.any { it.id == episode.id } ||
                (episode.number != 0 && season.episodes.any { it.number == episode.number && it.title == episode.title })
        } ?: currentSeason
    }

    private fun setPoster(imageView: ImageView) {
        imageView.scaleType = if (isIptvProvider()) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        imageView.loadTvShowPoster(tvShow) {
            fallback(R.drawable.glide_fallback_cover)
            transition(DrawableTransitionOptions.withCrossFade())
        }
    }

    private fun displayMobileItem(binding: ItemTvShowMobileBinding) {
        binding.root.setOnClickListener {
            checkProviderAndRun {
                if (isIptvProvider()) {
                    handleDirectPlay(binding.root.findNavController())
                } else {
                    binding.root.findNavController().navigate(R.id.tv_show, tvShowArgs())
                }
            }
        }
        binding.root.setOnLongClickListener { true } // WLFMOVIE: long-click deshabilitado
        setPoster(binding.ivTvShowPoster)
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            isVisible = !text.isNullOrEmpty()
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }
        binding.tvTvShowLastEpisode.text = if (isIptvProvider()) "" else tvShow.seasons.lastOrNull()?.episodes?.lastOrNull()?.let { "E${it.number}" } ?: tvShow.released?.format("yyyy") ?: context.getString(R.string.tv_show_item_type)
        binding.tvTvShowTitle.text = tvShow.title
    }

    private fun displayTvItem(binding: ItemTvShowTvBinding) {
        binding.root.apply {
            setOnClickListener {
                checkProviderAndRun {
                    if (isIptvProvider()) {
                        handleDirectPlay(findNavController())
                    } else {
                        findNavController().navigate(R.id.tv_show, tvShowArgs())
                    }
                }
            }
            binding.root.setOnLongClickListener { true } // WLFMOVIE: long-click deshabilitado
            setOnFocusChangeListener { _, hasFocus ->
                val animation = if (hasFocus) AnimationUtils.loadAnimation(context, R.anim.zoom_in) else AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                startAnimation(animation)
                animation.fillAfter = true
                (context.toActivity()?.getCurrentFragment() as? HomeTvFragment)?.let { fragment ->
                    if (hasFocus) {
                        fragment.pinBackground(tvShow.banner)
                    } else {
                        fragment.releasePinnedBackground()
                    }
                }
            }
        }
        setPoster(binding.ivTvShowPoster)
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            isVisible = !text.isNullOrEmpty()
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }
        binding.tvTvShowLastEpisode.text = if (isIptvProvider()) "" else tvShow.seasons.lastOrNull()?.episodes?.lastOrNull()?.let { "E${it.number}" } ?: tvShow.released?.format("yyyy") ?: context.getString(R.string.tv_show_item_type)
        binding.tvTvShowTitle.text = tvShow.title
    }

    private fun displayGridMobileItem(binding: ItemTvShowGridMobileBinding) {
        binding.root.setOnClickListener {
            checkProviderAndRun {
                if (isIptvProvider()) {
                    handleDirectPlay(binding.root.findNavController())
                } else {
                    binding.root.findNavController().navigate(R.id.tv_show, tvShowArgs())
                }
            }
        }
        binding.root.setOnLongClickListener { true } // WLFMOVIE: long-click deshabilitado
        setPoster(binding.ivTvShowPoster)
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            isVisible = !text.isNullOrEmpty()
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }
        binding.tvTvShowLastEpisode.text = if (isIptvProvider()) "" else tvShow.seasons.lastOrNull()?.episodes?.lastOrNull()?.let { "E${it.number}" } ?: tvShow.released?.format("yyyy") ?: context.getString(R.string.tv_show_item_type)
        binding.tvTvShowTitle.text = tvShow.title
    }

    private fun displayGridTvItem(binding: ItemTvShowGridBinding) {
        binding.root.apply {
            setOnClickListener {
                checkProviderAndRun {
                    if (isIptvProvider()) {
                        handleDirectPlay(findNavController())
                    } else {
                        findNavController().navigate(R.id.tv_show, tvShowArgs())
                    }
                }
            }
            binding.root.setOnLongClickListener { true } // WLFMOVIE: long-click deshabilitado
            setOnFocusChangeListener { _, hasFocus ->
                val animation = if (hasFocus) AnimationUtils.loadAnimation(context, R.anim.zoom_in) else AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                startAnimation(animation)
                animation.fillAfter = true
            }
        }
        setPoster(binding.ivTvShowPoster)
        binding.tvTvShowQuality.apply {
            text = tvShow.quality ?: ""
            isVisible = !text.isNullOrEmpty()
        }
        binding.pbTvShowProgress.apply {
            val watchHistory = tvShow.episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null && watchHistory.durationMillis > 0 ->
                    (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }
        binding.tvTvShowLastEpisode.text = if (isIptvProvider()) "" else tvShow.seasons.lastOrNull()?.episodes?.lastOrNull()?.let { "E${it.number}" } ?: tvShow.released?.format("yyyy") ?: context.getString(R.string.tv_show_item_type)
        binding.tvTvShowTitle.text = tvShow.title
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun getInstalledSmartTubePackages(): List<String> {
        val installed = mutableListOf<String>()
        if (isPackageInstalled("org.smarttube.stable")) installed.add("org.smarttube.stable")
        if (isPackageInstalled("org.smarttube.beta")) installed.add("org.smarttube.beta")
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
            if (pkg == "org.smarttube.stable") context.getString(R.string.smarttube_stable)
            else context.getString(R.string.smarttube_beta)
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.choose_smarttube_version))
            .setItems(items) { _, which ->
                val selectedPackage = packages[which]

                if (shouldSavePreference) {
                    editor.putString("preferred_smarttube_package", selectedPackage).apply()
                }

                launchSmartTube(selectedPackage, trailerUrl)
            }.show()
    }

    private fun handleSmartTubeSelection(trailerUrl: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val savedPackage = prefs.getString("preferred_smarttube_package", null)
        val stPackages = getInstalledSmartTubePackages()

        if (stPackages.isEmpty()) {
            context.startActivity(Intent(Intent.ACTION_VIEW, trailerUrl.toUri()))
            return
        }

        if (stPackages.size == 1) {
            launchSmartTube(stPackages[0], trailerUrl)
            return
        }

        if (savedPackage != null && stPackages.contains(savedPackage)) {
            launchSmartTube(savedPackage, trailerUrl)
        } else {
            showSmartTubeVersionDialog(stPackages, trailerUrl, true)
        }
    }

    private fun safeLaunchYoutube(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("TvShowViewHolder", "Failed to launch YouTube intent", e)
            Toast.makeText(context, context.getString(R.string.player_external_player_error_video), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleTrailerClick(trailer: String) {
        val youtubeIntent = Intent(Intent.ACTION_VIEW, trailer.toUri())
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val preferredPlayer = prefs.getString("preferred_player", "ask")

        when (preferredPlayer) {
            "smarttube" -> {
                handleSmartTubeSelection(trailer)
            }
            "smarttube_stable" -> {
                launchSmartTube("org.smarttube.stable", trailer)
            }
            "smarttube_beta" -> {
                launchSmartTube("org.smarttube.beta", trailer)
            }
            "youtube" -> {
                safeLaunchYoutube(youtubeIntent)
            }
            else -> {
                val stPackages = getInstalledSmartTubePackages()
                if (stPackages.isNotEmpty()) {
                    AlertDialog.Builder(context)
                        .setTitle(context.getString(R.string.watch_trailer_with))
                        .setItems(arrayOf(context.getString(R.string.youtube), context.getString(R.string.smarttube))) { _, which ->
                            if (which == 0) {
                                safeLaunchYoutube(youtubeIntent)
                            } else {
                                if (stPackages.size > 1) {
                                    showSmartTubeVersionDialog(stPackages, trailer, false)
                                } else {
                                    launchSmartTube(stPackages[0], trailer)
                                }
                            }
                        }.show()
                } else {
                    safeLaunchYoutube(youtubeIntent)
                }
            }
        }
    }

    private fun displaySwiperMobileItem(binding: ItemCategorySwiperMobileBinding) {
        binding.ivSwiperBackground.loadTvShowBanner(tvShow) {
            centerCrop().transition(DrawableTransitionOptions.withCrossFade())
        }
        binding.tvSwiperTitle.text = tvShow.title
        binding.tvSwiperTvShowLastEpisode.text = if (isIptvProvider()) "" else tvShow.seasons.lastOrNull()?.episodes?.lastOrNull()?.let { "E${it.number}" } ?: context.getString(R.string.tv_show_item_type)

        binding.tvSwiperQuality.apply {
            text = tvShow.quality
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvSwiperReleased.apply {
            text = tvShow.released?.format("yyyy")
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvSwiperRating.apply {
            text = tvShow.rating?.let { String.format(Locale.ROOT, "%.1f", it) }
            isVisible = !text.isNullOrEmpty()
        }
        binding.ivSwiperRatingIcon.isVisible = binding.tvSwiperRating.isVisible

        binding.tvSwiperOverview.text = tvShow.overview
        binding.btnSwiperWatchNow.setOnClickListener {
            if (isIptvProvider()) {
                handleDirectPlay(binding.root.findNavController())
            } else {
                binding.root.findNavController().navigate(R.id.tv_show, tvShowArgs())
            }
        }
    }

    private fun displayTvShowMobile(binding: ContentTvShowMobileBinding) {
        // WLFMOVIE: Backdrop 16:9 (sin WebView - YouTube bloquea embeds en WebView)
        binding.ivTvShowPoster.run {
            loadTvShowBanner(tvShow) {
                fallback(R.drawable.glide_fallback_cover)
                transition(DrawableTransitionOptions.withCrossFade())
            }
            visibility = if (tvShow.banner.isNullOrEmpty() && tvShow.poster.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        binding.tvTvShowTitle.text = tvShow.title

        binding.tvTvShowRating.apply {
            text = tvShow.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"
            isVisible = !text.isNullOrEmpty()
        }
        binding.ivTvShowRatingIcon.isVisible = binding.tvTvShowRating.isVisible

        binding.tvTvShowQuality.apply {
            text = tvShow.quality
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowReleased.apply {
            text = tvShow.released?.format("yyyy")
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowRuntime.apply {
            text = tvShow.runtime?.let {
                val hours = it / 60
                val minutes = it % 60
                when {
                    hours > 0 -> context.getString(R.string.tv_show_runtime_hours_minutes, hours, minutes)
                    else -> context.getString(R.string.tv_show_runtime_minutes, minutes)
                }
            }
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowGenres.apply {
            text = tvShow.genres.joinToString(", ") { it.name }
            isVisible = tvShow.genres.isNotEmpty()
        }

        binding.tvTvShowOverview.text = tvShow.overview
        val episodeToWatch = tvShow.episodeToWatch
        val episodeSeason = resolveEpisodeSeason(episodeToWatch)
        binding.btnTvShowWatchNow.apply {
            isVisible = episodeToWatch != null
            setOnClickListener {
                if (isIptvProvider()) {
                    handleDirectPlay(findNavController())
                } else {
                    val videoType = Video.Type.Episode(
                        id = episodeToWatch!!.id,
                        number = episodeToWatch.number,
                        title = episodeToWatch.title,
                        poster = episodeToWatch.poster,
                        overview = episodeToWatch.overview,
                        tvShow = Video.Type.Episode.TvShow(
                            id = tvShow.id,
                            title = tvShow.title,
                            poster = tvShow.poster,
                            banner = tvShow.banner,
                            releaseDate = tvShow.released?.format("yyyy-MM-dd"),
                            imdbId = tvShow.imdbId,
                        ),
                        season = Video.Type.Episode.Season(
                            number = episodeSeason?.number ?: 1,
                            title = episodeSeason?.title ?: "",
                        ),
                    )
                    val args = Bundle().apply {
                        putString("id", episodeToWatch.id)
                        putString("title", tvShow.title)
                        putString("subtitle", "S${videoType.season.number} E${videoType.number}  •  ${videoType.title}")
                        putSerializable("videoType", videoType)
                    }
                    findNavController().navigate(R.id.player, args)
                }
            }
            text = if (isIptvProvider()) context.getString(R.string.movie_watch_now) else context.getString(R.string.tv_show_watch_season_episode, episodeSeason?.number ?: 1, episodeToWatch?.number ?: 1)
        }

        binding.pbTvShowProgressEpisode.apply {
            val watchHistory = episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }

        binding.btnTvShowTrailer.apply {
            val trailer = tvShow.trailer
            setOnClickListener {
                if (trailer != null) handleTrailerClick(trailer)
            }
            isVisible = trailer != null
        }


        // WLFMOVIE: Botón "Ver temporadas".
        // 1 temporada  → abre directamente el diálogo de episodios (formato lista texto).
        // N temporadas → abre el selector de temporadas; al click, abre el diálogo de episodios.
        // Nunca usamos SeasonFragment (que muestra imágenes) — ese es el formato viejo.
        binding.btnTvShowSeasons?.apply {
            isVisible = tvShow.seasons.isNotEmpty()
            setOnClickListener {
                if (tvShow.seasons.size == 1) {
                    showEpisodesDialog(tvShow.seasons.first())
                } else {
                    showSeasonsDialog()
                }
            }
        }

binding.btnTvShowFavorite.apply {
            fun Boolean.drawable() = when (this) {
                true -> R.drawable.ic_favorite_enable
                false -> R.drawable.ic_favorite_disable
            }

            setOnClickListener {
                checkProviderAndRun {
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.tvShowDao()
                        val current = dao.getById(tvShow.id)?.isFavorite ?: false
                        val newValue = !current
                        val resolvedTvShow = ArtworkRepair.resolveTvShowForFavorite(context, tvShow, newValue)

                        dao.upsertFavorite(resolvedTvShow, newValue)

                        withContext(Dispatchers.Main) {
                            tvShow.poster = resolvedTvShow.poster
                            tvShow.banner = resolvedTvShow.banner
                            tvShow.isFavorite = newValue
                            setImageDrawable(
                                ContextCompat.getDrawable(context, newValue.drawable())
                            )
                        }
                    }
                }
            }

            setImageDrawable(
                ContextCompat.getDrawable(context, tvShow.isFavorite.drawable())
            )
        }
    }

    private fun displayTvShowTv(binding: ContentTvShowTvBinding) {
        // WLFMOVIE: Cargar backdrop (banner horizontal) en vez de poster vertical.
        binding.ivTvShowPoster.run {
            loadTvShowBanner(tvShow) {
                fallback(R.drawable.glide_fallback_cover)
                transition(DrawableTransitionOptions.withCrossFade())
            }
            visibility = if (tvShow.banner.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        binding.tvTvShowTitle.text = tvShow.title

        binding.tvTvShowRating.apply {
            text = tvShow.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"
            isVisible = !text.isNullOrEmpty()
        }
        binding.ivTvShowRatingIcon.isVisible = binding.tvTvShowRating.isVisible

        binding.tvTvShowQuality.apply {
            text = tvShow.quality
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowReleased.apply {
            text = tvShow.released?.format("yyyy")
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowRuntime.apply {
            text = tvShow.runtime?.let {
                val hours = it / 60
                val minutes = it % 60
                when {
                    hours > 0 -> context.getString(R.string.tv_show_runtime_hours_minutes, hours, minutes)
                    else -> context.getString(R.string.tv_show_runtime_minutes, minutes)
                }
            }
            isVisible = !text.isNullOrEmpty()
        }

        binding.tvTvShowGenres.apply {
            text = tvShow.genres.joinToString(", ") { it.name }
            isVisible = tvShow.genres.isNotEmpty()
        }

        binding.tvTvShowOverview.text = tvShow.overview
        val episodeToWatch = tvShow.episodeToWatch
        val episodeSeason = resolveEpisodeSeason(episodeToWatch)
        binding.btnTvShowWatchNow.apply {
            isVisible = episodeToWatch != null
            setOnClickListener {
                if (isIptvProvider()) {
                    handleDirectPlay(findNavController())
                } else {
                    val videoType = Video.Type.Episode(
                        id = episodeToWatch!!.id,
                        number = episodeToWatch.number,
                        title = episodeToWatch.title,
                        poster = episodeToWatch.poster,
                        overview = episodeToWatch.overview,
                        tvShow = Video.Type.Episode.TvShow(
                            id = tvShow.id,
                            title = tvShow.title,
                            poster = tvShow.poster,
                            banner = tvShow.banner,
                            releaseDate = tvShow.released?.format("yyyy-MM-dd"),
                            imdbId = tvShow.imdbId,
                        ),
                        season = Video.Type.Episode.Season(
                            number = episodeSeason?.number ?: 1,
                            title = episodeSeason?.title ?: "",
                        ),
                    )
                    val args = Bundle().apply {
                        putString("id", episodeToWatch.id)
                        putString("title", tvShow.title)
                        putString("subtitle", "S${videoType.season.number} E${videoType.number}  •  ${videoType.title}")
                        putSerializable("videoType", videoType)
                    }
                    findNavController().navigate(R.id.player, args)
                }
            }
            text = if (isIptvProvider()) context.getString(R.string.movie_watch_now) else context.getString(R.string.tv_show_watch_season_episode, episodeSeason?.number ?: 1, episodeToWatch?.number ?: 1)
        }

        binding.pbTvShowProgressEpisode.apply {
            val watchHistory = episodeToWatch?.watchHistory
            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            isVisible = watchHistory != null
        }

        // WLFMOVIE: Texto "Visto S1 E2 · 23 min" en TV (reemplaza barra).
        binding.tvTvShowProgressText?.apply {
            val watchHistory = episodeToWatch?.watchHistory
            if (watchHistory != null && watchHistory.durationMillis > 0) {
                val watchedMin = (watchHistory.lastPlaybackPositionMillis / 60000).toInt()
                val seasonNum = episodeSeason?.number ?: 1
                val episodeNum = episodeToWatch?.number ?: 1
                text = "Visto S${seasonNum} E${episodeNum} · ${watchedMin} min"
                isVisible = true
            } else {
                isVisible = false
            }
        }

        // WLFMOVIE: Tráiler oculto en TV (igual que en mobile).
        binding.btnTvShowTrailer.apply {
            val trailer = tvShow.trailer
            setOnClickListener {
                if (trailer != null) handleTrailerClick(trailer)
            }
            isVisible = false
        }

        // WLFMOVIE: Botón "Ver temporadas" — igual que displayTvShowMobile.
        // 1 temporada  → abre directamente el diálogo de episodios (formato lista texto).
        // N temporadas → abre el selector de temporadas; al click, abre el diálogo de episodios.
        // Nunca usamos SeasonFragment (que muestra imágenes) — ese es el formato viejo.
        binding.btnTvShowSeasons?.apply {
            isVisible = tvShow.seasons.isNotEmpty()
            setOnClickListener {
                if (tvShow.seasons.size == 1) {
                    showEpisodesDialog(tvShow.seasons.first())
                } else {
                    showSeasonsDialog()
                }
            }
        }

        // WLFMOVIE: Favorito oculto en TV — lo maneja el overlay del fragment.
        binding.btnTvShowFavorite.apply {
            fun Boolean.drawable() = when (this) {
                true -> R.drawable.ic_favorite_enable
                false -> R.drawable.ic_favorite_disable
            }

            setOnClickListener {
                checkProviderAndRun {
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.tvShowDao()
                        val current = dao.getById(tvShow.id)?.isFavorite ?: false
                        val newValue = !current
                        val resolvedTvShow = ArtworkRepair.resolveTvShowForFavorite(context, tvShow, newValue)

                        dao.upsertFavorite(resolvedTvShow, newValue)

                        withContext(Dispatchers.Main) {
                            tvShow.poster = resolvedTvShow.poster
                            tvShow.banner = resolvedTvShow.banner
                            tvShow.isFavorite = newValue
                            setImageDrawable(
                                ContextCompat.getDrawable(context, newValue.drawable())
                            )
                        }
                    }
                }
            }

            setImageDrawable(
                ContextCompat.getDrawable(context, tvShow.isFavorite.drawable())
            )
            isVisible = false
        }
    }

    private fun displaySeasonsMobile(binding: ContentTvShowSeasonsMobileBinding) {
        binding.rvTvShowSeasons.apply {
            adapter = AppAdapter().apply { submitList(tvShow.seasons.onEach { it.itemType = AppAdapter.Type.SEASON_MOBILE_ITEM }) }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(10.dp(context)))
            }
        }
    }

    private fun displaySeasonsTv(binding: ContentTvShowSeasonsTvBinding) {
        binding.hgvTvShowSeasons.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply { submitList(tvShow.seasons.onEach { it.itemType = AppAdapter.Type.SEASON_TV_ITEM }) }
            setItemSpacing(20)
        }
    }

    private fun displayDirectorsMobile(binding: ContentTvShowDirectorsMobileBinding) { binding.rvTvShowDirectors.text = tvShow.directors.joinToString(", ") { it.name } }
    private fun displayDirectorsTv(binding: ContentTvShowDirectorsTvBinding) { binding.hgvTvShowDirectors.text = tvShow.directors.joinToString(", ") { it.name } }
    private fun displayCastMobile(binding: ContentTvShowCastMobileBinding) {
        binding.rvTvShowCast.apply {
            adapter = AppAdapter().apply {
                submitList(tvShow.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
                })
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(10.dp(context)))
            }
        }
    }

    private fun displayCastTv(binding: ContentTvShowCastTvBinding) {
        binding.hgvTvShowCast.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(tvShow.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
                })
            }
            setItemSpacing(20)
        }
    }
    private fun displayRecommendationsMobile(binding: ContentTvShowRecommendationsMobileBinding) {
        binding.rvTvShowRecommendations.apply {
            adapter = AppAdapter().apply {
                submitList(tvShow.recommendations.onEach {
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

    private fun displayRecommendationsTv(binding: ContentTvShowRecommendationsTvBinding) {
        binding.hgvTvShowRecommendations.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(tvShow.recommendations.onEach {
                    when (it) {
                        is Movie -> it.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                        is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                    }
                })
            }
            setItemSpacing(20)
        }
    }

    // =================================================================
    // WLFMOVIE: Navegación a temporadas
    // =================================================================

    private fun navigateToSeason(season: com.mew.wlfmovie.models.Season) {
        val navController = itemView.findNavController()
        val args = Bundle().apply {
            putString("tvShowId", tvShow.id)
            putString("tvShowTitle", tvShow.title)
            putString("tvShowPoster", tvShow.poster)
            putString("tvShowBanner", tvShow.banner)
            putString("seasonId", season.id)
            putInt("seasonNumber", season.number)
            putString("seasonTitle", season.title)
        }
        navController.navigate(com.mew.wlfmovie.R.id.season, args)
    }

    private fun showSeasonsDialog() {
        val seasons = tvShow.seasons
        if (seasons.size == 1) {
            // Si solo hay una temporada, ir directo a episodios
            showEpisodesDialog(seasons.first())
            return
        }

        // Diálogo WlfMovie con lista de temporadas
        val dialog = android.app.Dialog(context)
        val dialogView = android.view.LayoutInflater.from(context)
            .inflate(com.mew.wlfmovie.R.layout.wlf_dialog_list, null)
        dialogView.findViewById<android.widget.TextView>(com.mew.wlfmovie.R.id.tv_dialog_title).text = "Temporadas"

        val container = dialogView.findViewById<android.widget.LinearLayout>(com.mew.wlfmovie.R.id.ll_dialog_items)
        seasons.forEach { season ->
            val item = android.view.LayoutInflater.from(context)
                .inflate(com.mew.wlfmovie.R.layout.wlf_dialog_list_item, container, false) as android.widget.TextView
            item.text = season.title ?: "Temporada ${season.number}"
            item.setOnClickListener {
                dialog.dismiss()
                showEpisodesDialog(season)
            }
            container.addView(item)
        }

        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.show()
    }

    private fun showEpisodesDialog(season: com.mew.wlfmovie.models.Season) {
        val navController = itemView.findNavController()

        // Mostrar diálogo de carga
        val dialog = android.app.Dialog(context)
        val dialogView = android.view.LayoutInflater.from(context)
            .inflate(com.mew.wlfmovie.R.layout.wlf_dialog_list, null)

        // WLFMOVIE: Título con flecha ← para volver a temporadas.
        // Siempre se muestra la flecha cuando hay múltiples temporadas, para
        // que el usuario sepa dónde está y pueda volver.
        val titleText = dialogView.findViewById<android.widget.TextView>(com.mew.wlfmovie.R.id.tv_dialog_title)
        val seasons = tvShow.seasons
        if (seasons.size > 1) {
            // WLFMOVIE: flecha + nombre de la temporada, alineado a la izquierda
            // para que se vea claramente que es un botón de "volver".
            titleText.text = "←  ${season.title ?: "Temporada ${season.number}"}"
            titleText.gravity = android.view.Gravity.START
            titleText.textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
            titleText.setPadding(32, 16, 32, 16)
            // WLFMOVIE: Fondo que cambia al recibir foco (morado con borde fucsia).
            titleText.background = ContextCompat.getDrawable(context, com.mew.wlfmovie.R.drawable.wlf_bg_dialog_back_button)
            titleText.setOnClickListener {
                dialog.dismiss()
                showSeasonsDialog()
            }
            // WLFMOVIE: Hacer el título focusable para que se pueda navegar con D-pad.
            // Cuando recibe foco, el fondo cambia a morado con borde fucsia.
            titleText.isFocusable = true
            titleText.isFocusableInTouchMode = true
            // WLFMOVIE: Request focus al abrir para que se vea que es focusable.
            titleText.requestFocus()
        } else {
            titleText.text = season.title ?: "Temporada ${season.number}"
        }

        val container = dialogView.findViewById<android.widget.LinearLayout>(com.mew.wlfmovie.R.id.ll_dialog_items)
        val loadingText = android.widget.TextView(context).apply {
            text = "Cargando episodios..."
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setPadding(40, 40, 40, 40)
        }
        container.addView(loadingText)

        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.show()

        // Cargar episodios en background
        itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
            try {
                val provider = com.mew.wlfmovie.utils.UserPreferences.currentProvider
                val episodes = provider?.getEpisodesBySeason(season.id) ?: emptyList()

                withContext(Dispatchers.Main) {
                    if (_binding == null || !dialog.isShowing) return@withContext

                    container.removeAllViews()
                    if (episodes.isEmpty()) {
                        val emptyText = android.widget.TextView(context).apply {
                            text = "No hay episodios disponibles"
                            gravity = android.view.Gravity.CENTER
                            setTextColor(android.graphics.Color.WHITE)
                            setPadding(40, 40, 40, 40)
                        }
                        container.addView(emptyText)
                    } else {
                        episodes.forEach { episode ->
                            val item = android.view.LayoutInflater.from(context)
                                .inflate(com.mew.wlfmovie.R.layout.wlf_dialog_list_item, container, false) as android.widget.TextView
                            item.text = "E${episode.number} · ${episode.title ?: ""}"
                            item.setOnClickListener {
                                dialog.dismiss()
                                // Navegar al player con este episodio
                                val videoType = com.mew.wlfmovie.models.Video.Type.Episode(
                                    id = episode.id,
                                    number = episode.number,
                                    title = episode.title,
                                    poster = episode.poster,
                                    overview = episode.overview,
                                    tvShow = com.mew.wlfmovie.models.Video.Type.Episode.TvShow(
                                        id = tvShow.id,
                                        title = tvShow.title,
                                        poster = tvShow.poster,
                                        banner = tvShow.banner,
                                        releaseDate = tvShow.released?.format("yyyy-MM-dd"),
                                        imdbId = tvShow.imdbId,
                                    ),
                                    season = com.mew.wlfmovie.models.Video.Type.Episode.Season(
                                        number = season.number,
                                        title = season.title,
                                    ),
                                )
                                val args = android.os.Bundle().apply {
                                    putString("id", episode.id)
                                    putString("title", tvShow.title)
                                    putString("subtitle", "S${season.number} E${episode.number}  •  ${episode.title}")
                                    putSerializable("videoType", videoType)
                                }
                                navController.navigate(com.mew.wlfmovie.R.id.player, args)
                            }
                            container.addView(item)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (dialog.isShowing) {
                        container.removeAllViews()
                        val errorText = android.widget.TextView(context).apply {
                            text = "Error: ${e.message}"
                            gravity = android.view.Gravity.CENTER
                            setTextColor(android.graphics.Color.WHITE)
                            setPadding(40, 40, 40, 40)
                        }
                        container.addView(errorText)
                    }
                }
            }
        }
    }
}
