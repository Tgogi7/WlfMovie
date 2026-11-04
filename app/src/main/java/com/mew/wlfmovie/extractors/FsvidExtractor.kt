package com.mew.wlfmovie.extractors

import android.net.Uri
import android.util.Base64
import com.mew.wlfmovie.models.Video
import com.mew.wlfmovie.utils.DnsResolver
import com.mew.wlfmovie.utils.JsUnpacker
import com.mew.wlfmovie.utils.UserPreferences
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url

class FsvidExtractor : Extractor() {
    override val name = "FSVid"
    override val mainUrl = "https://fsvid.lol"

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val referer = UserPreferences.currentProvider?.baseUrl?.takeIf { it.isNotBlank() } ?: "$mainUrl/"
            val newRequest = chain.request()
                .newBuilder()
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Referer", referer)
                .build()
            chain.proceed(newRequest)
        }
        .dns(DnsResolver.doh)
        .build()

    private val service = Retrofit.Builder()
        .baseUrl(mainUrl)
        .addConverterFactory(ScalarsConverterFactory.create())
        .client(client)
        .build()
        .create(FsvidService::class.java)

    private interface FsvidService {
        @GET
        suspend fun get(@Url url: String): String
    }

    private fun decryptSource(fnCode: String, s: String, link: String): String = runCatching {
        val host = Uri.parse(link).host ?: return@runCatching ""
        var H = 0
        for (ch in host) {
            H = (H + ch.code) and 0xFF
        }

        val extraPx = Regex("""calc\(1in\s*\+\s*(\d+)px\)""").find(fnCode)?.groupValues?.get(1)?.toIntOrNull() ?: 56
        val pct = Regex("""width:(\d+)%""").find(fnCode)?.groupValues?.get(1)?.toDoubleOrNull()?.div(100.0) ?: 0.5
        val bc = ((96 + extraPx) * pct).toInt()

        val kkMatch = Regex("""kk\s*=\s*\(\s*(0x[0-9a-fA-F]+|\d+)\s*\+\s*i\s*\*\s*(\d+)""").find(fnCode)
        val baseXor = kkMatch?.groupValues?.get(1)?.let {
            if (it.startsWith("0x", ignoreCase = true)) it.substring(2).toIntOrNull(16) else it.toIntOrNull()
        } ?: 0x3d
        val step = kkMatch?.groupValues?.get(2)?.toIntOrNull() ?: 89

        val decodedBytes = Base64.decode(s, Base64.DEFAULT)
        val reversed = decodedBytes.reversedArray()
        val r = StringBuilder()
        for (i in reversed.indices) {
            val kk = (baseXor + i * step + H + bc) and 0xFF
            val byteVal = reversed[i].toInt() and 0xFF
            r.append((byteVal xor kk).toChar())
        }
        val url = r.toString()
        if (url.startsWith("http")) url else ""
    }.getOrDefault("")

    override suspend fun extract(link: String): Video {
        val html = service.get(link)

        val scriptData = html
            .substringAfter("eval(function(p,a,c,k,e,d)")
            .substringBefore("</script>")
            .let { "eval(function(p,a,c,k,e,d)$it" }

        if (!scriptData.startsWith("eval")) throw Exception("Packed JS not found")
        val unpacked = JsUnpacker(scriptData).unpack() ?: throw Exception("Unpack failed")

        val plainUrl = Regex("""src\s*:\s*["'](https?://[^"']+)["']""").find(unpacked)?.groupValues?.get(1)
        val funcMatch = Regex("""sources:\s*\[\s*\{\s*src\s*:\s*\((function\(s\)\{.*?\})\)\s*\(\s*["']([^"']+)["']""")
            .find(unpacked)
            ?: Regex("""\((function\(s\)\{.*?\})\)\s*\(\s*["']([A-Za-z0-9+/=]+)["']""").find(unpacked)

        val m3u8 = plainUrl ?: funcMatch?.let { match ->
            val fnCode = match.groupValues[1]
            val sArg = match.groupValues[2]
            decryptSource(fnCode, sArg, link)
        }?.takeIf { it.isNotEmpty() }
            ?: throw Exception("Stream URL not found")

        val referer = UserPreferences.currentProvider?.baseUrl?.takeIf { it.isNotBlank() } ?: "$mainUrl/"

        return Video(
            source = m3u8,
            headers = mapOf(
                "User-Agent" to DEFAULT_USER_AGENT,
                "Referer" to referer,
            )
        )
    }
}

