package com.mew.wlfmovie.adapters.viewholders

import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.mew.wlfmovie.R
import com.mew.wlfmovie.databinding.ItemEpisodeContinueWatchingMobileBinding
import com.mew.wlfmovie.databinding.ItemEpisodeContinueWatchingTvBinding
import com.mew.wlfmovie.databinding.ItemEpisodeMobileBinding
import com.mew.wlfmovie.databinding.ItemEpisodeTvBinding
import com.mew.wlfmovie.fragments.home.HomeMobileFragmentDirections
import com.mew.wlfmovie.fragments.home.HomeTvFragment
import com.mew.wlfmovie.fragments.home.HomeTvFragmentDirections
import com.mew.wlfmovie.fragments.season.SeasonMobileFragmentDirections
import com.mew.wlfmovie.fragments.season.SeasonTvFragmentDirections
import com.mew.wlfmovie.fragments.tv_show.TvShowMobileFragmentDirections
import com.mew.wlfmovie.fragments.tv_show.TvShowTvFragmentDirections
import com.mew.wlfmovie.models.Episode
import com.mew.wlfmovie.models.Video
import com.mew.wlfmovie.ui.ShowOptionsMobileDialog
import com.mew.wlfmovie.ui.ShowOptionsTvDialog
import com.mew.wlfmovie.utils.format
import com.mew.wlfmovie.utils.getCurrentFragment
import com.mew.wlfmovie.utils.loadTvShowCardArtwork
import com.mew.wlfmovie.utils.toActivity

class EpisodeViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private lateinit var episode: Episode

    fun bind(episode: Episode) {
        this.episode = episode

        when (_binding) {
            is ItemEpisodeMobileBinding -> displayMobileItem(_binding)
            is ItemEpisodeTvBinding -> displayTvItem(_binding)
            is ItemEpisodeContinueWatchingMobileBinding -> displayContinueWatchingMobileItem(_binding)
            is ItemEpisodeContinueWatchingTvBinding -> displayContinueWatchingTvItem(_binding)
        }
    }


    private fun displayMobileItem(binding: ItemEpisodeMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                // WLFMOVIE YETI v9: Si el toggle "Segundo reproductor" está ON,
                // preguntar al usuario qué método quiere usar antes de llevarlo al player.
                if (com.mew.wlfmovie.utils.UserPreferences.yetiDirectPlayer) {
                    showMethodChooserDialog(isTv = false)
                } else {
                    navigateToPlayer(isTv = false, useYp = false)
                }
            }
            setOnLongClickListener {
                ShowOptionsMobileDialog(context, episode)
                    .show()
                true
            }
        }

        // WLFMOVIE YETI v8: Botón "Opción 2" en episodio (mobile).
        // Visible solo si el toggle "Segundo reproductor" está ON.
        // Al tocarlo, setea YpDirectPlay.requestYp() y navega al PlayerFragment
        // igual que el click normal del row — el usuario ve el spinner del
        // reproductor mientras YetiPelisProvider busca los servers.
        binding.btnEpisodeYp.apply {
            visibility = if (com.mew.wlfmovie.utils.UserPreferences.yetiDirectPlayer) View.VISIBLE else View.GONE
            setOnClickListener {
                try {
                    com.mew.wlfmovie.utils.CrashLogger.logError(
                        tag = "YP-Player",
                        message = "[EpisodeHolder Mobile] Botón Opción 2 presionado | episode=${episode.number} | tvShow=${episode.tvShow?.title}",
                        context = "EpisodeViewHolder.playWithYeti"
                    )
                } catch (_: Exception) {}
                com.mew.wlfmovie.utils.YpDirectPlay.requestYp()
                findNavController().navigate(
                    SeasonMobileFragmentDirections.actionSeasonToPlayer(
                        id = episode.id,
                        title = episode.tvShow?.title ?: "",
                        subtitle = buildSubtitle(),
                        videoType = buildVideoType(),
                    )
                )
            }
        }

        binding.ivEpisodePoster.apply {
            clipToOutline = true
            Glide.with(context)
                .load(episode.poster)
                .error(R.drawable.glide_fallback_cover)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
        }

        binding.pbEpisodeProgress.apply {
            val watchHistory = episode.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                episode.isWatched -> 100
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                episode.isWatched -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvEpisodeInfo.text = context.getString(
            R.string.episode_number,
            episode.number
        )

        binding.tvEpisodeTitle.text = episode.title ?: context.getString(
            R.string.episode_number,
            episode.number
        )

        binding.tvEpisodeReleased.apply {
            text = episode.released?.let { " • ${it.format("yyyy-MM-dd")}" }
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }
        binding.tvEpisodeOverview.text = episode.overview ?: ""
    }

    private fun displayTvItem(binding: ItemEpisodeTvBinding) {
        binding.root.apply {
            setOnClickListener {
                // WLFMOVIE YETI v9: Si el toggle está ON, preguntar método.
                if (com.mew.wlfmovie.utils.UserPreferences.yetiDirectPlayer) {
                    showMethodChooserDialog(isTv = true)
                } else {
                    navigateToPlayer(isTv = true, useYp = false)
                }
            }
            setOnLongClickListener {
                ShowOptionsTvDialog(context, episode)
                    .show()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                val animation = when {
                    hasFocus -> AnimationUtils.loadAnimation(context, R.anim.zoom_in)
                    else -> AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                }
                binding.root.startAnimation(animation)
                animation.fillAfter = true
            }
        }

        // WLFMOVIE YETI v8: Botón "Opción 2" en episodio (TV).
        // Mismo comportamiento que en mobile.
        binding.btnEpisodeYp.apply {
            visibility = if (com.mew.wlfmovie.utils.UserPreferences.yetiDirectPlayer) View.VISIBLE else View.GONE
            setOnClickListener {
                try {
                    com.mew.wlfmovie.utils.CrashLogger.logError(
                        tag = "YP-Player",
                        message = "[EpisodeHolder TV] Botón Opción 2 presionado | episode=${episode.number} | tvShow=${episode.tvShow?.title}",
                        context = "EpisodeViewHolder.playWithYeti"
                    )
                } catch (_: Exception) {}
                com.mew.wlfmovie.utils.YpDirectPlay.requestYp()
                findNavController().navigate(
                    SeasonTvFragmentDirections.actionSeasonToPlayer(
                        id = episode.id,
                        title = episode.tvShow?.title ?: "",
                        subtitle = buildSubtitle(),
                        videoType = buildVideoType(),
                    )
                )
            }
        }

        binding.ivEpisodePoster.apply {
            clipToOutline = true
            Glide.with(context)
                .load(episode.poster)
                .error(R.drawable.glide_fallback_cover)
                .fallback(R.drawable.glide_fallback_cover)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
        }

        binding.pbEpisodeProgress.apply {
            val watchHistory = episode.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                episode.isWatched -> 100
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                episode.isWatched -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvEpisodeInfo.text = context.getString(
            R.string.episode_number,
            episode.number
        )

        binding.tvEpisodeTitle.text = episode.title ?: context.getString(
            R.string.episode_number,
            episode.number
        )

        binding.tvEpisodeReleased.apply {
            text = episode.released?.format("EEEE - MMMM dd, yyyy")
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }
        binding.tvEpisodeOverview.text = episode.overview ?: ""
    }

    private fun displayContinueWatchingMobileItem(binding: ItemEpisodeContinueWatchingMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                // WLFMOVIE YETI v9: Si este episodio se reprodujo con YP la última vez
                // y el toggle está ON, usar YP automáticamente.
                if (com.mew.wlfmovie.utils.UserPreferences.yetiDirectPlayer &&
                    com.mew.wlfmovie.utils.YpDirectPlay.wasLastModeYpForVideo(episode.id)) {
                    com.mew.wlfmovie.utils.YpDirectPlay.requestYp()
                }
                findNavController().navigate(
                    HomeMobileFragmentDirections.actionHomeToTvShow(
                        id = episode.tvShow?.id ?: "",
                        poster = episode.tvShow?.poster,
                        banner = episode.tvShow?.banner,
                    )
                )
                findNavController().navigate(
                    TvShowMobileFragmentDirections.actionTvShowToPlayer(
                        id = episode.id,
                        title = episode.tvShow?.title ?: "",
                        subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
                            context.getString(
                                R.string.player_subtitle_tv_show,
                                season.number,
                                episode.number,
                                episode.title ?: context.getString(
                                    R.string.episode_number,
                                    episode.number
                                )
                            )
                        } ?: context.getString(
                            R.string.player_subtitle_tv_show_episode_only,
                            episode.number,
                            episode.title ?: context.getString(
                                R.string.episode_number,
                                episode.number
                            )
                        ),
                        videoType = Video.Type.Episode(
                            id = episode.id,
                            number = episode.number,
                            title = episode.title,
                            poster = episode.poster,
                            overview = episode.overview,
                            tvShow = Video.Type.Episode.TvShow(
                                id = episode.tvShow?.id ?: "",
                                title = episode.tvShow?.title ?: "",
                                poster = episode.tvShow?.poster,
                                banner = episode.tvShow?.banner,
                                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                                imdbId = episode.tvShow?.imdbId,
                            ),
                            season = Video.Type.Episode.Season(
                                number = episode.season?.number ?: 0,
                                title = episode.season?.title,
                            ),
                        ),
                    )
                )
            }
            setOnLongClickListener {
                com.mew.wlfmovie.utils.ContinueWatchingRemover.showForEpisode(context, episode)
                true
            }
        }

        binding.ivEpisodeTvShowPoster.apply {
            clipToOutline = true
            loadContinueWatchingArtwork()
        }

        binding.pbEpisodeProgress.apply {
            val watchHistory = episode.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvEpisodeTvShowTitle.text = episode.tvShow?.title ?: ""

        binding.tvEpisodeInfo.text = episode.season?.takeIf { it.number != 0 }?.let { season ->
            context.getString(
                R.string.episode_item_info,
                season.number,
                episode.number,
                episode.title ?: context.getString(
                    R.string.episode_number,
                    episode.number
                )
            )
        } ?: context.getString(
            R.string.episode_item_info_episode_only,
            episode.number,
            episode.title ?: context.getString(
                R.string.episode_number,
                episode.number
            )
        )
    }

    private fun displayContinueWatchingTvItem(binding: ItemEpisodeContinueWatchingTvBinding) {
        binding.root.apply {
            setOnClickListener {
                // WLFMOVIE YETI v9: Si este episodio se reprodujo con YP la última vez
                // y el toggle está ON, usar YP automáticamente.
                if (com.mew.wlfmovie.utils.UserPreferences.yetiDirectPlayer &&
                    com.mew.wlfmovie.utils.YpDirectPlay.wasLastModeYpForVideo(episode.id)) {
                    com.mew.wlfmovie.utils.YpDirectPlay.requestYp()
                }
                findNavController().navigate(
                    HomeTvFragmentDirections.actionHomeToTvShow(
                        id = episode.tvShow?.id ?: "",
                        poster = episode.tvShow?.poster,
                        banner = episode.tvShow?.banner,
                    )
                )
                findNavController().navigate(
                    TvShowTvFragmentDirections.actionTvShowToPlayer(
                        id = episode.id,
                        title = episode.tvShow?.title ?: "",
                        subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
                            context.getString(
                                R.string.player_subtitle_tv_show,
                                season.number,
                                episode.number,
                                episode.title ?: context.getString(
                                    R.string.episode_number,
                                    episode.number
                                )
                            )
                        } ?: context.getString(
                            R.string.player_subtitle_tv_show_episode_only,
                            episode.number,
                            episode.title ?: context.getString(
                                R.string.episode_number,
                                episode.number
                            )
                        ),
                        videoType = Video.Type.Episode(
                            id = episode.id,
                            number = episode.number,
                            title = episode.title,
                            poster = episode.poster,
                            overview = episode.overview,
                            tvShow = Video.Type.Episode.TvShow(
                                id = episode.tvShow?.id ?: "",
                                title = episode.tvShow?.title ?: "",
                                poster = episode.tvShow?.poster,
                                banner = episode.tvShow?.banner,
                                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                                imdbId = episode.tvShow?.imdbId,
                            ),
                            season = Video.Type.Episode.Season(
                                number = episode.season?.number ?: 0,
                                title = episode.season?.title,
                            ),
                        ),
                    )
                )
            }
            setOnLongClickListener {
                com.mew.wlfmovie.utils.ContinueWatchingRemover.showForEpisode(context, episode)
                true
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
                            fragment.pinBackground(episode.tvShow?.banner)
                        } else {
                            fragment.releasePinnedBackground()
                        }
                    }
                }
            }
        }

        binding.ivEpisodeTvShowPoster.apply {
            clipToOutline = true
            loadContinueWatchingArtwork(withFallback = true)
        }

        binding.pbEpisodeProgress.apply {
            val watchHistory = episode.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                episode.isWatched -> 100
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                episode.isWatched -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvEpisodeTvShowTitle.text = episode.tvShow?.title ?: ""

        binding.tvEpisodeInfo.text = episode.season?.takeIf { it.number != 0 }?.let { season ->
            context.getString(
                R.string.episode_item_info,
                season.number,
                episode.number,
                episode.title ?: context.getString(
                    R.string.episode_number,
                    episode.number
                )
            )
        } ?: context.getString(
            R.string.episode_item_info_episode_only,
            episode.number,
            episode.title ?: context.getString(
                R.string.episode_number,
                episode.number
            )
        )
    }

    private fun ImageView.loadContinueWatchingArtwork(withFallback: Boolean = false) {
        val tvShow = episode.tvShow
        if (tvShow == null) {
            Glide.with(context)
                .load(episode.poster)
                .error(R.drawable.glide_fallback_cover)
                .apply {
                    if (withFallback) fallback(R.drawable.glide_fallback_cover)
                }
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
            return
        }

        loadTvShowCardArtwork(tvShow) {
            error(R.drawable.glide_fallback_cover)
            apply {
                if (withFallback) fallback(R.drawable.glide_fallback_cover)
            }
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }
    }

    // WLFMOVIE YETI v8: Helpers para construir el videoType y el subtitle del
    // episodio sin duplicar el código en cada click listener (mobile y TV).
    // Esto se usa tanto para el click normal del row como para el botón YP.

    private fun buildSubtitle(): String {
        return episode.season?.takeIf { it.number != 0 }?.let { season ->
            context.getString(
                R.string.player_subtitle_tv_show,
                season.number,
                episode.number,
                episode.title ?: context.getString(
                    R.string.episode_number,
                    episode.number
                )
            )
        } ?: context.getString(
            R.string.player_subtitle_tv_show_episode_only,
            episode.number,
            episode.title ?: context.getString(
                R.string.episode_number,
                episode.number
            )
        )
    }

    private fun buildVideoType(): Video.Type.Episode {
        return Video.Type.Episode(
            id = episode.id,
            number = episode.number,
            title = episode.title,
            poster = episode.poster,
            overview = episode.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = episode.tvShow?.id ?: "",
                title = episode.tvShow?.title ?: "",
                poster = episode.tvShow?.poster,
                banner = episode.tvShow?.banner,
                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                imdbId = episode.tvShow?.imdbId,
            ),
            season = Video.Type.Episode.Season(
                number = episode.season?.number ?: 0,
                title = episode.season?.title,
            ),
        )
    }

    // WLFMOVIE YETI v9: Navega al player con el modo elegido (normal o YP).
    private fun navigateToPlayer(isTv: Boolean, useYp: Boolean) {
        if (useYp) {
            com.mew.wlfmovie.utils.YpDirectPlay.requestYp()
            try {
                com.mew.wlfmovie.utils.CrashLogger.logError(
                    tag = "YP-Player",
                    message = "[EpisodeHolder] Navegando al player con Opción 2 | S${episode.season?.number}E${episode.number}",
                    context = "EpisodeViewHolder.navigateToPlayer"
                )
            } catch (_: Exception) {}
        }
        val navController = itemView.findNavController()
        if (isTv) {
            navController.navigate(
                SeasonTvFragmentDirections.actionSeasonToPlayer(
                    id = episode.id,
                    title = episode.tvShow?.title ?: "",
                    subtitle = buildSubtitle(),
                    videoType = buildVideoType(),
                )
            )
        } else {
            navController.navigate(
                SeasonMobileFragmentDirections.actionSeasonToPlayer(
                    id = episode.id,
                    title = episode.tvShow?.title ?: "",
                    subtitle = buildSubtitle(),
                    videoType = buildVideoType(),
                )
            )
        }
    }

    // WLFMOVIE YETI v9: Diálogo que pregunta al usuario qué método usar
    // (normal o Opción 2) cuando entra a un episodio desde el selector de temporada.
    // Solo se muestra si el toggle "Segundo reproductor" está ON.
    private fun showMethodChooserDialog(isTv: Boolean) {
        val options = arrayOf("▶ Reproducir normal", "🐢 Opción 2")
        android.app.AlertDialog.Builder(context)
            .setTitle("Método de reproducción")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> navigateToPlayer(isTv, useYp = false)
                    1 -> navigateToPlayer(isTv, useYp = true)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
