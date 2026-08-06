package com.mew.wlfmovie.remoteplay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.mew.wlfmovie.models.Video
import org.json.JSONObject

/**
 * Orquestador del RemotePlay.
 *
 * Capa 6+7+8+9: combina:
 * - RemotePlayServer (HTTP + WebSocket) — arrancado por el Controller
 * - RemotePlayService (foreground) — mantiene notificación + wakelock para
 *   que el server sobreviva pantalla apagada / app en background
 *
 * El Controller es el punto de entrada único. El Fragment llama a start(),
 * y observa RemotePlayState.connectionState + RemotePlayState.serverUrl
 * para actualizar la UI.
 */
object RemotePlayController {

    private const val TAG = "WlfMovie-RemoteCtrl"

    private var server: RemotePlayServer? = null

    /**
     * Inicia el cast.
     *
     * 1. Arranca el RemotePlayService (foreground + wakelock)
     * 2. Levanta el server HTTP+WebSocket
     * 3. Devuelve la URL para que el user la abra en el PC
     *
     * @return la URL del server (ej: "http://192.168.1.5:8080") o null si falló
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

        // Guardar estado compartido DESPUÉS de detener el server anterior
        RemotePlayState.currentVideo = video
        RemotePlayState.currentServer = server
        RemotePlayState.currentVideoType = videoType
        RemotePlayState.currentPosition = position
        RemotePlayState.currentDuration = duration
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
            // Detener el service también
            val stopIntent = Intent(context, RemotePlayService::class.java).apply {
                action = RemotePlayService.ACTION_STOP
            }
            context.startService(stopIntent)
            return null
        }

        this.server = newServer
        RemotePlayState.setServerUrl(url)
        RemotePlayState.setState(RemotePlayState.ConnectionState.WAITING)
        Log.i(TAG, "start: server levantado en $url, currentVideo=${RemotePlayState.currentVideo?.source}")
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
     * Detiene el cast completamente: server + service + estado.
     */
    fun stop(context: Context) {
        Log.i(TAG, "stop")
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
     */
    fun sendStop(context: Context) {
        server?.sendStop()
        stop(context)
    }

    /**
     * ¿Está el server levantado?
     */
    fun isRunning(): Boolean = server != null

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
            "position" -> {
                val position = json.optLong("position", 0L)
                val duration = json.optLong("duration", 0L)
                val isPlaying = json.optBoolean("isPlaying", false)
                RemotePlayState.updateRemotePosition(position, duration, isPlaying)
            }
            "ended" -> {
                Log.i(TAG, "handleWebsocketMessage: video ended")
                RemotePlayState.updateRemotePosition(
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
