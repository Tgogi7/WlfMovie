package com.mew.wlfmovie.extractors

import android.media.MediaCodecList
import android.media.MediaFormat
import com.mew.wlfmovie.models.Video
import androidx.media3.common.MimeTypes
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

class VidloveExtractor : Extractor() {
    override val name = "Vidlove"
    override val mainUrl = "https://api.vidlove.cc"

    companion object {
        val supportsHevc: Boolean by lazy {
            try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                    !info.isEncoder && info.supportedTypes.any { type ->
                        type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                    }
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    data class ServerConfig(
        val name: String,
        val sourceKey: String
    )

    private val serversConfig = listOf(
        ServerConfig("Barbarian King 2.0", "moviebox2"),
        ServerConfig("Barbarian King", "moviebox"),
        ServerConfig("Electro Dragon", "dcloud"),
        ServerConfig("Ice Wizard", "tcloud"),
        ServerConfig("Archer Queen", "vidapi")
    )

    fun servers(videoType: Video.Type): List<Video.Server> {
        return serversConfig
            .filter { config ->
                // moviebox2 only provides HEVC streams on Vidlove; exclude if device has no HEVC support
                config.sourceKey != "moviebox2" || supportsHevc
            }
            .map { config ->
                val extraParams = when (config.sourceKey) {
                    "moviebox", "moviebox2" -> if (supportsHevc) "&hevc=1&vh=1" else "&vh=1"
                    "dcloud" -> "&sw=1"
                    else -> ""
                }
            val url = when (videoType) {
                is Video.Type.Movie -> {
                    "$mainUrl/movie?id=${videoType.id}&mode=json&sources=${config.sourceKey}$extraParams"
                }
                is Video.Type.Episode -> {
                    "$mainUrl/tv?id=${videoType.tvShow.id}&season=${videoType.season.number}&episode=${videoType.number}&mode=json&sources=${config.sourceKey}$extraParams"
                }
            }
            
            Video.Server(
                id = "${config.name} (Vidlove)",
                name = "${config.name} (Vidlove)",
                src = url
            )
        }
    }

    override suspend fun extract(link: String): Video {
        val service = Service.build(mainUrl)
        val jsonString = service.get(link)
        val json = JSONObject(jsonString)

        val sourceObj = json.optJSONObject("source") ?: throw Exception("No video source found")
        val url = sourceObj.optString("url")
        if (url.isNullOrEmpty()) {
            throw Exception("No video source found")
        }

        val subtitlesList = mutableListOf<Video.Subtitle>()
        val subtitlesArray = json.optJSONArray("subtitles")
        if (subtitlesArray != null) {
            for (i in 0 until subtitlesArray.length()) {
                val sub = subtitlesArray.optJSONObject(i) ?: continue
                val label = sub.optString("label", "Unknown")
                val file = sub.optString("file")
                if (file.isNotEmpty()) {
                    subtitlesList.add(Video.Subtitle(label, file))
                }
            }
        }

        return Video(
            source = url,
            type = MimeTypes.APPLICATION_M3U8,
            subtitles = subtitlesList,
            headers = mapOf("Referer" to "https://player.vidlove.cc/")
        )
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder().build()
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(ScalarsConverterFactory.create())
                    .client(client)
                    .build()

                return retrofit.create(Service::class.java)
            }
        }

        @Headers(
            "Origin: https://player.vidlove.cc",
            "Referer: https://player.vidlove.cc/"
        )
        @GET
        suspend fun get(
            @Url url: String
        ): String
    }
}
