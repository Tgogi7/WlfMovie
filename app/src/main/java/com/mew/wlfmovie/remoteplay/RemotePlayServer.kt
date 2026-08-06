package com.mew.wlfmovie.remoteplay

import android.content.Context
import android.util.Log
import com.mew.wlfmovie.models.Video
import com.mew.wlfmovie.utils.BypassWebSocketEndpointHelper
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream

class RemotePlayServer(
    private val context: Context,
    port: Int = 8080
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "WlfMovie-RemoteServer"
        private const val PORT = 8080
    }

    private val videoProxy = VideoProxy()

    fun startWithUrl(): String? {
        return try {
            start(SOCKET_READ_TIMEOUT)
            Log.i(TAG, "startWithUrl: server levantado en puerto $PORT")
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
            stop()
            Log.i(TAG, "stopServer: server detenido")
        } catch (e: Exception) {
            Log.e(TAG, "stopServer: error", e)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method
        Log.i(TAG, "serve: $method $uri")

        return when {
            uri == "/" || uri.isEmpty() -> serveIndex()
            uri == "/info" -> serveInfo()
            uri == "/stream" -> serveStream(session)
            uri.startsWith("/proxy") -> serveProxyAsset(session)
            uri.startsWith("/assets/") -> serveAsset(uri.removePrefix("/assets/"))
            uri == "/logo" -> serveLogo()
            else -> {
                Log.w(TAG, "serve: 404 para $uri")
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
            }
        }
    }

    private fun serveIndex(): Response {
        return try {
            val html = context.assets.open("remote_player/index.html").use { it.readBytes() }
            newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                ByteArrayInputStream(html),
                html.size.toLong()
            )
        } catch (e: Exception) {
            Log.e(TAG, "serveIndex: error leyendo index.html", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Error cargando player: ${e.message}"
            )
        }
    }

    private fun serveInfo(): Response {
        val video = RemotePlayState.currentVideo
        val videoType = RemotePlayState.currentVideoType
        if (video == null || videoType == null) {
            Log.e(TAG, "serveInfo: no hay currentVideo/currentVideoType")
            val json = JSONObject().apply {
                put("error", "no video loaded")
            }
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
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

        val json = JSONObject().apply {
            put("title", title)
            put("subtitle", subtitle)
            put("position", RemotePlayState.currentPosition)
            put("duration", RemotePlayState.currentDuration)
            put("isHls", video.source.contains(".m3u8", ignoreCase = true))
        }
        Log.i(TAG, "serveInfo: $json")
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            json.toString()
        )
    }

    private fun serveAsset(path: String): Response {
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
                Response.Status.OK,
                mimeType,
                ByteArrayInputStream(data),
                data.size.toLong()
            )
        } catch (e: Exception) {
            Log.e(TAG, "serveAsset: error leyendo $path", e)
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Asset not found: $path")
        }
    }

    private fun serveLogo(): Response {
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
        return newFixedLengthResponse(Response.Status.OK, "image/svg+xml", svg)
    }

    private fun serveStream(session: IHTTPSession): Response {
        val video = RemotePlayState.currentVideo
        if (video == null) {
            Log.e(TAG, "serveStream: no hay currentVideo en RemotePlayState")
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
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
            // Para HLS, fetchear el .m3u8 y reescribir las URLs para que apunten al proxy.
            // Esto evita CORS errors y resuelve URLs relativas.
            val serverBaseUrl = RemotePlayState.serverUrl.value ?: "http://localhost:8080"
            val rewritten = videoProxy.fetchAndRewriteM3u8(sourceUrl, video.headers, serverBaseUrl)
            if (rewritten == null) {
                Log.e(TAG, "serveStream: no se pudo fetchear/reescribir el m3u8")
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    "application/json",
                    "{\"error\":\"No se pudo cargar el manifest HLS\"}"
                )
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/vnd.apple.mpegurl",
                rewritten
            )
        }

        // Para MP4 u otros — stream directo con Range support
        val proxyStream = videoProxy.openProxyStream(sourceUrl, video.headers, rangeHeader)
        if (proxyStream == null) {
            Log.e(TAG, "serveStream: no se pudo abrir proxy stream")
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                "{\"error\":\"No se pudo conectar al source\"}"
            )
        }

        val mimeType = video.type ?: "video/mp4"
        val response = newChunkedResponse(Response.Status.OK, mimeType, proxyStream)
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Cache-Control", "no-cache, no-store")
        response.addHeader("Connection", "close")
        return response
    }

    /**
     * Proxy de assets individuales (.ts segments, sub-playlists .m3u8, etc.)
     *
     * El PC hace GET /proxy?url=<url-encoded-source>
     * → nosotros hacemos GET a esa URL con los headers del provider
     * → reenviamos los bytes al PC
     *
     * Si la URL es un .m3u8, lo reescribimos también (para sub-playlists).
     */
    private fun serveProxyAsset(session: IHTTPSession): Response {
        val video = RemotePlayState.currentVideo
        if (video == null) {
            Log.e(TAG, "serveProxyAsset: no hay currentVideo")
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                "{\"error\":\"No video loaded\"}"
            )
        }

        // Parsear query string para sacar ?url=...
        val params = session.parameters ?: emptyMap()
        val targetUrl = params["url"]?.firstOrNull()
        if (targetUrl.isNullOrBlank()) {
            Log.e(TAG, "serveProxyAsset: falta parámetro url")
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                "{\"error\":\"Falta parámetro url\"}"
            )
        }

        Log.i(TAG, "serveProxyAsset: targetUrl=$targetUrl")

        // Si es .m3u8, reescribir
        if (targetUrl.contains(".m3u8", ignoreCase = true)) {
            val serverBaseUrl = RemotePlayState.serverUrl.value ?: "http://localhost:8080"
            val rewritten = videoProxy.fetchAndRewriteM3u8(targetUrl, video.headers, serverBaseUrl)
            if (rewritten == null) {
                Log.e(TAG, "serveProxyAsset: no se pudo fetchear/reescribir m3u8")
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    "application/json",
                    "{\"error\":\"No se pudo cargar sub-playlist\"}"
                )
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/vnd.apple.mpegurl",
                rewritten
            )
        }

        // Para otros assets (.ts, .key, etc.) — stream directo con Range
        val rangeHeader = session.headers?.entries
            ?.firstOrNull { it.key.equals("range", ignoreCase = true) }
            ?.value

        val proxyStream = videoProxy.openProxyStream(targetUrl, video.headers, rangeHeader)
        if (proxyStream == null) {
            Log.e(TAG, "serveProxyAsset: no se pudo abrir proxy stream para $targetUrl")
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                "{\"error\":\"No se pudo conectar al asset\"}"
            )
        }

        // Detectar mime type
        val mimeType = when {
            targetUrl.contains(".ts", ignoreCase = true) -> "video/mp2t"
            targetUrl.contains(".m4s", ignoreCase = true) -> "video/iso.segment"
            targetUrl.contains(".mp4", ignoreCase = true) -> "video/mp4"
            targetUrl.contains(".key", ignoreCase = true) -> "application/octet-stream"
            else -> "application/octet-stream"
        }

        val response = newChunkedResponse(Response.Status.OK, mimeType, proxyStream)
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Cache-Control", "no-cache, no-store")
        response.addHeader("Connection", "close")
        // CORS headers para que el navegador no se queje
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }
}
