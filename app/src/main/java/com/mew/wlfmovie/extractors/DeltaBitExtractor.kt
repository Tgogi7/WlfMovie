package com.mew.wlfmovie.extractors

import com.mew.wlfmovie.models.Video
import com.mew.wlfmovie.utils.DnsResolver
import com.mew.wlfmovie.utils.NetworkClient
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class DeltaBitExtractor : Extractor() {

    override val name = "DeltaBit"
    override val mainUrl = "https://deltabit.co"

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .cookieJar(NetworkClient.cookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override suspend fun extract(link: String): Video {
        // 1. Initial GET request (follow redirects)
        val getReq = Request.Builder()
            .url(link)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()

        val (html1, finalUrl) = httpClient.newCall(getReq).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("DeltaBit GET failed with code ${resp.code}")
            Pair(resp.body?.string().orEmpty(), resp.request.url.toString())
        }

        // Form parameter extraction
        val hash = Regex("""name=["']hash["']\s+value=["']([^"']+)["']""").find(html1)?.groupValues?.get(1)
            ?: throw Exception("DeltaBit: hash not found in page")
        val id = Regex("""name=["']id["']\s+value=["']([^"']+)["']""").find(html1)?.groupValues?.get(1)
            ?: finalUrl.trimEnd('/').substringAfterLast('/')
        val fname = Regex("""name=["']fname["']\s+value=["']([^"']+)["']""").find(html1)?.groupValues?.get(1).orEmpty()
        val fileId = hash.substringBefore("-")

        // Wait for server countdown (5 seconds)
        delay(5500)

        // 2. POST request to unlock the video player
        val formBody = FormBody.Builder()
            .add("op", "download1")
            .add("usr_login", "")
            .add("id", id)
            .add("fname", fname)
            .add("referer", "")
            .add("hash", hash)
            .add("imhuman", "")
            .build()

        val postReq = Request.Builder()
            .url("https://deltabit.co/$id")
            .post(formBody)
            .header("User-Agent", DEFAULT_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Origin", "https://deltabit.co")
            .header("Referer", finalUrl)
            .header("Cookie", "file_id=$fileId; aff=5; ref_url=$finalUrl")
            .build()

        val html2 = httpClient.newCall(postReq).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("DeltaBit POST failed with code ${resp.code}")
            resp.body?.string().orEmpty()
        }

        // 3. Video source extraction
        val videoSource = Regex("""sources:\s*\[\s*["']([^"']+)["']""").find(html2)?.groupValues?.get(1)
            ?: throw Exception("DeltaBit: video source not found in response")

        return Video(
            source = videoSource,
            headers = mapOf(
                "User-Agent" to DEFAULT_USER_AGENT,
                "Referer" to "https://deltabit.co/"
            )
        )
    }
}
