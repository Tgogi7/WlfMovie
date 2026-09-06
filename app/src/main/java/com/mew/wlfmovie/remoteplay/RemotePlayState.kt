package com.mew.wlfmovie.remoteplay

import android.util.Log
import com.mew.wlfmovie.models.Video
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Estado compartido del RemotePlay.
 *
 * Singleton observable — tanto el Fragment como el Service lo observan para
 * saber en qué estado está el cast al PC.
 */
object RemotePlayState {

    private const val TAG = "WlfMovie-RemotePlay"

    enum class ConnectionState {
        IDLE,        // No hay cast activo
        STARTING,    // Levantando server
        WAITING,     // Server arriba, esperando conexión del PC
        CONNECTED,   // PC conectó, enviando video info
        PLAYING,     // PC reproduciendo
        STOPPING,    // Deteniendo
        ERROR        // Error
    }

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    // Posición actual del video en el PC (actualizada vía WebSocket cada 10s)
    private val _remotePosition = MutableStateFlow(0L)
    val remotePosition: StateFlow<Long> = _remotePosition.asStateFlow()

    private val _remoteDuration = MutableStateFlow(0L)
    val remoteDuration: StateFlow<Long> = _remoteDuration.asStateFlow()

    private val _remoteIsPlaying = MutableStateFlow(false)
    val remoteIsPlaying: StateFlow<Boolean> = _remoteIsPlaying.asStateFlow()

    // V6.5.0: progreso (0..100) de la preparación en caliente del .ts local
    // para el envío al PC (caminata de purga: corridas limpias + discontinuidades).
    // -1 = no aplica / terminó con fallo / cast online o .mp4.
    private val _localPrepProgress = MutableStateFlow(-1)
    val localPrepProgress: StateFlow<Int> = _localPrepProgress.asStateFlow()

    fun updateLocalPrep(percent: Int) {
        _localPrepProgress.value = percent
    }

    // Info del video actual (seteado por el Fragment al iniciar cast)
    var currentVideo: Video? = null
    var currentServer: Video.Server? = null
    var currentVideoType: Video.Type? = null
    var currentPosition: Long = 0L
    var currentDuration: Long = 0L

    // WLFMOVIE V6.4: Modo LOCAL (compartir pelicula DESCARGADA al PC).
    // true cuando el video.source es file:// — el server sirve el archivo
    // del disco directamente por la LAN (cero internet), y el progreso se
    // guarda SOLO en la tabla `downloads` (nada de nube ni continue watching).
    var isLocalFile: Boolean = false
    var localFilePath: String? = null      // path absoluto del archivo (sin file://)
    var downloadId: String? = null         // = movie.id en la tabla downloads

    // Flag: true cuando el PC manda {type:"ended"} — el video terminó naturalmente
    var videoEnded: Boolean = false

    fun setState(state: ConnectionState) {
        Log.i(TAG, "setState: $_connectionState → $state")
        _connectionState.value = state
    }

    fun setServerUrl(url: String?) {
        Log.i(TAG, "setServerUrl: $url")
        _serverUrl.value = url
    }

    fun updateRemotePosition(position: Long, duration: Long, isPlaying: Boolean) {
        _remotePosition.value = position
        _remoteDuration.value = duration
        _remoteIsPlaying.value = isPlaying
        currentPosition = position
        currentDuration = duration
    }

    fun reset() {
        Log.i(TAG, "reset")
        currentVideo = null
        currentServer = null
        currentVideoType = null
        currentPosition = 0L
        currentDuration = 0L
        videoEnded = false
        isLocalFile = false
        localFilePath = null
        downloadId = null
        _localPrepProgress.value = -1
        _serverUrl.value = null
        _connectionState.value = ConnectionState.IDLE
        _remotePosition.value = 0L
        _remoteDuration.value = 0L
        _remoteIsPlaying.value = false
    }
}
