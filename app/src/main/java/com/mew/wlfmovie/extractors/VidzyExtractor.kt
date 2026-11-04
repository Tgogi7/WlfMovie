package com.mew.wlfmovie.extractors

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.mew.wlfmovie.models.Video
import com.mew.wlfmovie.utils.JsUnpacker
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import com.mew.wlfmovie.utils.DnsResolver
import com.mew.wlfmovie.utils.UserPreferences
import okhttp3.OkHttpClient

class VidzyExtractor : Extractor() {

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }

    override val name = "Vidzy"
    override val mainUrl = "https://vidzy.org"

    fun extractSubtitles(text: String): List<Video.Subtitle> {
        val loadTracksRegex = Regex("""loadTracks\s*\(\s*\[(.*?)]\s*\)""")
        val tracksContent = loadTracksRegex.find(text)?.groupValues?.get(1) ?: return emptyList()

        val objectRegex = Regex("""\{(.*?)\}""")

        return objectRegex.findAll(tracksContent).mapNotNull { match ->
            val obj = match.groupValues[1]

            val label = Regex("""label:'([^']+)'""").find(obj)?.groupValues?.get(1)
            val file = Regex("""src:'([^']+)'""").find(obj)?.groupValues?.get(1)
            val default = Regex("""default:(true|false)""").find(obj)?.groupValues?.get(1)?.toBoolean() ?: false

            if (label == null || file == null || !file.startsWith("http")) return@mapNotNull null
            Video.Subtitle(
                file = file,
                label = label,
                initialDefault = default,
                default = if (UserPreferences.serverAutoSubtitlesDisabled) false else default
            )
        }.toList()
    }

    override suspend fun extract(link: String): Video {
        val service = Service.build(mainUrl)

        val document = service.get(link)
        val html = document.html()
        var streamUrl: String? = null

        // Find the IIFE pattern: src: (function(s){...})(payload)
        // Use two-step approach: locate "src: (function(" then find the IIFE's closing call arg
        // to handle nested braces in the function body (try/catch, etc.)
        val srcFuncStart = Regex("""src:\s*\(function\([a-zA-Z]\)""").find(html)
        if (srcFuncStart != null) {
            // Find the closing pattern: })("payload") after the function start
            // Search from function start position
            val searchRegion = html.substring(srcFuncStart.range.first)
            // Match the IIFE closing call: })(["']...["'])
            val iifeTailMatch = Regex("""\}\)\(["']([^"']+)["']\)""").find(searchRegion)
            if (iifeTailMatch != null) {
                val payload = iifeTailMatch.groupValues[1]
                val domain = runCatching { java.net.URI(link).host }.getOrNull() ?: ""

                val calcMatch = Regex("""width:calc\(1in\s*\+\s*(\d+)px\)""").find(html)
                val pxVal = calcMatch?.groupValues?.get(1)?.toIntOrNull() ?: 136
                val bc = (96 + pxVal) / 2

                val decrypted = decryptSource(payload, domain, bc)
                if (decrypted.startsWith("http://") || decrypted.startsWith("https://")) {
                    streamUrl = decrypted
                }
            }
        }

        if (streamUrl == null || (!streamUrl.startsWith("http://") && !streamUrl.startsWith("https://"))) {
            throw Exception("Can't retrieve source")
        }

        return Video(
            source = streamUrl,
            headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to USER_AGENT
            ),
            subtitles = extractSubtitles(html),
            useServerSubtitleSetting = true
        )
    }

    private fun decryptSource(payload: String, domain: String, bc: Int): String {
        var H = 0
        for (char in domain) H = (H + char.code) and 255

        val decoded = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        val sb = java.lang.StringBuilder(decoded.size)

        for (i in decoded.indices.reversed()) {
            val kk = (0x3d + (decoded.lastIndex - i) * 89 + H + bc) and 255
            val byteVal = decoded[i].toInt() and 0xFF
            sb.append((byteVal xor kk).toChar())
        }
        return sb.toString()
    }

    private interface Service {
        companion object {
            val client = OkHttpClient.Builder()
                .dns(DnsResolver.doh)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .apply {
                            UserPreferences.currentProvider?.baseUrl?.let { header("Referer", it) }
                        }
                        .build()
                    chain.proceed(request)
                }
                .build()
            fun build(baseUrl: String): Service {
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()

                return retrofit.create(Service::class.java)
            }
        }

        @GET
        suspend fun get(@Url url: String): Document
    }
}
