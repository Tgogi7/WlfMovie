package com.mew.wlfmovie.remoteplay

import android.util.Log
import com.mew.wlfmovie.models.Video
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Proxy del video: hace de middleman entre el source original (URL del provider)
 * y el navegador del PC.
 *
 * El PC hace un GET con header `Range` → nosotros hacemos un GET al source
 * con el mismo Range y los headers del provider → reenviamos los bytes al PC.
 *
 * Esto permite:
 * - Que el PC no toque el source original (que a menudo requiere headers especiales
 *   como User-Agent, Referer, etc. que los navegadores no pueden setear)
 * - Seek: el PC puede pedir cualquier rango
 * - Streaming: reenviamos bytes en chunks, sin bufferar todo en memoria
 *
 * Además, para HLS (.m3u8), reescribe las URLs del manifest para que apunten
 * al proxy del celular — esto evita problemas de CORS en los navegadores.
 */
class VideoProxy {

    companion object {
        private const val TAG = "WlfMovie-VideoProxy"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Cliente para requests del m3u8 (no sigue redirects, queremos ver la URL final).
     */
    private val m3u8Client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Abre un InputStream que hace proxy del video on-demand.
     *
     * @param url la URL a fetchear (puede ser el source original o una URL
     *           relativa resuelta desde el m3u8)
     * @param headers headers del provider (User-Agent, Referer, etc.)
     * @param rangeHeader el valor del header `Range` que mandó el PC (ej: "bytes=0-1023" o null)
     * @return InputStream listo para leer, o null si falló
     */
    fun openProxyStream(
        url: String,
        headers: Map<String, String>?,
        rangeHeader: String?
    ): InputStream? {
        if (url.isBlank()) {
            Log.e(TAG, "openProxyStream: URL vacía")
            return null
        }

        Log.i(TAG, "openProxyStream: url=$url, range=$rangeHeader")

        return try {
            val requestBuilder = Request.Builder().url(url)
            headers?.forEach { (k, v) -> requestBuilder.header(k, v) }
            if (!rangeHeader.isNullOrBlank()) {
                requestBuilder.header("Range", rangeHeader)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.byteStream() ?: run {
                response.close()
                Log.e(TAG, "openProxyStream: sin body en respuesta del source")
                return null
            }

            Log.i(TAG, "openProxyStream: response code=${response.code}")

            object : InputStream() {
                override fun read(): Int = body.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = body.read(b, off, len)
                override fun available(): Int = try { body.available() } catch (_: Exception) { 0 }
                override fun close() {
                    try { body.close() } catch (_: Exception) {}
                    try { response.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "openProxyStream: error", e)
            null
        }
    }

    /**
     * Fetch y reescribe un .m3u8 para que todas las URLs apunten al proxy del celular.
     *
     * - URLs absolutas (https://sv2.ibra.lat/.../video.ts) → /proxy?url=<encoded>
     * - URLs relativas (index-v1-a1.m3u8) → /proxy?url=<base + relative>
     * - URLs relativas con path (subdir/playlist.m3u8) → /proxy?url=<base + subdir/playlist.m3u8>
     *
     * @param m3u8Url la URL del .m3u8 original
     * @param headers headers del provider
     * @param serverBaseUrl "http://192.168.1.5:8080" — la URL base del proxy
     * @return el .m3u8 reescrito como String, o null si falló
     */
    fun fetchAndRewriteM3u8(
        m3u8Url: String,
        headers: Map<String, String>?,
        serverBaseUrl: String
    ): String? {
        Log.i(TAG, "fetchAndRewriteM3u8: url=$m3u8Url, serverBaseUrl=$serverBaseUrl")

        return try {
            val requestBuilder = Request.Builder().url(m3u8Url)
            headers?.forEach { (k, v) -> requestBuilder.header(k, v) }

            m3u8Client.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body ?: run {
                    Log.e(TAG, "fetchAndRewriteM3u8: sin body")
                    return null
                }
                val contentType = body.contentType()?.toString() ?: ""
                val raw = body.string()
                Log.i(TAG, "fetchAndRewriteM3u8: response code=${response.code}, contentType=$contentType, length=${raw.length}")

                // Si es un master playlist con variantes, cada variante también es .m3u8
                // y necesita ser reescrita.
                val baseUrl = resolveBaseUrl(m3u8Url)
                val rewritten = StringBuilder()
                val lines = raw.split("\n")

                for (line in lines) {
                    val trimmed = line.trim()
                    // Líneas de comentario o directives (#EXTM3U, #EXT-X-VERSION, etc.)
                    if (trimmed.startsWith("#")) {
                        // Algunas directives incluyen URLs (#EXT-X-KEY:URI="...", #EXT-X-MAP:URI="...")
                        val uriMatch = Regex("""URI="([^"]+)"""").find(trimmed)
                        if (uriMatch != null) {
                            val originalUri = uriMatch.groupValues[1]
                            val resolved = resolveUrl(baseUrl, originalUri)
                            val rewrittenUri = "$serverBaseUrl/proxy?url=" + java.net.URLEncoder.encode(resolved, "UTF-8")
                            val newLine = trimmed.replace(originalUri, rewrittenUri)
                            rewritten.append(newLine).append("\n")
                        } else {
                            rewritten.append(line).append("\n")
                        }
                    } else if (trimmed.isEmpty()) {
                        rewritten.append("\n")
                    } else {
                        // Es una URL de media (segment .ts o sub-playlist .m3u8)
                        val resolved = resolveUrl(baseUrl, trimmed)
                        val rewrittenUrl = "$serverBaseUrl/proxy?url=" + java.net.URLEncoder.encode(resolved, "UTF-8")
                        rewritten.append(rewrittenUrl).append("\n")
                    }
                }

                val result = rewritten.toString()
                Log.i(TAG, "fetchAndRewriteM3u8: reescrito OK, nueva longitud=${result.length}")
                return result
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchAndRewriteM3u8: error", e)
            null
        }
    }

    /**
     * Resuelve la URL base de un m3u8 (sin el filename).
     * Ej: "https://sv2.ibra.lat/files/7/76b13f71a13/master.m3u8?t=..." → "https://sv2.ibra.lat/files/7/76b13f71a13/"
     */
    private fun resolveBaseUrl(m3u8Url: String): String {
        // Quitar query string
        val withoutQuery = m3u8Url.substringBefore("?")
        // Buscar el último /
        val lastSlash = withoutQuery.lastIndexOf('/')
        return if (lastSlash > 0) withoutQuery.substring(0, lastSlash + 1) else withoutQuery + "/"
    }

    /**
     * Resuelve una URL relativa contra una base.
     * - "https://..." → se deja igual (absoluta)
     * - "//host/path" → se agrega "https:"
     * - "/path" → se agrega scheme + host de la base
     * - "subdir/file.ts" → se concatena con la base
     * - "file.ts" → se concatena con la base
     */
    private fun resolveUrl(baseUrl: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) {
            return relative
        }
        if (relative.startsWith("//")) {
            return "https:" + relative
        }
        if (relative.startsWith("/")) {
            // Path absoluto del host — extraer scheme + host de baseUrl
            val schemeEnd = baseUrl.indexOf("://")
            if (schemeEnd > 0) {
                val hostEnd = baseUrl.indexOf("/", schemeEnd + 3)
                val origin = if (hostEnd > 0) baseUrl.substring(0, hostEnd) else baseUrl
                return origin + relative
            }
            return relative
        }
        // Relativa — concatenar con baseUrl
        return baseUrl + relative
    }
}
