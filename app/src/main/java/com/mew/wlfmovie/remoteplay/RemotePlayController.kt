package com.mew.wlfmovie.remoteplay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.models.Video
import com.mew.wlfmovie.models.WatchItem
import com.mew.wlfmovie.utils.CrashLogger
import com.mew.wlfmovie.utils.UserDataCache
import com.mew.wlfmovie.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Orquestador del RemotePlay.
 *
 * Capa 6+7+8+9: combina:
 * - RemotePlayServer (HTTP + WebSocket) — arrancado por el Controller
 * - RemotePlayService (foreground) — mantiene notificación + wakelock para
 *   que el server sobreviva pantalla apagada / app en background
 * - Sync de posición cada 10s — manejado por el Controller (NO por el Fragment)
 *   para que siga funcionando aunque el Fragment se destruya (ej: al navegar
 *   a la pantalla RemotePlayFragment)
 *
 * El Controller es el punto de entrada único. El Fragment llama a start(),
 * y observa RemotePlayState.connectionState + RemotePlayState.serverUrl
 * para actualizar la UI.
 */
object RemotePlayController {

    private const val TAG = "WlfMovie-RemoteCtrl"

    private var server: RemotePlayServer? = null
    private var appContext: Context? = null
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null
    private var prepareJob: Job? = null
    private var lastSavedPosition = -1L
    private var lastSavedIsPlaying = false

    /**
     * Inicia el cast.
     *
     * 1. Arranca el RemotePlayService (foreground + wakelock)
     * 2. Levanta el server HTTP+WebSocket
     * 3. Arranca el sync de posición cada 10s (guarda en DB + nube automáticamente)
     * 4. Devuelve la URL para que el user la abra en el PC
     */
    fun start(
        context: Context,
        video: Video,
        server: Video.Server,
        videoType: Video.Type,
        position: Long,
        duration: Long
    ): String? {
        Log.i(TAG, "start: video.source=${video.source}, position=$position, duration=$duration")

        // Detener server anterior si existía
        stopServerOnly()

        // Guardar contexto para el sync
        appContext = context.applicationContext

        // Guardar estado compartido DESPUÉS de detener el server anterior
        RemotePlayState.currentVideo = video
        RemotePlayState.currentServer = server
        RemotePlayState.currentVideoType = videoType
        RemotePlayState.currentPosition = position
        RemotePlayState.currentDuration = duration

        // WLFMOVIE V6.4: Detectar archivo LOCAL (pelicula descargada, file://).
        // En modo local el server NO retransmite: sirve el archivo del disco
        // por la LAN (funciona sin internet, ej: hotspot) y el progreso se
        // guarda SOLO en la tabla `downloads` — sin nube, sin continue watching.
        val isLocalFile = video.source.startsWith("file://")
        RemotePlayState.isLocalFile = isLocalFile
        RemotePlayState.localFilePath = if (isLocalFile) video.source.removePrefix("file://") else null
        RemotePlayState.downloadId = if (isLocalFile) (videoType as? Video.Type.Movie)?.id else null
        if (isLocalFile) {
            Log.i(TAG, "start: modo LOCAL (descarga) — archivo=${RemotePlayState.localFilePath}, downloadId=${RemotePlayState.downloadId}")
        }

        RemotePlayState.setState(RemotePlayState.ConnectionState.STARTING)

        // Arrancar el service foreground (notificación + wakelock)
        val serviceIntent = Intent(context, RemotePlayService::class.java).apply {
            action = RemotePlayService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        // Levantar server nuevo
        val newServer = RemotePlayServer(context.applicationContext)
        newServer.onMessage = { json -> handleWebsocketMessage(json) }
        val url = newServer.startWithUrl()
        if (url == null) {
            Log.e(TAG, "start: no se pudo levantar el server")
            RemotePlayState.setState(RemotePlayState.ConnectionState.ERROR)
            RemotePlayState.reset()
            val stopIntent = Intent(context, RemotePlayService::class.java).apply {
                action = RemotePlayService.ACTION_STOP
            }
            context.startService(stopIntent)
            return null
        }

        this.server = newServer
        RemotePlayState.setServerUrl(url)
        RemotePlayState.setState(RemotePlayState.ConnectionState.WAITING)
        Log.i(TAG, "start: server levantado en $url")

        // WLFMOVIE V6.5.0: preparación EN CALIENTE del .ts local. La caminata
        // de purga (corridas limpias + discontinuidades) lee el archivo
        // completo — puede tardar varios segundos en un .ts de 2GB. Dispararla
        // AHORA (en cuanto el usuario toca "Enviar al PC") hace que el
        // /stream del navegador casi siempre encuentre el índice listo, y la
        // UI muestra el avance mientras el usuario abre la URL en el PC.
        if (isLocalFile && RemotePlayState.localFilePath?.endsWith(".ts", ignoreCase = true) == true) {
            val localPath = RemotePlayState.localFilePath
            if (localPath != null) {
                prepareJob?.cancel()
                prepareJob = controllerScope.launch(Dispatchers.IO) {
                    RemotePlayState.updateLocalPrep(0)
                    val entry = try {
                        newServer.prepareLocalFile(File(localPath)) { frac ->
                            RemotePlayState.updateLocalPrep((frac * 100f).toInt().coerceIn(0, 100))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "prepareLocalFile: error", e)
                        null
                    }
                    RemotePlayState.updateLocalPrep(if (entry != null) 100 else -1)
                    val idx = entry?.index
                    // V6.5.2: capa del audio MPEG — Layer I/II (MP2) NO se puede
                    // decodificar en NINGÚN navegador (hls.js lo entrega crudo y el
                    // decodificador Layer III del navegador lo vuelve ruido grave y
                    // distorsionado).
                    // V6.6.0: la conversión a MP4 (y la re-codificación de audio a
                    // AAC) fue ELIMINADA por decisión del usuario — ya NO existe
                    // como salida. El aviso ahora es honesto: sin re-codificar no
                    // hay arreglo posible en el navegador; la alternativa es
                    // descargar esa película del servidor que entrega MP4 (Remux)
                    // — ese archivo el navegador lo reproduce perfecto, audio
                    // incluido (es exactamente lo que pasa al ver online).
                    val audioLayer = idx?.audioMpegLayer ?: 0
                    val audioNote = when (audioLayer) {
                        2, 3 ->
                            " ATENCIÓN: el audio de esta película es MP2 (MPEG Layer " +
                                (if (audioLayer == 2) "II" else "I") +
                                ") y NINGÚN navegador lo puede decodificar — en el PC se oirá " +
                                "grave y distorsionado aunque el video se vea perfecto " +
                                "(en el celular suena normal). V6.6.0 ya no convierte ni " +
                                "re-codifica audio, así que no hay arreglo en el navegador " +
                                "para este .ts. Alternativa sin convertir nada: descargar " +
                                "esta película del servidor que entrega MP4 (Remux) y " +
                                "volver a enviarla — ese sí se oye perfecto en el PC."
                        1 -> " (audio MP3: el navegador sí lo reproduce)."
                        else -> ""
                    }
                    if (idx != null &&
                        (idx.junkRuns > 0 || idx.clockBreaks > 0 || audioNote.isNotEmpty())
                    ) {
                        val title = when (videoType) {
                            is Video.Type.Movie -> videoType.title
                            is Video.Type.Episode -> "${videoType.tvShow.title} S${videoType.season.number}E${videoType.number}"
                            else -> "?"
                        }
                        val junkNote = if (idx.junkRuns > 0 || idx.clockBreaks > 0) {
                            "el .ts trae basura intercalada — se saltarán ${idx.junkBytes} B en " +
                                "${idx.junkRuns} huecos y se marcarán ${idx.clockBreaks} saltos de " +
                                "reloj como discontinuidades (el navegador rebasa el timeline y " +
                                "sigue reproduciendo en vez de congelarse en el hueco)."
                        } else {
                            "el .ts está limpio (sin basura ni saltos de reloj)."
                        }
                        CrashLogger.logError(
                            tag = TAG,
                            message = "ENVIAR-PC «$title»: $junkNote$audioNote",
                            context = "envío al PC",
                        )
                    }
                }
            }
        }

        // Arrancar sync de posición cada 10s (automático, sin necesidad de back/stop)
        startPositionSync()

        return url
    }

    /**
     * Detiene el server HTTP si estaba levantado, pero NO resetea el estado.
     */
    private fun stopServerOnly() {
        server?.stopServer()
        server = null
    }

    /**
     * Detiene el cast completamente: server + service + estado + sync.
     * Antes de detener, guarda la última posición conocida.
     */
    fun stop(context: Context) {
        Log.i(TAG, "stop")
        // Guardar última posición antes de detener
        saveLastPosition()
        // Detener sync
        syncJob?.cancel()
        syncJob = null
        // Detener la preparación en caliente (si seguía caminando)
        prepareJob?.cancel()
        prepareJob = null
        RemotePlayState.updateLocalPrep(-1)
        lastSavedPosition = -1L
        lastSavedIsPlaying = false
        // Detener server + estado
        RemotePlayState.setState(RemotePlayState.ConnectionState.STOPPING)
        stopServerOnly()
        RemotePlayState.setServerUrl(null)
        RemotePlayState.reset()
        // Detener el service
        val intent = Intent(context, RemotePlayService::class.java).apply {
            action = RemotePlayService.ACTION_STOP
        }
        context.startService(intent)
    }

    /**
     * Envía comando play al PC.
     */
    fun sendPlay() {
        server?.sendPlay()
    }

    /**
     * Envía comando pause al PC.
     */
    fun sendPause() {
        server?.sendPause()
    }

    /**
     * Envía comando seek al PC.
     */
    fun sendSeek(positionMs: Long) {
        server?.sendSeek(positionMs)
    }

    /**
     * Envía comando stop al PC (le pide que cierre el video) y detiene el cast.
     * IMPORTANTE: envía el stop por WebSocket PRIMERO, espera 500ms para que
     * el PC lo reciba, y luego detiene el server.
     */
    fun sendStop(context: Context) {
        // Enviar stop por WebSocket primero
        server?.sendStop()
        // Esperar 500ms para que el PC reciba el stop antes de cerrar el server
        controllerScope.launch {
            kotlinx.coroutines.delay(500)
            stop(context)
        }
    }

    /**
     * ¿Está el server levantado?
     */
    fun isRunning(): Boolean = server != null

    /**
     * Arranca el sync de posición.
     * Ya NO usamos un timer cada 10s — en su lugar, guardamos en CADA reporte
     * del PC (que llega cada 10s del navegador). Esto asegura que guardamos
     * en el momento exacto en que el PC reporta, sin importar en qué segundo
     * del reloj del celular estemos.
     *
     * El PC reporta posición cada 10s (según su propio reloj), así que el
     * guardado ocurre cada 10s pero alineado con el PC, no con el celular.
     */
    private fun startPositionSync() {
        syncJob?.cancel()
        lastSavedPosition = -1L
        lastSavedIsPlaying = false
        // Ya no necesitamos un timer — el sync se dispara desde
        // handleWebsocketMessage() cuando llega un reporte 'position'.
        Log.i(TAG, "startPositionSync: sync on-demand arrancado (se guarda en cada reporte del PC)")
    }

    /**
     * Llamado cuando llega un reporte de posición del PC.
     * Guarda en DB local + nube si la posición o el estado isPlaying cambió.
     */
    private fun onPositionReport(position: Long, duration: Long, isPlaying: Boolean) {
        if (position <= 0 || duration <= 0) return
        // Guardar si la posición cambió O si el estado isPlaying cambió
        // (para que el guardado refleje el pause/play inmediatamente).
        if (position == lastSavedPosition && isPlaying == lastSavedIsPlaying) return

        lastSavedPosition = position
        lastSavedIsPlaying = isPlaying
        controllerScope.launch {
            savePosition(position, duration, isPlaying)
            // WLFMOVIE V6.4: en modo local (descarga) NO se sube nada a la nube
            // — el plan exige aislamiento total del sync (y puede no haber internet).
            if (RemotePlayState.isLocalFile) {
                return@launch
            }
            // CRÍTICO: Subir a la nube después de guardar localmente.
            // Sin esto, el cache local se actualiza pero la nube nunca se entera.
            // (Esto es lo que hacía el player local en saveWatchProgress)
            try {
                val ctx = appContext ?: return@launch
                com.mew.wlfmovie.utils.SyncManager.autoUpload(ctx)
                Log.i(TAG, "onPositionReport: autoUpload a nube disparado")
            } catch (e: Exception) {
                Log.e(TAG, "onPositionReport: error autoUpload", e)
            }
        }
    }

    /**
     * Guarda la última posición conocida (llamado al detener el cast).
     */
    private fun saveLastPosition() {
        val position = RemotePlayState.remotePosition.value
        val duration = RemotePlayState.remoteDuration.value
        val isPlaying = RemotePlayState.remoteIsPlaying.value
        if (position > 0 && duration > 0) {
            controllerScope.launch {
                savePosition(position, duration, isPlaying)
                // WLFMOVIE V6.4: en modo local nada de nube.
                if (RemotePlayState.isLocalFile) {
                    return@launch
                }
                // Subir a la nube también al hacer stop
                try {
                    val ctx = appContext ?: return@launch
                    com.mew.wlfmovie.utils.SyncManager.autoUpload(ctx)
                    Log.i(TAG, "saveLastPosition: autoUpload a nube disparado")
                } catch (e: Exception) {
                    Log.e(TAG, "saveLastPosition: error autoUpload", e)
                }
            }
        }
    }

    /**
     * Guarda la posición en DB local + nube.
     * Réplica EXACTA de la lógica del player local (PlayerMobileFragment.onIsPlayingChanged):
     * - Actualiza isWatched / watchedDate / watchHistory del episodio/película
     * - Si terminó: resetea progreso, saca de continue watching, hace queue del próximo ep
     * - Actualiza tvShow.isWatching (CRÍTICO — sin esto, continue watching no se actualiza)
     * - Sync a UserDataCache (nube)
     *
     * Estados de "terminado" (réplica del player local):
     * - hasFinished = position >= duration * 0.90 (como player.hasFinished())
     * - hasReallyFinished = position >= duration - autoplayBuffer*1000 (como player.hasReallyFinished())
     *   o cuando el PC manda {type:"ended"}
     *
     * isWatching = !hasReallyFinished || isStillWatching
     */
    private suspend fun savePosition(position: Long, duration: Long, isPlaying: Boolean) {
        try {
            val ctx = appContext ?: return

            // WLFMOVIE V6.4: Modo LOCAL (pelicula descargada) — guardar SOLO en
            // la tabla `downloads` (watchPositionMillis / durationMillis / isWatched).
            // NO se toca movies/episodes, NO UserDataCache, NO continue watching,
            // NO SyncManager — aislamiento total del sync, igual que el player
            // offline del celular (saveOfflineWatchProgress).
            if (RemotePlayState.isLocalFile) {
                saveLocalDownloadPosition(position, duration)
                return
            }

            val videoType = RemotePlayState.currentVideoType ?: return
            val provider = UserPreferences.currentProvider ?: return
            val database = AppDatabase.getInstance(ctx)

            // Réplica de hasFinished() y hasReallyFinished() del player local
            val hasFinished = duration > 0 && position >= duration * 0.90
            val autoplayBufferMs = UserPreferences.autoplayBuffer * 1000L
            val hasReallyFinished = duration > 0 && position >= (duration - autoplayBufferMs)
            // Si el video terminó (ended), forzar hasReallyFinished
            val reallyEnded = hasReallyFinished || RemotePlayState.videoEnded

            Log.i(TAG, "savePosition: pos=$position, dur=$duration, hasFinished=$hasFinished, reallyEnded=$reallyEnded")

            when (videoType) {
                is Video.Type.Movie -> {
                    val movie = database.movieDao().getById(videoType.id) ?: return
                    if (hasFinished) {
                        movie.isWatched = true
                        movie.watchedDate = java.util.Calendar.getInstance()
                        movie.watchHistory = null
                        UserDataCache.removeMovieFromContinueWatching(ctx, provider, movie.id)
                    } else {
                        movie.isWatched = false
                        movie.watchedDate = null
                        movie.watchHistory = WatchItem.WatchHistory(
                            lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                            lastPlaybackPositionMillis = position,
                            durationMillis = duration,
                        )
                    }
                    database.movieDao().update(movie)
                    if (!movie.isWatched) {
                        UserDataCache.syncMovieToCache(ctx, provider, movie)
                    }
                    Log.i(TAG, "savePosition: MOVIE saved, watched=${movie.isWatched}")
                }
                is Video.Type.Episode -> {
                    val episode = database.episodeDao().getById(videoType.id) ?: return
                    if (hasFinished) {
                        episode.isWatched = true
                        episode.watchedDate = java.util.Calendar.getInstance()
                        episode.watchHistory = null
                        database.episodeDao().resetProgressionFromEpisode(videoType.id)
                        // WLFMOVIE Update 5: Marcar todos los episodios ANTERIORES como vistos.
                        // Si el user salta directo al último ep y lo termina, los anteriores
                        // también deben quedar como vistos — sin esto, isStillWatching queda
                        // en true y la serie no se quita del continue watching.
                        database.episodeDao().markPreviousEpisodesWatched(videoType.id)
                        UserDataCache.removeEpisodeFromContinueWatching(ctx, provider, episode.id)
                        // Queue next episode for continue watching (como hace el player local)
                        queueNextEpisodeForContinueWatching(ctx, provider, videoType, database)
                    } else {
                        episode.isWatched = false
                        episode.watchedDate = null
                        episode.watchHistory = WatchItem.WatchHistory(
                            lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                            lastPlaybackPositionMillis = position,
                            durationMillis = duration,
                        )
                    }
                    database.episodeDao().update(episode)
                    if (!episode.isWatched) {
                        UserDataCache.syncEpisodeToCache(ctx, provider, episode)
                    }

                    // CRÍTICO: Actualizar tvShow.isWatching — réplica exacta del player local:
                    // isWatching = !hasReallyFinished || isStillWatching
                    episode.tvShow?.let { tvShowRef ->
                        database.tvShowDao().getById(tvShowRef.id)?.let { tvShow ->
                            val isStillWatching = database.episodeDao().hasAnyWatchHistoryForTvShow(tvShow.id)
                            val tvShowCopy = tvShow.copy().apply {
                                merge(tvShow)
                                isWatching = !reallyEnded || isStillWatching
                            }
                            database.tvShowDao().save(tvShowCopy)
                            Log.i(TAG, "savePosition: tvShow.isWatching=${tvShowCopy.isWatching}, reallyEnded=$reallyEnded, isStillWatching=$isStillWatching")
                        }
                    }

                    Log.i(TAG, "savePosition: EPISODE saved, watched=${episode.isWatched}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "savePosition: error", e)
        }
    }

    /**
     * WLFMOVIE V6.4: Guarda el progreso de una pelicula DESCARGADA vista en el
     * PC — solo en la tabla local `downloads`. Mismo criterio de "terminada"
     * que el player offline del celular (posición >= duración - 10s).
     */
    private suspend fun saveLocalDownloadPosition(position: Long, duration: Long) {
        try {
            val ctx = appContext ?: return
            val downloadId = RemotePlayState.downloadId ?: return
            if (position <= 0L || duration <= 0L) return
            val isWatched = position >= (duration - 10_000L)
            AppDatabase.getInstance(ctx).downloadDao().updateProgress(
                id = downloadId,
                position = position,
                duration = duration,
                isWatched = isWatched,
            )
            Log.i(TAG, "saveLocalDownloadPosition: download=$downloadId pos=$position/$duration watched=$isWatched (solo local, sin sync)")
        } catch (e: Exception) {
            Log.e(TAG, "saveLocalDownloadPosition: error", e)
        }
    }

    /**
     * Pone el próximo episodio en la lista de "continue watching".
     * Réplica de la lógica del player local (PlayerMobileFragment.queueNextEpisodeForContinueWatching).
     */
    private suspend fun queueNextEpisodeForContinueWatching(
        ctx: Context,
        provider: com.mew.wlfmovie.providers.Provider,
        videoType: Video.Type.Episode,
        database: AppDatabase
    ) {
        try {
            // Asegurar que la lista de episodios esté cargada
            com.mew.wlfmovie.utils.EpisodeManager.ensureNextEpisodeAvailable(videoType, database)
            val nextEpisode = com.mew.wlfmovie.utils.EpisodeManager.peekNextEpisode() ?: return
            val episodeDao = database.episodeDao()
            val persistedNextEpisode = episodeDao.getById(nextEpisode.id)?.apply {
                isWatched = false
                watchedDate = null
                watchHistory = WatchItem.WatchHistory(
                    lastEngagementTimeUtcMillis = System.currentTimeMillis(),
                    lastPlaybackPositionMillis = 0L,
                    durationMillis = 0L,
                )
            } ?: com.mew.wlfmovie.models.Episode(
                id = nextEpisode.id,
                number = nextEpisode.number,
                title = nextEpisode.title,
                poster = nextEpisode.poster,
                overview = nextEpisode.overview,
                tvShow = database.tvShowDao().getById(nextEpisode.tvShow.id) ?: com.mew.wlfmovie.models.TvShow(
                    id = nextEpisode.tvShow.id,
                    title = nextEpisode.tvShow.title,
                    poster = nextEpisode.tvShow.poster,
                    banner = nextEpisode.tvShow.banner,
                ),
                season = com.mew.wlfmovie.models.Season(
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
            UserDataCache.syncEpisodeToCache(ctx, provider, persistedNextEpisode)
            Log.i(TAG, "queueNextEpisodeForContinueWatching: queued ep ${nextEpisode.number} of season ${nextEpisode.season.number}")
        } catch (e: Exception) {
            Log.e(TAG, "queueNextEpisodeForContinueWatching: error", e)
        }
    }

    /**
     * Maneja mensajes recibidos del PC vía WebSocket.
     */
    private fun handleWebsocketMessage(json: JSONObject) {
        val type = json.optString("type", "")
        Log.i(TAG, "handleWebsocketMessage: type=$type, json=$json")

        when (type) {
            "ready" -> {
                RemotePlayState.setState(RemotePlayState.ConnectionState.PLAYING)
            }
            "ping" -> {
                Log.v(TAG, "handleWebsocketMessage: ping recibido")
            }
            "position" -> {
                val position = json.optLong("position", 0L)
                val duration = json.optLong("duration", 0L)
                val isPlaying = json.optBoolean("isPlaying", false)
                RemotePlayState.updateRemotePosition(position, duration, isPlaying)
                // Guardar en DB + nube inmediatamente (en cada reporte del PC)
                onPositionReport(position, duration, isPlaying)
            }
            "ended" -> {
                Log.i(TAG, "handleWebsocketMessage: video ended")
                RemotePlayState.videoEnded = true
                RemotePlayState.updateRemotePosition(
                    position = RemotePlayState.currentDuration,
                    duration = RemotePlayState.currentDuration,
                    isPlaying = false
                )
                // Guardar inmediatamente con el flag videoEnded=true
                // para que tvShow.isWatching se actualice correctamente
                onPositionReport(
                    position = RemotePlayState.currentDuration,
                    duration = RemotePlayState.currentDuration,
                    isPlaying = false
                )
            }
            "error" -> {
                Log.e(TAG, "handleWebsocketMessage: error del PC: ${json.optString("message", "")}")
            }
            "disconnected" -> {
                Log.i(TAG, "handleWebsocketMessage: PC se desconectó")
                RemotePlayState.setState(RemotePlayState.ConnectionState.WAITING)
            }
        }
    }
}

