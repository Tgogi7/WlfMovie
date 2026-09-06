package com.mew.wlfmovie.remoteplay

import android.content.Context
import android.util.Log
import com.mew.wlfmovie.models.Video
import com.mew.wlfmovie.utils.BypassWebSocketEndpointHelper
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Locale
import java.util.Vector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RemotePlayServer(
    private val context: Context,
    port: Int = 8080
) : NanoWSD(port) {

    companion object {
        private const val TAG = "WlfMovie-RemoteServer"
        private const val PORT = 8080

        /** Indices de archivos locales conservados en el registro (LRU). */
        private const val MAX_LOCAL_INDEXES = 3

        /** V6.5.0: tope de espera del /stream por la preparacion en caliente. */
        private const val PREPARE_AWAIT_SECONDS = 180L
    }

    private val videoProxy = VideoProxy()
    private var webSocket: NanoWSD.WebSocket? = null

    // =================================================================
    // WLFMOVIE V6.4.3: registro de indices por IDENTIDAD de archivo.
    //
    // Antes (V6.4.1/2) habia UN solo indice cacheado cuya clave incluia
    // `RemotePlayState.currentDuration` — un valor que se REESCRIBE en cada
    // reporte de posicion del PC (updateRemotePosition) — y el archivo a
    // servir se resolvia por ESTADO DE SESION (`localFilePath`). Resultado:
    // el indice podia reconstruirse en plena reproduccion, y cualquier
    // cambio de sesion (re-cast, cambio de pelicula, reset) movia el piso
    // debajo de un player que estaba descargando segmentos → los bytes de
    // /localsegment?i=N dejaban de corresponder al m3u8 que ese player
    // tenia → fragParsingError "Found no media in fragment" a los N
    // segmentos, SOLO cuando la sesion original ya no estaba viva.
    //
    // Ahora: la clave es SOLO path|len|mtime (estable por archivo), cada
    // indice tiene una VERSION (hash de 8 hex) que viaja en la URL de cada
    // segmento (/localsegment?v=XXXX&i=N), y el servidor resuelve POR
    // VERSION. Se conservan los ultimos MAX_LOCAL_INDEXES indices: si el
    // usuario castea otra pelicula mientras un player todavia reproduce la
    // anterior, ese player SIGUE recibiendo los bytes correctos.
    // =================================================================
    // V6.5.1: sin "private" — prepareLocalFile() (public) lo devuelve y
    // RemotePlayController lee entry.index para el diagnóstico de purga;
    // privado, K2 lo rechaza dos veces ("exposes private-in-class return
    // type" + "cannot access" en el Controller).
    class LocalIndexEntry(
        val key: String,                       // "path|len|mtime"
        val file: File,
        val index: LocalMediaPlaylist.LocalIndex,
        val version: String,                   // 8 hex — viaja en las URLs
    )

    private val localIndexLock = Any()
    private val localIndexRegistry = LinkedHashMap<String, LocalIndexEntry>() // version → entry (LRU)
    private var localIndexFailedKey: String? = null  // ultimo archivo cuya indexacion fallo (evita reintentar caro en cada request)

    // =================================================================
    // V6.5.0: preparacion EN CALIENTE del archivo local.
    //
    // La caminata de purga lee el archivo COMPLETO (basura intercalada →
    // corridas limpias + discontinuidades): en un .ts de 2GB puede tardar
    // varios segundos. El RemotePlayController la dispara apenas arranca el
    // cast (prepareLocalFile, con progreso a la UI); si el /stream del PC
    // llega antes de que termine, espera al ticket (awaitLocalPreparation)
    // en vez de disparar una caminata DUPLICADA sobre el mismo archivo.
    // =================================================================
    private val prepareLock = Any()
    private val prepareTickets = HashMap<String, CountDownLatch>()

    // Callback que se invoca cuando llega un mensaje del PC
    var onMessage: ((JSONObject) -> Unit)? = null

    fun startWithUrl(): String? {
        return try {
            // SOCKET_READ_TIMEOUT default de NanoHTTPD es 5000ms, lo que CIERRA
            // el WebSocket después de 5s sin actividad. Para WebSocket necesitamos
            // timeout infinito (0) — el WebSocket se mantiene con ping/pong.
            start(0)
            Log.i(TAG, "startWithUrl: server levantado en puerto $PORT (timeout=0 para WS)")
            val ip = BypassWebSocketEndpointHelper.getLocalIpv4Address()
            if (ip != null) {
                "http://$ip:$PORT"
            } else {
                Log.e(TAG, "startWithUrl: no se pudo obtener IP local")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "startWithUrl: error levantando server", e)
            null
        }
    }

    fun stopServer() {
        try {
            webSocket?.close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "server stopped", false)
            webSocket = null
            stop()
            Log.i(TAG, "stopServer: server detenido")
        } catch (e: Exception) {
            Log.e(TAG, "stopServer: error", e)
        }
    }

    /**
     * Envía un mensaje JSON al PC vía WebSocket.
     */
    fun sendMessage(json: JSONObject) {
        try {
            val ws = webSocket
            if (ws != null && ws.isOpen) {
                ws.send(json.toString())
                Log.i(TAG, "sendMessage: ${json.toString().take(200)}")
            } else {
                Log.w(TAG, "sendMessage: WebSocket no conectado, mensaje descartado")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage: error", e)
        }
    }

    /**
     * Devuelve la URL del WebSocket (ws://ip:8080/ws)
     */
    fun getWebSocketUrl(): String? {
        val ip = BypassWebSocketEndpointHelper.getLocalIpv4Address() ?: return null
        return "ws://$ip:$PORT/ws"
    }

    // ===== NanoWSD: manejo de WebSocket en /ws =====

    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri ?: "/"
        // Si es upgrade a WebSocket, NanoWSD lo maneja
        if (uri == "/ws") {
            return super.serve(session)
        }
        // Resto de rutas HTTP normales
        val method = session.method
        Log.i(TAG, "serve: $method $uri")

        return when {
            uri == "/" || uri.isEmpty() -> serveIndex()
            uri == "/info" -> serveInfo()
            uri == "/stream" -> serveStream(session)
            uri == "/localfile" -> serveLocalFile(session)
            uri == "/localsegment" -> serveLocalSegment(session)
            uri.startsWith("/proxy") -> serveProxyAsset(session)
            uri.startsWith("/assets/") -> serveAsset(uri.removePrefix("/assets/"))
            uri == "/logo" -> serveLogo()
            else -> {
                Log.w(TAG, "serve: 404 para $uri")
                newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
            }
        }
    }

    override fun openWebSocket(handshake: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket {
        Log.i(TAG, "openWebSocket: nueva conexión desde ${handshake.remoteIpAddress}")
        return RemotePlayWebSocket(this, handshake)
    }

    /**
     * WebSocket handler — recibe mensajes del PC y los pasa al callback.
     */
    private class RemotePlayWebSocket(
        private val server: RemotePlayServer,
        handshake: NanoHTTPD.IHTTPSession
    ) : NanoWSD.WebSocket(handshake) {

        override fun onOpen() {
            Log.i(TAG, "WebSocket onOpen")
            server.webSocket = this
            RemotePlayState.setState(RemotePlayState.ConnectionState.CONNECTED)
            // Enviar video_info al PC apenas conecta
            server.sendVideoInfo()
        }

        override fun onClose(
            code: NanoWSD.WebSocketFrame.CloseCode,
            reason: String?,
            initiatedByRemote: Boolean
        ) {
            Log.i(TAG, "WebSocket onClose: code=$code, reason=$reason, byRemote=$initiatedByRemote")
            server.webSocket = null
            if (RemotePlayState.connectionState.value == RemotePlayState.ConnectionState.CONNECTED ||
                RemotePlayState.connectionState.value == RemotePlayState.ConnectionState.PLAYING) {
                RemotePlayState.setState(RemotePlayState.ConnectionState.WAITING)
            }
        }

        override fun onMessage(frame: NanoWSD.WebSocketFrame) {
            val message = frame.textPayload
            Log.i(TAG, "WebSocket onMessage: ${message.take(300)}")
            try {
                val json = JSONObject(message)
                server.onMessage?.invoke(json)
            } catch (e: Exception) {
                Log.e(TAG, "WebSocket onMessage: error parseando JSON", e)
            }
        }

        override fun onPong(frame: NanoWSD.WebSocketFrame) {
            Log.v(TAG, "WebSocket onPong")
        }

        override fun onException(exception: IOException) {
            Log.e(TAG, "WebSocket onException", exception)
        }
    }

    /**
     * Envía video_info al PC con título, subtítulo, URL del stream y posición inicial.
     */
    fun sendVideoInfo() {
        val video = RemotePlayState.currentVideo ?: return
        val videoType = RemotePlayState.currentVideoType ?: return

        // Resetear flag de "video terminado" — es un video nuevo
        RemotePlayState.videoEnded = false

        val title = when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title
        }
        val subtitle = when (videoType) {
            is Video.Type.Movie -> ""
            is Video.Type.Episode -> "S${videoType.season.number} E${videoType.number}" +
                (videoType.title?.let { " · $it" } ?: "")
        }

        val json = JSONObject().apply {
            put("type", "video_info")
            put("title", title)
            put("subtitle", subtitle)
            put("url", "/stream")
            put("position", RemotePlayState.currentPosition)
            put("duration", RemotePlayState.currentDuration)
            put("isHls", resolveIsHls(video.source))
            // WLFMOVIE V6.6.0: le dice a la página si lo que llega es una
            // DESCARGA (archivo local). Con eso el player del PC entra en
            // MODO NATIVO: video a pantalla completa con los controles del
            // propio navegador (cero diseño propio), conservando el WebSocket
            // (posiciones cada 10s, play/pause/seek/stop desde el celular).
            put("isLocal", RemotePlayState.isLocalFile)
        }
        sendMessage(json)
    }

    /**
     * WLFMOVIE V6.4: decide si el PC debe usar hls.js o video nativo.
     * - Online: HLS si el source es .m3u8 (histórico).
     * - Archivo local (.ts descargado): SIEMPRE HLS — /stream devuelve un
     *   m3u8 sintético con byteranges (el navegador no reproduce .ts crudo).
     * - Archivo local .mp4 (descargas viejas de Remux): video nativo con Range.
     */
    private fun resolveIsHls(sourceUrl: String): Boolean {
        return if (RemotePlayState.isLocalFile) {
            RemotePlayState.localFilePath?.endsWith(".ts", ignoreCase = true) ?: false
        } else {
            sourceUrl.contains(".m3u8", ignoreCase = true)
        }
    }

    /**
     * Envía comando play al PC.
     */
    fun sendPlay() {
        sendMessage(JSONObject().put("type", "play"))
        RemotePlayState.setState(RemotePlayState.ConnectionState.PLAYING)
        // Actualizar isPlaying inmediatamente para que el botón cambie de icono
        // sin esperar al próximo reporte de posición (que tarda hasta 10s)
        RemotePlayState.updateRemotePosition(
            position = RemotePlayState.remotePosition.value,
            duration = RemotePlayState.remoteDuration.value,
            isPlaying = true
        )
    }

    /**
     * Envía comando pause al PC.
     */
    fun sendPause() {
        sendMessage(JSONObject().put("type", "pause"))
        RemotePlayState.setState(RemotePlayState.ConnectionState.CONNECTED)
        RemotePlayState.updateRemotePosition(
            position = RemotePlayState.remotePosition.value,
            duration = RemotePlayState.remoteDuration.value,
            isPlaying = false
        )
    }

    /**
     * Envía comando seek al PC.
     */
    fun sendSeek(positionMs: Long) {
        sendMessage(JSONObject().put("type", "seek").put("position", positionMs))
    }

    /**
     * Envía comando stop al PC (le pide que cierre el video).
     */
    fun sendStop() {
        sendMessage(JSONObject().put("type", "stop"))
    }

    // ===== HTTP endpoints =====

    private fun serveIndex(): NanoHTTPD.Response {
        return try {
            val html = context.assets.open("remote_player/index.html").use { it.readBytes() }
            val resp = newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "text/html; charset=utf-8",
                ByteArrayInputStream(html),
                html.size.toLong()
            )
            // V6.4.2: nunca cachear — tras actualizar la app el PC debe recibir
            // SIEMPRE el player nuevo (el cache del browser era una fuente de
            // "reinstale y sigue igual").
            addNoStoreHeaders(resp)
            resp
        } catch (e: Exception) {
            Log.e(TAG, "serveIndex: error leyendo index.html", e)
            newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Error cargando player: ${e.message}"
            )
        }
    }

    private fun serveInfo(): NanoHTTPD.Response {
        val video = RemotePlayState.currentVideo
        val videoType = RemotePlayState.currentVideoType
        if (video == null || videoType == null) {
            Log.e(TAG, "serveInfo: no hay currentVideo/currentVideoType")
            val json = JSONObject().apply {
                put("error", "no video loaded")
            }
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                json.toString()
            )
        }

        val title = when (videoType) {
            is Video.Type.Movie -> videoType.title
            is Video.Type.Episode -> videoType.tvShow.title
        }
        val subtitle = when (videoType) {
            is Video.Type.Movie -> ""
            is Video.Type.Episode -> "S${videoType.season.number} E${videoType.number}" +
                (videoType.title?.let { " · $it" } ?: "")
        }

        // Incluir la URL del WebSocket para que el PC se conecte
        val wsUrl = getWebSocketUrl()
        val json = JSONObject().apply {
            put("title", title)
            put("subtitle", subtitle)
            put("position", RemotePlayState.currentPosition)
            put("duration", RemotePlayState.currentDuration)
            put("isHls", resolveIsHls(video.source))
            put("wsUrl", wsUrl ?: "")
            // WLFMOVIE V6.6.0: modo nativo del player del PC para descargas.
            put("isLocal", RemotePlayState.isLocalFile)
        }
        Log.i(TAG, "serveInfo: $json")
        val resp = newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json; charset=utf-8",
            json.toString()
        )
        addNoStoreHeaders(resp)
        return resp
    }

    private fun serveAsset(path: String): NanoHTTPD.Response {
        return try {
            val mimeType = when {
                path.endsWith(".css") -> "text/css; charset=utf-8"
                path.endsWith(".js") -> "application/javascript; charset=utf-8"
                path.endsWith(".html") -> "text/html; charset=utf-8"
                path.endsWith(".svg") -> "image/svg+xml"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".ico") -> "image/x-icon"
                else -> "application/octet-stream"
            }
            val data = context.assets.open("remote_player/$path").use { it.readBytes() }
            val resp = newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                mimeType,
                ByteArrayInputStream(data),
                data.size.toLong()
            )
            addNoStoreHeaders(resp)
            resp
        } catch (e: Exception) {
            Log.e(TAG, "serveAsset: error leyendo $path", e)
            newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Asset not found: $path")
        }
    }

    private fun serveLogo(): NanoHTTPD.Response {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="120" height="40" viewBox="0 0 120 40">
                <defs>
                    <linearGradient id="g" x1="0" y1="0" x2="1" y2="0">
                        <stop offset="0%" stop-color="#9b59ff"/>
                        <stop offset="100%" stop-color="#ff3df0"/>
                    </linearGradient>
                </defs>
                <text x="60" y="28" font-family="Inter, sans-serif" font-size="22" font-weight="bold" text-anchor="middle" fill="url(#g)">WlfMovie</text>
            </svg>
        """.trimIndent()
        return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "image/svg+xml", svg)
    }

    private fun serveStream(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        // WLFMOVIE V6.4: Modo LOCAL (pelicula descargada) — servir el archivo
        // del disco directamente por la LAN. CERO internet: no se toca el
        // VideoProxy ni la URL del provider. El camino online queda intacto.
        if (RemotePlayState.isLocalFile) {
            return serveLocalStream(session)
        }

        val video = RemotePlayState.currentVideo
        if (video == null) {
            Log.e(TAG, "serveStream: no hay currentVideo en RemotePlayState")
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                "{\"error\":\"No video loaded\"}"
            )
        }

        val rangeHeader = session.headers?.entries
            ?.firstOrNull { it.key.equals("range", ignoreCase = true) }
            ?.value

        Log.i(TAG, "serveStream: video.source=${video.source}, range=$rangeHeader")

        val sourceUrl = video.source
        val isHls = sourceUrl.contains(".m3u8", ignoreCase = true)

        if (isHls) {
            val serverBaseUrl = RemotePlayState.serverUrl.value ?: "http://localhost:8080"
            val rewritten = videoProxy.fetchAndRewriteM3u8(sourceUrl, video.headers, serverBaseUrl)
            if (rewritten == null) {
                Log.e(TAG, "serveStream: no se pudo fetchear/reescribir el m3u8")
                return newFixedLengthResponse(
                    NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                    "application/json",
                    "{\"error\":\"No se pudo cargar el manifest HLS\"}"
                )
            }
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/vnd.apple.mpegurl",
                rewritten
            )
        }

        val proxyStream = videoProxy.openProxyStream(sourceUrl, video.headers, rangeHeader)
        if (proxyStream == null) {
            Log.e(TAG, "serveStream: no se pudo abrir proxy stream")
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                "{\"error\":\"No se pudo conectar al source\"}"
            )
        }

        val mimeType = video.type ?: "video/mp4"
        val response = newChunkedResponse(NanoHTTPD.Response.Status.OK, mimeType, proxyStream)
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Cache-Control", "no-cache, no-store")
        response.addHeader("Connection", "close")
        return response
    }

    // =================================================================
    // WLFMOVIE V6.4: Servidor de archivo LOCAL (pelicula descargada)
    // =================================================================

    /**
     * /stream en modo local.
     * - .mp4 → se sirve directo con soporte Range (el navegador lo reproduce
     *   nativo, seek instantáneo).
     * - .ts → m3u8 SINTÉTICO con segmentos AUTOCONTENIDOS (V6.4.1): cada
     *   segmento se sirve por /localsegment?v=X&i=N con su PAT/PMT sintético
     *   (MPEG-TS) o su init via EXT-X-MAP (fMP4/CMAF). Cero internet: los
     *   bytes salen del disco del teléfono.
     *
     * V6.4.3: soporta ?v=<version> para servir el m3u8 de un archivo ESPECÍFICO
     * del registro (le permite a un player re-pedir SU playlist aunque la
     * sesión ya castee otra cosa). Sin ?v → el archivo actual de la sesión.
     * Las URLs dentro del m3u8 son RELATIVAS (se resuelven contra el host por
     * el que llegó el request — inmune a cambios de IP / doble interfaz
     * Wi-Fi+hotspot) y VERSIONADAS (inmunes a cambios de sesión y a la caché
     * del navegador del PC).
     */
    private fun serveLocalStream(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val params = session.parameters ?: emptyMap()
        val vParam = params["v"]?.firstOrNull()

        // 1) ¿Pidieron el m3u8 de una versión específica del registro?
        val entry = if (!vParam.isNullOrBlank()) findEntryByVersion(vParam) else null
        if (entry != null) {
            Log.i(
                TAG,
                "serveLocalStream: m3u8 POR VERSIÓN v=${entry.version} " +
                    "(${entry.file.name}, ${entry.index.segments.size} segmentos)"
            )
            val resp = newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/vnd.apple.mpegurl",
                LocalMediaPlaylist.buildM3u8(entry.index, entry.version)
            )
            addNoStoreHeaders(resp)
            return resp
        }
        if (!vParam.isNullOrBlank()) {
            Log.w(TAG, "serveLocalStream: versión v=$vParam desconocida — cayendo al archivo actual de la sesión")
        }

        // 2) Sin versión (o desconocida) → archivo ACTUAL de la sesión.
        val path = RemotePlayState.localFilePath
        if (path.isNullOrBlank()) {
            Log.e(TAG, "serveLocalStream: no hay localFilePath")
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                "{\"error\":\"No hay archivo local cargado\"}"
            )
        }
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) {
            Log.e(TAG, "serveLocalStream: archivo no existe: $path")
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                "{\"error\":\"El archivo descargado no existe en el dispositivo\"}"
            )
        }

        return if (path.endsWith(".mp4", ignoreCase = true)) {
            Log.i(TAG, "serveLocalStream: sirviendo MP4 local directo (${file.length()} bytes)")
            serveFileRange(file, session, "video/mp4")
        } else {
            // V6.5.0: esperar la preparacion en caliente si esta en curso (la
            // caminata de purga del .ts puede tardar varios segundos en
            // archivos grandes) — asi no disparamos una caminata duplicada.
            awaitLocalPreparation(file)
            val currentEntry = getLocalIndexEntry(file)
            if (currentEntry != null) {
                // V6.4.1: m3u8 con segmentos AUTOCONTENIDOS — cada
                // /localsegment?v=X&i=N lleva su propio PAT/PMT sintético (TS)
                // o su init via EXT-X-MAP (fMP4). Esto arregla el
                // fragParsingError ("Found no media in fragment N") de los
                // byteranges crudos.
                val idx = currentEntry.index
                if (idx.junkRuns > 0 || idx.clockBreaks > 0) {
                    Log.i(
                        TAG,
                        "serveLocalStream: PURGA v=${currentEntry.version} (${file.name}): " +
                            "se saltaran ${idx.junkBytes}B de basura en ${idx.junkRuns} huecos y " +
                            "${idx.clockBreaks} saltos de reloj (marcados como discontinuidades)"
                    )
                }
                val m3u8 = LocalMediaPlaylist.buildM3u8(idx, currentEntry.version)
                Log.i(
                    TAG,
                    "serveLocalStream: m3u8 ${idx.segments.size} segmentos autocontenidos " +
                        "v=${currentEntry.version} (fmp4=${idx.isFmp4}, " +
                        "prologo=${idx.tsPrologue?.size ?: 0}B, " +
                        "total=${"%.1f".format(idx.totalDurationSec)}s)"
                )
                val resp = newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/vnd.apple.mpegurl",
                    m3u8
                )
                addNoStoreHeaders(resp)
                resp
            } else {
                // Ultimo recurso: byteranges crudos (comportamiento V6.4.0)
                Log.w(TAG, "serveLocalStream: indice no disponible — fallback byterange")
                val legacy = LocalMediaPlaylist.buildLegacyByterangeM3u8(
                    file,
                    "localfile",
                    RemotePlayState.currentDuration
                )
                val resp = newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/vnd.apple.mpegurl",
                    legacy
                )
                addNoStoreHeaders(resp)
                resp
            }
        }
    }

    /**
     * /localfile — sirve los bytes del archivo descargado. Endpoint usado por
     * el fallback legacy (byteranges) y el modo .mp4 nativo (con Range).
     * V6.4.3: soporta ?v=<version> para resolver el archivo desde el registro
     * (independiente de la sesión); sin ?v → archivo actual de la sesión.
     */
    private fun serveLocalFile(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val params = session.parameters ?: emptyMap()
        val vParam = params["v"]?.firstOrNull()
        val entry = if (!vParam.isNullOrBlank()) findEntryByVersion(vParam) else null

        val file: File
        if (entry != null) {
            file = entry.file
        } else {
            val path = RemotePlayState.localFilePath
            if (path.isNullOrBlank()) {
                return newFixedLengthResponse(
                    NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                    "application/json",
                    "{\"error\":\"No hay archivo local cargado\"}"
                )
            }
            file = File(path)
        }
        if (!file.exists() || file.length() <= 0L) {
            Log.e(TAG, "serveLocalFile: archivo no existe: ${file.path}")
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "text/plain",
                "404 archivo no encontrado"
            )
        }
        val mimeType = if (file.name.endsWith(".mp4", ignoreCase = true)) "video/mp4" else "video/mp2t"
        return serveFileRange(file, session, mimeType)
    }

    // =================================================================
    // WLFMOVIE V6.4.3: registro de indices por identidad de archivo
    // =================================================================

    /** Identidad estable del archivo: path|len|mtime. */
    private fun fileIdentityKey(file: File): String =
        "${file.absolutePath}|${file.length()}|${file.lastModified()}"

    /** Version de 8 hex que viaja en las URLs — derivada de la identidad. */
    private fun versionOf(key: String): String =
        String.format(Locale.US, "%08x", key.hashCode().toLong() and 0xFFFFFFFFL)

    /**
     * Indice de segmentos del archivo local, cacheado en el registro por
     * IDENTIDAD (path|len|mtime) — sin valores mutables en la clave (V6.4.2
     * metia currentDuration en la clave y eso cambiaba con cada reporte del
     * PC, reconstruyendo el indice en plena reproduccion).
     * Conserva los ultimos MAX_LOCAL_INDEXES archivos (LRU): un player que
     * sigue descargando segmentos versionados sigue recibiendo SUS bytes
     * aunque la sesion ya castee otra pelicula.
     *
     * V6.5.0: la caminata de purga (lee el archivo ENTERO) corre FUERA del
     * lock — antes sujetaba localIndexLock durante todo el escaneo y bloqueaba
     * requests paralelos (/localsegment sin ?v) durante segundos.
     */
    private fun getLocalIndexEntry(file: File): LocalIndexEntry? {
        val key = fileIdentityKey(file)
        synchronized(localIndexLock) {
            // Cache hit por identidad de archivo
            for (e in localIndexRegistry.values) {
                if (e.key == key) return e
            }
            // Indexacion que ya fallo para ESTE archivo → no reintentar caro
            if (localIndexFailedKey == key) return null
        }
        return buildIndexEntry(file, key)
    }

    /** Construye el indice (caminata de purga incluida) y lo registra. */
    private fun buildIndexEntry(
        file: File,
        key: String,
        onProgress: ((Float) -> Unit)? = null,
    ): LocalIndexEntry? {
        val index = LocalMediaPlaylist.buildIndex(file, RemotePlayState.currentDuration, onProgress)
        if (index == null) {
            Log.w(TAG, "buildIndexEntry: no se pudo indexar ${file.name} — fallback byterange por ahora")
            synchronized(localIndexLock) { localIndexFailedKey = key }
            return null
        }
        val version = versionOf(key)
        synchronized(localIndexLock) {
            localIndexFailedKey = null
            // Carrera: otro hilo pudo registrar el mismo archivo mientras
            // caminabamos → quedarnos con el SUYO (mismo contenido).
            for (e in localIndexRegistry.values) {
                if (e.key == key) return e
            }
            val entry = LocalIndexEntry(key, file, index, version)
            localIndexRegistry[version] = entry
            while (localIndexRegistry.size > MAX_LOCAL_INDEXES) {
                val eldest = localIndexRegistry.keys.first() ?: break
                localIndexRegistry.remove(eldest)
            }
            return entry
        }
    }

    /**
     * V6.5.0: preparacion EN CALIENTE del .ts local — la llama el
     * RemotePlayController apenas arranca el cast (Dispatchers.IO), con el
     * progreso de la caminata hacia la UI. Devuelve el entry registrado (o
     * null si el archivo no se pudo indexar). Idempotente y single-flight:
     * si ya esta cacheado devuelve el cacheado; si hay una caminata en curso
     * para el mismo archivo, la espera en vez de duplicarla.
     */
    fun prepareLocalFile(file: File, onProgress: (Float) -> Unit): LocalIndexEntry? {
        if (!file.exists() || file.length() <= 0L) return null
        val key = fileIdentityKey(file)
        synchronized(localIndexLock) {
            for (e in localIndexRegistry.values) {
                if (e.key == key) return e
            }
        }
        val existing = synchronized(prepareLock) { prepareTickets[key] }
        if (existing != null) {
            try {
                existing.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return getLocalIndexEntry(file)
        }
        val latch = CountDownLatch(1)
        synchronized(prepareLock) { prepareTickets[key] = latch }
        try {
            return buildIndexEntry(file, key, onProgress)
        } finally {
            synchronized(prepareLock) { prepareTickets.remove(key) }
            latch.countDown()
        }
    }

    /**
     * V6.5.0: espera (acotada) a que la preparacion en caliente de [file]
     * termine, si hay una en curso. Sin ticket → devuelve inmediato (el
     * llamador indexara de forma perezosa, como siempre).
     */
    private fun awaitLocalPreparation(file: File) {
        val key = fileIdentityKey(file)
        val ticket = synchronized(prepareLock) { prepareTickets[key] } ?: return
        Log.i(TAG, "awaitLocalPreparation: /stream llego mientras se prepara ${file.name} — esperando")
        try {
            ticket.await(PREPARE_AWAIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Busca un indice del registro por su version (la que viaja en la URL). */
    private fun findEntryByVersion(version: String): LocalIndexEntry? {
        synchronized(localIndexLock) {
            return localIndexRegistry[version]
        }
    }

    /** Headers anti-caché para TODAS las respuestas del modo local. */
    private fun addNoStoreHeaders(resp: NanoHTTPD.Response) {
        resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0")
        resp.addHeader("Pragma", "no-cache")
        resp.addHeader("Expires", "0")
    }

    /**
     * /localsegment?v=X&i=N — sirve el segmento N del archivo local (V6.4.1):
     * - TS: prologo PAT+PMT VERBATIM + los bytes del segmento → cada
     *   segmento es un segmento HLS TS valido por si mismo (autocontenido,
     *   igual que un HLS real). Arregla el fragParsingError de la V6.4.0.
     * - fMP4: los bytes del grupo de fragmentos (el init va aparte, con i=init).
     *
     * V6.4.3: si la URL trae ?v=<version>, el archivo y el indice se resuelven
     * POR VERSION desde el registro — INDEPENDIENTE del estado de la sesión
     * de cast. Este es el corazon del fix: los bytes servidos siempre
     * corresponden al m3u8 que el player tiene en la mano, aunque la sesión
     * se re-castee, cambie de pelicula o se reinicie. Sin ?v (compat): se usa
     * el archivo actual de la sesión.
     */
    private fun serveLocalSegment(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val params = session.parameters ?: emptyMap()
        val vParam = params["v"]?.firstOrNull()
        val iParam = params["i"]?.firstOrNull()
        if (iParam.isNullOrBlank()) {
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                "text/plain",
                "400 falta parametro i"
            )
        }

        // V6.4.3: resolucion del archivo — POR VERSION primero, sesion despues.
        val entry: LocalIndexEntry?
        if (!vParam.isNullOrBlank()) {
            entry = findEntryByVersion(vParam)
            if (entry == null) {
                // Version expulsada del registro LRU (el usuario casteo 3+
                // peliculas mas) o de una sesion anterior. NUNCA servir bytes
                // de OTRO archivo con esta URL: 404 claro y el player recarga.
                Log.w(TAG, "serveLocalSegment: version v=$vParam i=$iParam NO esta en el registro — 404")
                return newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND,
                    "text/plain",
                    "404 version $vParam desconocida — recarga /stream"
                )
            }
        } else {
            val path = RemotePlayState.localFilePath
            if (path.isNullOrBlank()) {
                Log.w(TAG, "serveLocalSegment: sin ?v y sin archivo en la sesion — 503")
                return newFixedLengthResponse(
                    NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                    "application/json",
                    "{\"error\":\"No hay archivo local cargado\"}"
                )
            }
            val file = File(path)
            if (!file.exists() || file.length() <= 0L) {
                Log.w(TAG, "serveLocalSegment: archivo de la sesion no existe: $path — 404")
                return newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND,
                    "text/plain",
                    "404 archivo no encontrado"
                )
            }
            entry = getLocalIndexEntry(file) ?: run {
                return newFixedLengthResponse(
                    NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                    "text/plain",
                    "503 archivo local no indexable"
                )
            }
        }

        val file = entry.file
        val index = entry.index

        // Segmento de inicializacion fMP4 (EXT-X-MAP)
        if (iParam == "init") {
            if (!index.isFmp4 || index.fmp4InitEnd <= 0L) {
                return newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND,
                    "text/plain",
                    "404 sin segmento init (no es fMP4)"
                )
            }
            val len = index.fmp4InitEnd
            val stream = boundedFileInputStream(file, 0L, len)
                ?: return newFixedLengthResponse(
                    NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "500 error leyendo init"
                )
            val resp = newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "video/mp4",
                stream,
                len
            )
            addNoStoreHeaders(resp)
            return resp
        }

        val i = iParam.toIntOrNull() ?: run {
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                "text/plain",
                "400 i invalido"
            )
        }
        if (i < 0 || i >= index.segments.size) {
            Log.w(TAG, "serveLocalSegment: i=$i fuera de rango (total=${index.segments.size}) v=${entry.version}")
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "text/plain",
                "404 segmento fuera de rango (i=$i, total=${index.segments.size})"
            )
        }
        val seg = index.segments[i]

        // Log compacto: init, primer segmento, cada 100 y el ultimo
        if (i == 0 || i == index.segments.size - 1 || i % 100 == 0) {
            Log.i(
                TAG,
                "serveLocalSegment: v=${entry.version} i=$i/${index.segments.size} " +
                    "off=${seg.offset} len=${seg.length} (${file.name})"
            )
        }

        if (index.isFmp4) {
            val stream = boundedFileInputStream(file, seg.offset, seg.length)
                ?: return newFixedLengthResponse(
                    NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "500 error leyendo segmento"
                )
            val resp = newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "video/iso.segment",
                stream,
                seg.length
            )
            addNoStoreHeaders(resp)
            return resp
        }

        // MPEG-TS: prologo verbatim (PAT+PMT) + bytes del archivo.
        // V6.5.0: el segmento se sirve como CONCATENACION DE RANGOS del
        // archivo original — la caminata de purga dejo la basura entre
        // segmentos, nunca dentro: lo que llega al PC es TS limpio 100%
        // alineado, aunque el .ts del telefono tenga basura intercalada.
        val prologue = index.tsPrologue
        val ranges = seg.ranges ?: listOf(LocalMediaPlaylist.ByteRange(seg.offset, seg.length))
        var totalBytes = 0L
        for (r in ranges) totalBytes += r.length
        val streamList = Vector<InputStream>()
        if (prologue != null) {
            streamList.add(ByteArrayInputStream(prologue))
        }
        for (r in ranges) {
            val s = boundedFileInputStream(file, r.offset, r.length)
            if (s == null) {
                // Cerrar lo ya abierto antes de fallar.
                for (st in streamList) runCatching { st.close() }
                return newFixedLengthResponse(
                    NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "500 error leyendo segmento"
                )
            }
            streamList.add(s)
        }
        val total = (prologue?.size ?: 0).toLong() + totalBytes
        val resp = newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "video/mp2t",
            SequenceInputStream(streamList.elements()),
            total
        )
        addNoStoreHeaders(resp)
        return resp
    }

    /**
     * Sirve un archivo del disco con soporte HTTP Range (206).
     * Sin header Range → 200 con el archivo completo.
     */
    private fun serveFileRange(file: File, session: NanoHTTPD.IHTTPSession, mimeType: String): NanoHTTPD.Response {
        val fileLen = file.length()
        val rangeHeader = session.headers?.entries
            ?.firstOrNull { it.key.equals("range", ignoreCase = true) }
            ?.value

        Log.i(TAG, "serveFileRange: ${file.name}, len=$fileLen, range=$rangeHeader")

        if (rangeHeader != null) {
            val parsed = parseRange(rangeHeader, fileLen)
            if (parsed == null) {
                val resp = newFixedLengthResponse(
                    NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE,
                    "text/plain",
                    "416 Range Not Satisfiable"
                )
                resp.addHeader("Content-Range", "bytes */$fileLen")
                return resp
            }
            val (start, end) = parsed
            val length = end - start + 1
            val stream = boundedFileInputStream(file, start, length)
                ?: return newFixedLengthResponse(
                    NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "500 error leyendo archivo"
                )
            val resp = newFixedLengthResponse(
                NanoHTTPD.Response.Status.PARTIAL_CONTENT,
                mimeType,
                stream,
                length
            )
            resp.addHeader("Content-Range", "bytes $start-$end/$fileLen")
            resp.addHeader("Accept-Ranges", "bytes")
            return resp
        }

        val stream = boundedFileInputStream(file, 0L, fileLen)
            ?: return newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "text/plain",
                "500 error leyendo archivo"
            )
        val resp = newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            mimeType,
            stream,
            fileLen
        )
        resp.addHeader("Accept-Ranges", "bytes")
        resp.addHeader("Cache-Control", "no-cache, no-store")
        return resp
    }

    /**
     * Parsea un header "Range: bytes=A-B" | "bytes=A-" | "bytes=-S".
     * Devuelve (start, end) inclusivos, o null si es inválido/fuera de rango.
     */
    private fun parseRange(rangeHeader: String, fileLen: Long): Pair<Long, Long>? {
        val m = Regex("""bytes\s*=\s*(\d*)\s*-\s*(\d*)""").find(rangeHeader) ?: return null
        val a = m.groupValues[1]
        val b = m.groupValues[2]
        return when {
            a.isEmpty() && b.isEmpty() -> null
            a.isEmpty() -> {
                // "bytes=-S" → últimos S bytes
                val suffix = b.toLongOrNull() ?: return null
                if (suffix <= 0L) return null
                val start = maxOf(0L, fileLen - suffix)
                start to (fileLen - 1L)
            }
            else -> {
                val start = a.toLongOrNull() ?: return null
                if (start < 0L || start >= fileLen) return null
                val end = if (b.isEmpty()) {
                    fileLen - 1L
                } else {
                    minOf(b.toLongOrNull() ?: return null, fileLen - 1L)
                }
                if (start > end) null else start to end
            }
        }
    }

    /**
     * InputStream sobre [file] que empieza en [start] y devuelve como máximo
     * [length] bytes (EOF lógico al llegar al límite). null si falla el seek.
     */
    private fun boundedFileInputStream(file: File, start: Long, length: Long): InputStream? {
        return try {
            val fis = FileInputStream(file)
            var skipped = 0L
            while (skipped < start) {
                val s = fis.skip(start - skipped)
                if (s <= 0L) break
                skipped += s
            }
            if (skipped < start) {
                fis.close()
                Log.e(TAG, "boundedFileInputStream: no se pudo saltar a $start (llegó a $skipped)")
                return null
            }
            object : FilterInputStream(fis) {
                private var remaining = length
                override fun read(): Int {
                    if (remaining <= 0L) return -1
                    val r = super.read()
                    if (r >= 0) remaining--
                    return r
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0L) return -1
                    val toRead = minOf(len.toLong(), remaining).toInt()
                    val r = super.read(b, off, toRead)
                    if (r > 0) remaining -= r
                    return r
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "boundedFileInputStream: error", e)
            null
        }
    }

    private fun serveProxyAsset(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val video = RemotePlayState.currentVideo
        if (video == null) {
            Log.e(TAG, "serveProxyAsset: no hay currentVideo")
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                "{\"error\":\"No video loaded\"}"
            )
        }

        val params = session.parameters ?: emptyMap()
        val targetUrl = params["url"]?.firstOrNull()
        if (targetUrl.isNullOrBlank()) {
            Log.e(TAG, "serveProxyAsset: falta parámetro url")
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                "application/json",
                "{\"error\":\"Falta parámetro url\"}"
            )
        }

        Log.i(TAG, "serveProxyAsset: targetUrl=$targetUrl")

        if (targetUrl.contains(".m3u8", ignoreCase = true)) {
            val serverBaseUrl = RemotePlayState.serverUrl.value ?: "http://localhost:8080"
            val rewritten = videoProxy.fetchAndRewriteM3u8(targetUrl, video.headers, serverBaseUrl)
            if (rewritten == null) {
                Log.e(TAG, "serveProxyAsset: no se pudo fetchear/reescribir m3u8")
                return newFixedLengthResponse(
                    NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                    "application/json",
                    "{\"error\":\"No se pudo cargar sub-playlist\"}"
                )
            }
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/vnd.apple.mpegurl",
                rewritten
            )
        }

        val rangeHeader = session.headers?.entries
            ?.firstOrNull { it.key.equals("range", ignoreCase = true) }
            ?.value

        val proxyStream = videoProxy.openProxyStream(targetUrl, video.headers, rangeHeader)
        if (proxyStream == null) {
            Log.e(TAG, "serveProxyAsset: no se pudo abrir proxy stream para $targetUrl")
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                "{\"error\":\"No se pudo conectar al asset\"}"
            )
        }

        val mimeType = when {
            targetUrl.contains(".ts", ignoreCase = true) -> "video/mp2t"
            targetUrl.contains(".m4s", ignoreCase = true) -> "video/iso.segment"
            targetUrl.contains(".mp4", ignoreCase = true) -> "video/mp4"
            targetUrl.contains(".key", ignoreCase = true) -> "application/octet-stream"
            else -> "application/octet-stream"
        }

        val response = newChunkedResponse(NanoHTTPD.Response.Status.OK, mimeType, proxyStream)
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Cache-Control", "no-cache, no-store")
        response.addHeader("Connection", "close")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }
}
