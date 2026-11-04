package com.mew.wlfmovie.extractors

import androidx.media3.common.MimeTypes
import com.mew.wlfmovie.models.Video
import com.mew.wlfmovie.utils.NetworkClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

class MovieMasterExtractor : Extractor() {

    override val name = "Moviemaster"
    override val mainUrl = "https://api.moviemaster.lat"

    private val service = Service.build(mainUrl)

    companion object {
        private val LANGUAGE_ORDER = listOf("latino", "castellano", "subtitulado")
        private val LANGUAGE_LABELS = mapOf(
            "latino" to "Latino",
            "castellano" to "Castellano",
            "subtitulado" to "Subtitulado"
        )
    }

    suspend fun servers(videoType: Video.Type): List<Video.Server> {
        val url = when (videoType) {
            is Video.Type.Movie -> "$mainUrl/e/movie/${videoType.id}"
            is Video.Type.Episode -> "$mainUrl/e/tv/${videoType.tvShow.id}/${videoType.season.number}/${videoType.number}"
        }

        return try {
            val embedHtml = service.get(
                url = url,
                referer = "$mainUrl/",
                secFetchDest = "iframe",
                secFetchMode = "navigate"
            )

            val pmUi = Regex("""window\.PM_UI\s*=\s*["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1)
                ?: return emptyList()

            val mediaParams = when (videoType) {
                is Video.Type.Movie -> "tmdb=${videoType.id}&type=movie"
                is Video.Type.Episode -> "tmdb=${videoType.tvShow.id}&type=tv&season=${videoType.season.number}&episode=${videoType.number}"
            }

            val serversJsonStr = service.get("$mainUrl/servers?$mediaParams&ui=$pmUi")

            val serversArray = JSONObject(serversJsonStr).optJSONArray("servers") ?: return emptyList()

            val languageGroups = linkedMapOf<String, MutableList<String>>()
            for (i in 0 until serversArray.length()) {
                val serverObj = serversArray.optJSONObject(i) ?: continue
                val serverId = serverObj.optString("id").takeIf { it.isNotEmpty() } ?: continue
                val lang = serverObj.optString("lang").uppercase()
                val langKey = when (lang) {
                    "LAT" -> "latino"
                    "ESP" -> "castellano"
                    "SUB" -> "subtitulado"
                    else -> lang.lowercase()
                }
                languageGroups.getOrPut(langKey) { mutableListOf() }.add(serverId)
            }

            val allLangs = languageGroups.keys.toList()
            val orderedLangs = LANGUAGE_ORDER.filter { it in allLangs } + (allLangs - LANGUAGE_ORDER.toSet())

            orderedLangs.flatMap { langKey ->
                val serverIds = languageGroups[langKey] ?: return@flatMap emptyList()
                val langLabel = LANGUAGE_LABELS[langKey] ?: langKey.replaceFirstChar { it.uppercase() }

                serverIds.mapIndexed { index, serverId ->
                    Video.Server(
                        id = serverId,
                        name = "Moviemaster · Opción ${index + 1} · $langLabel",
                        src = "$mainUrl/video?$mediaParams&embed=$serverId&noiframe=1&r=1"
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun extract(link: String): Video {
        val jsonStr = service.get(link)

        val videoUrl = JSONObject(jsonStr).optString("url")
        if (videoUrl.isEmpty()) {
            throw Exception("Stream URL not found in MovieMaster response")
        }

        return Video(
            source = videoUrl,
            type = MimeTypes.APPLICATION_M3U8,
            headers = mapOf("Referer" to "$mainUrl/")
        )
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service {
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(ScalarsConverterFactory.create())
                    .client(NetworkClient.default)
                    .build()

                return retrofit.create(Service::class.java)
            }
        }

        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String? = null,
            @Header("Sec-Fetch-Dest") secFetchDest: String = "empty",
            @Header("Sec-Fetch-Mode") secFetchMode: String = "cors",
        ): String
    }
}
