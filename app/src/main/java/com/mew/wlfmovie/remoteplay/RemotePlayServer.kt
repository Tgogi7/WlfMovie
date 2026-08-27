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

class RemotePlayServer(
    private val context: Context,
    port: Int = 8080
) : NanoWSD(port) {

    companion object {
        private const val TAG = "WlfMovie-RemoteServer"
        private const val PORT = 8080
    }

    private val videoProxy = VideoProxy()
    private var webSocket: NanoWSD.WebSocket? = null

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
            newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "text/html; charset=utf-8",
                ByteArrayInputStream(html),
                html.size.toLong()
            )
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
        }
        Log.i(TAG, "serveInfo: $json")
        return newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json; charset=utf-8",
            json.toString()
        )
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
            newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                mimeType,
                ByteArrayInputStream(data),
                data.size.toLong()
            )
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
     * - .ts → se genera un m3u8 SINTÉTICO con segmentos por rango de bytes;
     *   hls.js del PC pide cada rango a /localfile con header Range.
     */
    private fun serveLocalStream(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
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
            val baseUrl = RemotePlayState.serverUrl.value ?: "http://localhost:8080"
            val playlist = LocalMediaPlaylist.build(
                file,
                "$baseUrl/localfile",
                RemotePlayState.currentDuration
            )
            if (playlist == null) {
                Log.e(TAG, "serveLocalStream: no se pudo generar la playlist sintetica")
                return newFixedLengthResponse(
                    NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                    "application/json",
                    "{\"error\":\"No se pudo leer el archivo descargado\"}"
                )
            }
            Log.i(TAG, "serveLocalStream: playlist local generada (${playlist.segmentCount} segmentos, fmp4=${playlist.isFmp4})")
            newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/vnd.apple.mpegurl",
                playlist.content
            )
        }
    }

    /**
     * /localfile — sirve los bytes del archivo descargado. Endpoint usado por
     * hls.js para pedir cada segmento virtual (via header Range) y por el
     * modo .mp4 nativo. Responde 206 Partial Content cuando hay Range.
     */
    private fun serveLocalFile(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val path = RemotePlayState.localFilePath
        if (path.isNullOrBlank()) {
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                "{\"error\":\"No hay archivo local cargado\"}"
            )
        }
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) {
            Log.e(TAG, "serveLocalFile: archivo no existe: $path")
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "text/plain",
                "404 archivo no encontrado"
            )
        }
        val mimeType = if (path.endsWith(".mp4", ignoreCase = true)) "video/mp4" else "video/mp2t"
        return serveFileRange(file, session, mimeType)
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
