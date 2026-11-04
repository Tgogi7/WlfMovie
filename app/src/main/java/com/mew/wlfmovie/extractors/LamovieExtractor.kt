package com.mew.wlfmovie.extractors

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.mew.wlfmovie.models.Video
import com.mew.wlfmovie.utils.JsUnpacker
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

class LamovieExtractor : Extractor() {

    override val name = "Lamovie"
    override val mainUrl = "https://lamovie.link"
    override val aliasUrls = listOf("https://vimeos.net")

    override suspend fun extract(link: String): Video {
        val service = Service.build(mainUrl)

        val document = service.get(link)
        val html = document.toString()

        val packedJS = Regex("(eval\\(function\\(p,a,c,k,e,d\\)(.|\\n)*?)</script>")
            .find(html)?.let { it.groupValues[1] }

        val (streamUrl, subtitles) = if (!packedJS.isNullOrBlank()) {
            val unPacked = JsUnpacker(packedJS).unpack()
                ?: throw Exception("Can't unpack js")

            val fileMatch = Regex("""file\s*:\s*["']([^"']+)["']""").find(unPacked)
            val streamUrl = fileMatch?.groupValues?.get(1)
                ?: throw Exception("No file found")

            val subtitles = Regex("""file\s*:\s*["']([^"']+)["']\s*,\s*label\s*:\s*["']([^"']+)["']""").findAll(
                Regex("""tracks\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(unPacked)
                    ?.groupValues?.get(1)
                    ?: ""
            ).map {
                Video.Subtitle(
                    label = it.groupValues[2],
                    file = it.groupValues[1],
                )
            }.filter { it.label != "Upload captions" }
            .toList()

            Pair(streamUrl, subtitles)
        } else {
            val streamMatch = Regex("""stream_urls["']?\s*:\s*\[\s*["']([^"']+)["']""").find(html)
            val streamUrl = streamMatch?.groupValues?.get(1)
                ?: throw Exception("No video source found")

            val subtitles = mutableListOf<Video.Subtitle>()
            val subsMatch = Regex("""default_subs["']?\s*:\s*(\[.*?\])""").find(html)?.groupValues?.get(1)
            if (!subsMatch.isNullOrBlank()) {
                try {
                    val subsArr = JSONArray(subsMatch)
                    for (i in 0 until subsArr.length()) {
                        val subObj = subsArr.optJSONObject(i) ?: continue
                        val subUrl = subObj.optString("url")
                        val label = subObj.optString("lang").ifBlank { subObj.optString("code") }
                        if (subUrl.isNotBlank()) {
                            subtitles.add(Video.Subtitle(label = label, file = subUrl))
                        }
                    }
                } catch (_: Exception) {
                }
            }

            Pair(streamUrl, subtitles)
        }

        return Video(
            source = streamUrl,
            subtitles = subtitles,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
                "Accept-Language" to "en-US,en;q=0.9",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors"
            )
        )
    }


    private interface Service {

        companion object {
            fun build(baseUrl: String): Service {
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()

                return retrofit.create(Service::class.java)
            }
        }

        @GET
        @Headers(
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
            "Accept-Language: en-US,en;q=0.9"
        )
        suspend fun get(@Url url: String): Document
    }
}