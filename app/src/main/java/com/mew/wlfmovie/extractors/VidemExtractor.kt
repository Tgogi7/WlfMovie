package com.mew.wlfmovie.extractors

import android.net.Uri
import androidx.media3.common.MimeTypes
import com.mew.wlfmovie.models.Video
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

class VidemExtractor : Extractor() {
    override val name = "Videm"
    override val mainUrl = "https://videm.xyz"

    suspend fun servers(videoType: Video.Type, language: String): List<Video.Server> {
        val url = when (videoType) {
            is Video.Type.Movie -> "$mainUrl/embed/movie/${videoType.id}"
            is Video.Type.Episode -> "$mainUrl/embed/tv/${videoType.tvShow.id}/${videoType.season.number}/${videoType.number}"
        }

        val targetLang = when (language) {
            "es" -> "Spanish"
            "fr" -> "French"
            "en" -> "English"
            else -> return emptyList()
        }

        return try {
            val service = Service.build(mainUrl)
            val html = service.get(url)

            val qJsonStr = Regex("var Q = (\\{.*?\\});", setOf(RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)
                ?: throw Exception("Q JSON not found in HTML!")
            
            val qJson = JSONObject(qJsonStr)
            val t = qJson.optString("t")
            
            val ssr = qJson.optJSONObject("ssr") ?: return emptyList()
            val serversArr = ssr.optJSONArray("servers") ?: return emptyList()
            
            val result = mutableListOf<Video.Server>()
            
            for (i in 0 until serversArr.length()) {
                val srv = serversArr.optJSONObject(i) ?: continue
                val lang = srv.optString("lang", "")
                
                if (lang.equals(targetLang, ignoreCase = true)) {
                    val ref = srv.optString("ref")
                    val name = srv.optString("name", "Server")
                    
                    if (ref.isNotEmpty()) {
                        result.add(Video.Server(
                            id = "videm-$ref",
                            name = "$name (Videm)",
                            src = "$mainUrl/api.php?a=play&ref=$ref&t=$t"
                        ))
                    }
                }
            }
            
            result
        } catch (e: Exception) {
            android.util.Log.e("VidemExtractor", "Error fetching servers for $url", e)
            emptyList()
        }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    override suspend fun extract(link: String): Video {
        // link is the direct api.php URL constructed in servers()
        val service = Service.build(mainUrl)
        val playResponseStr = service.get(link)
        val playJson = JSONObject(playResponseStr)
        val streamUrlPath = playJson.optString("url")
        
        if (streamUrlPath.isEmpty()) {
            throw Exception("Stream URL not found in Videm API response")
        }
        
        val finalUrl = "$mainUrl$streamUrlPath"
        
        return Video(
            source = finalUrl,
            type = MimeTypes.APPLICATION_M3U8,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        )
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", "$baseUrl/")
                            .build()
                        chain.proceed(request)
                    }
                    .build()
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(ScalarsConverterFactory.create())
                    .client(client)
                    .build()

                return retrofit.create(Service::class.java)
            }
        }

        @GET
        suspend fun get(@Url url: String): String
    }
}
