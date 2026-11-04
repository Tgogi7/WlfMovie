package com.mew.wlfmovie.extractors

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.mew.wlfmovie.models.Video
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

class VideoappExtractor : Extractor() {

    override val name = "Videoapp"
    override val mainUrl = "https://videoapp.zip"

    fun server(videoType: Video.Type): Video.Server {
        val url = when (videoType) {
            is Video.Type.Episode -> "$mainUrl/e/tv/${videoType.tvShow.id}/${videoType.season.number}/${videoType.number}"
            is Video.Type.Movie -> "$mainUrl/e/movie/${videoType.id}"
        }
        return Video.Server(
            id = url,
            name = "$name [LAT]",
            src = url,
        )
    }

    override suspend fun extract(link: String): Video {
        val service = Service.build(mainUrl)

        val document = service.get(link)

        val iframeSrc = document.selectFirst("#frame")?.attr("src")
            ?: throw Exception("No iframe found on Videoapp")

        return Extractor.extract(iframeSrc)
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
