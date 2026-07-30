package com.mew.wlfmovie.fragments.player

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mew.wlfmovie.models.Video
import com.mew.wlfmovie.providers.TmdbProvider
import com.mew.wlfmovie.utils.CustomTabHelper
import com.mew.wlfmovie.utils.EpisodeManager
import com.mew.wlfmovie.utils.OpenSubtitles
import com.mew.wlfmovie.utils.UserPreferences
import com.mew.wlfmovie.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import com.mew.wlfmovie.utils.SubDL

class PlayerViewModel(
    videoType: Video.Type,
    id: String,
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.LoadingServers)
    val state: Flow<State> = _state

    private val _subtitleState = MutableSharedFlow<SubtitleState>()
    val subtitleState: SharedFlow<SubtitleState> = _subtitleState

    private val _playPreviousOrNextEpisode = MutableSharedFlow<Video.Type.Episode>()
    val playPreviousOrNextEpisode: SharedFlow<Video.Type.Episode> = _playPreviousOrNextEpisode
    init {
        getServers(videoType, id)
        getSubtitles(videoType)
    }

    fun playEpisode(direction: Direction) {
        val hasEpisode = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.hasPreviousEpisode()
            Direction.NEXT -> EpisodeManager.hasNextEpisode()
        }

        if (!hasEpisode) return

        val ep = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.getPreviousEpisode()
            Direction.NEXT -> EpisodeManager.getNextEpisode()
        } ?: return

        val nextEpisode = Video.Type.Episode(
            id = ep.id,
            number = ep.number,
            title = ep.title,
            poster = ep.poster,
            overview = ep.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = ep.tvShow.id,
                title = ep.tvShow.title,
                poster = ep.tvShow.poster,
                banner = ep.tvShow.banner,
                releaseDate = ep.tvShow.releaseDate,
                imdbId = ep.tvShow.imdbId
            ),
            season = Video.Type.Episode.Season(
                number = ep.season.number,
                title = ep.season.title
            )
        )

        playEpisode(nextEpisode)

        viewModelScope.launch {
            _playPreviousOrNextEpisode.emit(nextEpisode)
        }
    }

    enum class Direction { PREVIOUS, NEXT }
    fun playPreviousEpisode() =
        playEpisode(Direction.PREVIOUS)

    fun playNextEpisode() =
        playEpisode(Direction.NEXT)

    fun autoplayNextEpisode() {
        if (UserPreferences.autoplay) {
            playEpisode(Direction.NEXT)
        }
    }
    fun playEpisode(episode: Video.Type.Episode) {
        getServers(episode, episode.id)
        getSubtitles(episode)
    }

    private fun getServers(videoType: Video.Type, id: String) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio ricerca server per ID: $id")
        lastVideoType = videoType
        lastId = id
        _state.emit(State.LoadingServers)

        val provider = UserPreferences.currentProvider

        // =================================================================
        // WLFMOVIE: Si el provider es TmdbProvider, usar getServersFlow()
        // que emite servers incrementalmente. El usuario empieza a ver
        // nada más llegar el primer server, y los demás se agregan después.
        // =================================================================
        if (provider is TmdbProvider) {
            try {
                var firstEmitHandled = false
                provider.getServersFlow(id, videoType).collect { servers ->
                    Log.i("WlfMovie", "[PlayerVM] Lote recibido: ${servers.size} servers")

                    if (servers.isEmpty() && !firstEmitHandled) {
                        // Todavía esperando el primer lote
                        return@collect
                    }

                    if (servers.isEmpty()) {
                        // Si ya tuvimos servers y ahora viene vacío, ignorar
                        return@collect
                    }

                    if (!firstEmitHandled) {
                        // Primera emisión con servers - mostrar y autoseleccionar el primero
                        firstEmitHandled = true
                        Log.i("WlfMovie", "[PlayerVM] Primera emisión: ${servers.size} servers, autoseleccionando: ${servers.first().name}")
                        _state.emit(State.SuccessLoadingServers(servers))
                    } else {
                        // Emisiones posteriores - solo actualizar la lista sin reiniciar
                        Log.i("WlfMovie", "[PlayerVM] Actualización: ${servers.size} servers totales")
                        _state.emit(State.SuccessLoadingServers(servers))
                    }
                }

                // Si el flow terminó sin emitir nada, error
                if (!firstEmitHandled) {
                    _state.emit(State.FailedLoadingServers(Exception("No servers found")))
                }
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Error en getServersFlow: ", e)
                _state.emit(State.FailedLoadingServers(e))
            }
            return@launch
        }

        // =================================================================
        // Para otros providers: comportamiento original síncrono
        // =================================================================
        try {
            val servers = provider!!.getServers(id, videoType)
            if (servers.isEmpty()) throw Exception("No servers found")

            Log.i("StreamFlixES", "[SERVERS LIST] -> Provider: ${provider.name}")
            Log.i("StreamFlixES", "[SERVERS LIST] -> Found ${servers.size} servers: ${servers.joinToString { it.name }}")

            _state.emit(State.SuccessLoadingServers(servers))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore ricerca server: ", e)
            _state.emit(State.FailedLoadingServers(e))
        }
    }

    fun getVideo(server: Video.Server) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio estrazione video dal server: ${server.name}")
        _state.emit(State.LoadingVideo(server))
        try {
            val video = UserPreferences.currentProvider!!.getVideo(server)
            if (video.source.isEmpty()) throw Exception("No source found")

            // LOGICA SOTTOTITOLI GLOBALE: 
            // Se il provider non ha già impostato un default (es. i "forced" in spagnolo),
            // allora proviamo ad attivare l'ultimo sottotitolo usato dall'utente.
            // MA: se siamo su un provider spagnolo e non ci sono forced, non dobbiamo attivare nulla.
            val currentProviderLang = UserPreferences.currentProvider?.language ?: ""
            val hasDefaultAlready = video.subtitles.any { it.default }

            if (!hasDefaultAlready && currentProviderLang != "es") {
                if (!(video.useServerSubtitleSetting && UserPreferences.serverAutoSubtitlesDisabled)) {
                    video.subtitles
                        .firstOrNull { it.label.startsWith(UserPreferences.subtitleName ?: "") }
                        ?.default = true
                }
            }

            Log.d("PlayerViewModel", "Estrazione video completata con successo")
            _state.emit(State.SuccessLoadingVideo(video, server))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore estrazione video: ", e)
            _state.emit(State.FailedLoadingVideo(e, server))
        }
    }

    fun getSubtitles(videoType: Video.Type) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio ricerca sottotitoli")
        _subtitleState.emit(SubtitleState.Loading)

        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca OpenSubtitles")
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> {
                        OpenSubtitles.search(
                            query = videoType.tvShow.title,
                            season = videoType.season.number,
                            episode = videoType.number,
                        )
                    }
                    is Video.Type.Movie -> {
                        OpenSubtitles.search(query = videoType.title)
                    }
                }.sortedWith(compareBy({ it.languageName }, { it.subDownloadsCnt }))
                
                Log.d("PlayerViewModel", "Ricerca OpenSubtitles completata: ${subtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessOpenSubtitles(subtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore OpenSubtitles: ", e)
                _subtitleState.emit(SubtitleState.FailedOpenSubtitles(e))
            }
        }

        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca SubDL")
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> {
                        SubDL.search(
                            filmName = videoType.tvShow.title,
                            seasonNumber = videoType.season.number,
                            episodeNumber = videoType.number,
                            type = "tv"
                        )
                    }
                    is Video.Type.Movie -> {
                        SubDL.search(
                            filmName = videoType.title,
                            type = "movie"
                        )
                    }
                }
                
                Log.d("PlayerViewModel", "Ricerca SubDL completata: ${subtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessSubDLSubtitles(subtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore SubDL: ", e)
                _subtitleState.emit(SubtitleState.FailedSubDLSubtitles(e))
            }
        }
    }

    fun downloadSubtitle(subtitle: OpenSubtitles.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio download sottotitolo OpenSubtitles: ${subtitle.subFileName}")
        _subtitleState.emit(SubtitleState.DownloadingOpenSubtitle)
        try {
            val uri = OpenSubtitles.download(subtitle)
            Log.d("PlayerViewModel", "Download OpenSubtitles completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingOpenSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download OpenSubtitles: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingOpenSubtitle(e, subtitle))
        }
    }

    fun downloadSubDLSubtitle(subtitle: SubDL.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio download sottotitolo SubDL: ${subtitle.name}")
        _subtitleState.emit(SubtitleState.DownloadingSubDLSubtitle)
        try {
            val uri = SubDL.download(subtitle)
            Log.d("PlayerViewModel", "Download SubDL completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingSubDLSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download SubDL: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingSubDLSubtitle(e, subtitle))
        }
    }

    sealed class State {
        data object LoadingServers : State()
        data class SuccessLoadingServers(val servers: List<Video.Server>) : State()
        data class FailedLoadingServers(val error: Exception) : State()
        data class LoadingVideo(val server: Video.Server) : State()
        data class SuccessLoadingVideo(val video: Video, val server: Video.Server) : State()
        data class FailedLoadingVideo(val error: Exception, val server: Video.Server) : State()
    }

    sealed class SubtitleState {
        data object Loading : SubtitleState()
        data class SuccessOpenSubtitles(val subtitles: List<OpenSubtitles.Subtitle>) : SubtitleState()
        data class FailedOpenSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingOpenSubtitle : SubtitleState()
        data class SuccessDownloadingOpenSubtitle(val subtitle: OpenSubtitles.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingOpenSubtitle(val error: Exception, val subtitle: OpenSubtitles.Subtitle) : SubtitleState()

        data class SuccessSubDLSubtitles(val subtitles: List<SubDL.Subtitle>) : SubtitleState()
        data class FailedSubDLSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingSubDLSubtitle : SubtitleState()
        data class SuccessDownloadingSubDLSubtitle(val subtitle: SubDL.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingSubDLSubtitle(val error: Exception, val subtitle: SubDL.Subtitle) : SubtitleState()
    }
    private var lastVideoType: Video.Type? = null
    private var lastId: String? = null
    fun reloadServersAfterBypass() {
        val type = lastVideoType ?: return
        val id = lastId ?: return
        getServers(type, id)
    }
}
