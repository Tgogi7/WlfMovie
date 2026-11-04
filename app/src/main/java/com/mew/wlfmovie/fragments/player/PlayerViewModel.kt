package com.mew.wlfmovie.fragments.player

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MimeTypes
import com.mew.wlfmovie.StreamFlixApp
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.models.Video
import com.mew.wlfmovie.providers.TmdbProvider
import com.mew.wlfmovie.providers.YetiPelisProvider
import com.mew.wlfmovie.utils.CrashLogger
import com.mew.wlfmovie.utils.CustomTabHelper
import com.mew.wlfmovie.utils.EpisodeManager
import com.mew.wlfmovie.utils.OpenSubtitles
import com.mew.wlfmovie.utils.UserPreferences
import com.mew.wlfmovie.utils.YpDirectPlay
import com.mew.wlfmovie.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import com.mew.wlfmovie.utils.SubDL
import java.io.File

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

    /**
     * WLFMOVIE Update 7: True cuando el video que se esta reproduciendo es un archivo
     * local (descarga offline). Lo consulta PlayerMobileFragment.saveWatchProgress para
     * decidir si guardar el progreso solo en la tabla `downloads` (y NO llamar a
     * SyncManager.autoUpload).
     */
    @Volatile
    var isPlayingOffline: Boolean = false
        private set
    init {
        getServers(videoType, id)
        getSubtitles(videoType)
    }

    /**
     * WLFMOVIE Update 7: Si la pelicula esta descargada localmente y el archivo
     * existe, emite directamente SuccessLoadingVideo con un Video de file:// y
     * un Video.Server sintetico. Marca [isPlayingOffline] = true para que el
     * PlayerMobileFragment sepa que el progreso debe guardarse solo en
     * `downloads.watchPositionMillis` (no en movies.watchHistory ni autoUpload).
     *
     * Solo aplica a peliculas (no episodios): la tabla `downloads` solo contiene
     * peliculas segun el plan.
     */
    private suspend fun tryEmitOfflineVideo(videoType: Video.Type, id: String): Boolean {
        if (videoType !is Video.Type.Movie) {
            isPlayingOffline = false
            return false
        }
        val appContext = runCatching { StreamFlixApp.instance.applicationContext }.getOrNull()
            ?: return false
        val downloaded = runCatching {
            AppDatabase.getInstance(appContext).downloadDao().getById(id)
        }.getOrNull() ?: run {
            isPlayingOffline = false
            return false
        }
        if (downloaded == null || !File(downloaded.filePath).exists()) {
            // Si la row existe pero el archivo no, limpiar silenciosamente.
            if (downloaded != null) {
                runCatching {
                    AppDatabase.getInstance(appContext).downloadDao().deleteById(id)
                }
            }
            isPlayingOffline = false
            return false
        }

        isPlayingOffline = true
        val source = "file://${downloaded.filePath}"
        // WLFMOVIE V6.2.4: set mimeType segun extension del archivo.
        // - HLS descargado → `.ts` (MPEG-TS o fMP4 concatenado) → "application/x-mp2t".
        //   Si lo dejamos como VIDEO_MP4, ExoPlayer intenta el extractor MP4 primero,
        //   falla, y hace fallback a sniffing — pero ese fallback no es garantizado.
        //   Setear "application/x-mp2t" fuerza el extractor MPEG-TS correcto.
        //   (Nota: en media3 1.8.0 las constantes APPLICATION_M2TS / APPLICATION_MP2T
        //    no estan expuestas en androidx.media3.common.MimeTypes, pero el VALOR
        //    "application/x-mp2t" sigue siendo lo que usa internamente el extractor
        //    para MPEG-TS. Pasamos el string literal directamente.)
        // - Remux descargado → `.mp4` → VIDEO_MP4 (como antes).
        // - Cualquier otra extension → VIDEO_MP4 (default historico).
        val mimeType = when {
            downloaded.filePath.endsWith(".ts", ignoreCase = true) ->
                "application/x-mp2t"
            else -> MimeTypes.VIDEO_MP4
        }
        val video = Video(
            source = source,
            subtitles = listOf(),
            type = mimeType,
            headers = null,
        )
        val offlineServer = Video.Server(
            id = "offline",
            name = "Descargado (${downloaded.language})",
            src = source,
        )
        _state.emit(State.SuccessLoadingVideo(video, offlineServer))
        return true
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

    fun getServers(videoType: Video.Type, id: String) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio ricerca server per ID: $id")
        CrashLogger.logError(
            tag = "YP-Player",
            message = "[VM] getServers() llamado | id=$id | isYpMode=${UserPreferences.yetiDirectPlayer}",
            context = "PlayerViewModel.getServers"
        )
        lastVideoType = videoType
        lastId = id
        _state.emit(State.LoadingServers)

        // WLFMOVIE Update 7: Si la pelicula esta descargada localmente, emitir el
        // Video de file:// directamente sin llamar al provider. El progreso de
        // reproduccion se guardara solo en `downloads.watchPositionMillis`.
        if (tryEmitOfflineVideo(videoType, id)) {
            CrashLogger.logError(
                tag = "YP-Player",
                message = "[VM] offline video emitido (pelicula descargada)",
                context = "PlayerViewModel.getServers"
            )
            return@launch
        }

        // WLFMOVIE YETI v8: Si el flag YP está activo (botón "Opción 2" en
        // detalles de película), usar YetiPelisProvider en vez del provider
        // actual. Esto se setea en MovieViewHolder.playWithYeti() /
        // EpisodeViewHolder.playWithYeti(). El flag se limpia después (one-shot).
        //
        // Flujo: el botón YP navega inmediatamente al reproductor (sin esperar
        // a searchServers), el usuario ve el spinner morado-fucsia del
        // reproductor mientras YetiPelisProvider busca los servers — mismo UX
        // que el botón "Reproducir" normal.
        val useYp = YpDirectPlay.consumeYpRequest()
        if (useYp) {
            // WLFMOVIE YETI v9: Guardar que este video se reprodujo con YP.
            YpDirectPlay.saveModeForVideo(id, true)
            CrashLogger.logError(
                tag = "YP-Player",
                message = "[VM] Flag YP activo — llamando a YetiPelisProvider.searchServers() en vez del provider normal",
                context = "PlayerViewModel.getServers"
            )
            try {
                // Extraer info del videoType (Movie o Episode) para pasarle al provider.
                // Estructura: title, isMovie, seasonNum, episodeNum, expectTmdbId
                val title: String
                val isMovie: Boolean
                val seasonNum: Int?
                val episodeNum: Int?
                val expectTmdbId: Int?

                when (val vt = videoType) {
                    is Video.Type.Movie -> {
                        CrashLogger.logError(
                            tag = "YP-Player",
                            message = "[VM] YP para película | title=${vt.title} | tmdb=${vt.id}",
                            context = "PlayerViewModel.getServers"
                        )
                        title = vt.title
                        isMovie = true
                        seasonNum = null
                        episodeNum = null
                        expectTmdbId = vt.id.toIntOrNull()
                    }
                    is Video.Type.Episode -> {
                        CrashLogger.logError(
                            tag = "YP-Player",
                            message = "[VM] YP para episodio | tvShow=${vt.tvShow.title} | S${vt.season.number}E${vt.number} | tmdb=${vt.tvShow.id}",
                            context = "PlayerViewModel.getServers"
                        )
                        title = vt.tvShow.title
                        isMovie = false
                        seasonNum = vt.season.number
                        episodeNum = vt.number
                        expectTmdbId = vt.tvShow.id.toIntOrNull()
                    }
                }

                val servers = YetiPelisProvider.searchServers(
                    title = title,
                    isMovie = isMovie,
                    seasonNum = seasonNum,
                    episodeNum = episodeNum,
                    expectTmdbId = expectTmdbId,
                )

                CrashLogger.logError(
                    tag = "YP-Player",
                    message = "[VM] YetiPelisProvider.searchServers() devolvió ${servers.size} servers. Primero: ${servers.firstOrNull()?.name}",
                    context = "PlayerViewModel.getServers"
                )

                if (servers.isEmpty()) {
                    _state.emit(State.FailedLoadingServers(Exception("No se encontraron servers YP")))
                } else {
                    _state.emit(State.SuccessLoadingServers(servers))
                }
            } catch (e: Exception) {
                CrashLogger.logError(
                    tag = "YP-Player",
                    message = "[VM] YetiPelisProvider.searchServers() FAILED: ${e.message?.take(200)}",
                    throwable = e,
                    context = "PlayerViewModel.getServers"
                )
                _state.emit(State.FailedLoadingServers(e))
            }
            return@launch
        }

        // WLFMOVIE YETI v7: Si hay servers YP pendientes (modo pre-cargado,
        // compatibilidad con v6/v7 — ya no se usa en v8 pero se mantiene por
        // si se invoca desde otro sitio), emitirlos directamente.
        val ypServers = YpDirectPlay.consumePendingServers()
        if (ypServers != null && ypServers.isNotEmpty()) {
            CrashLogger.logError(
                tag = "YP-Player",
                message = "[VM] YP direct play (modo pre-cargado): ${ypServers.size} servers pre-resueltos. Primero: ${ypServers.first().name} | src=${ypServers.first().src.take(100)}",
                context = "PlayerViewModel.getServers"
            )
            _state.emit(State.SuccessLoadingServers(ypServers))
            return@launch
        } else if (ypServers != null) {
            CrashLogger.logError(
                tag = "YP-Player",
                message = "[VM] No hay servers YP pendientes — usando provider normal",
                context = "PlayerViewModel.getServers"
            )
        }

        // WLFMOVIE YETI v9: Marcar que este intento fue normal (no YP).
        // Lo usa el overlay "sin servidores" para mostrar el botón correcto.
        YpDirectPlay.markNormalAttempt()
        // WLFMOVIE YETI v9: Guardar que este video se reprodujo normal.
        YpDirectPlay.saveModeForVideo(id, false)

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
        CrashLogger.logError(
            tag = "YP-Player",
            message = "[VM] getVideo() llamado | server=${server.name} | serverId=${server.id.take(100)} | src=${server.src.take(100)}",
            context = "PlayerViewModel.getVideo"
        )
        _state.emit(State.LoadingVideo(server))
        try {
            val video = UserPreferences.currentProvider!!.getVideo(server)
            if (video.source.isEmpty()) {
                CrashLogger.logError(
                    tag = "YP-Player",
                    message = "[VM] getVideo() — source vacío después de extract()",
                    context = "PlayerViewModel.getVideo"
                )
                throw Exception("No source found")
            }

            CrashLogger.logError(
                tag = "YP-Player",
                message = "[VM] getVideo() OK | video.source=${video.source.take(120)} | type=${video.type} | subtitles=${video.subtitles.size} | hasHeaders=${video.headers != null}",
                context = "PlayerViewModel.getVideo"
            )

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
            CrashLogger.logError(
                tag = "YP-Player",
                message = "[VM] getVideo() FAILED | server=${server.name} | error=${e.message?.take(200)}",
                throwable = e,
                context = "PlayerViewModel.getVideo"
            )
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
