package com.mew.wlfmovie.extractors

import com.google.gson.JsonParser
import com.mew.wlfmovie.models.Video
import com.mew.wlfmovie.utils.DnsResolver
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.jsoup.parser.Parser
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import java.net.URL

class HexloadExtractor : Extractor() {

    override val name = "Hexload"
    override val mainUrl = "https://hexload.com"

    override suspend fun extract(link: String): Video {
        val pathSegments = URL(link).path.split("/").filter { it.isNotEmpty() }
        val targetSegment = pathSegments.firstOrNull { it.startsWith("embed-") }
            ?: pathSegments.firstOrNull { it != "e" && it != "d" && it != "embed" }
            ?: throw Exception("File code not found in URL")
        val fileCode = targetSegment.removePrefix("embed-").substringBefore(".")

        if (fileCode.isEmpty()) {
            throw Exception("File code is empty in URL: $link")
        }

        val baseUrl = URL(link).protocol + "://" + URL(link).host
        val service = Service.build(baseUrl)

        val responseBody = service.getDownload(
            op = "download3",
            id = fileCode,
            ajax = "1",
            methodFree = "1",
            dataType = "json",
            referer = link
        )
        val responseString = responseBody.string()

        val jsonObject = try {
            JsonParser.parseString(responseString).asJsonObject
        } catch (e: Exception) {
            throw Exception("Failed to parse API response: ${e.message}")
        }

        val result = jsonObject.getAsJsonObject("result")
            ?: throw Exception("Result not found in API response")

        val rawUrl = result.get("url")?.asString
            ?: throw Exception("Video URL not found in API response")

        val videoUrl = Parser.unescapeEntities(rawUrl, false)

        return Video(
            source = videoUrl
        )
    }

    private interface Service {

        companion object {
            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .build()

                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                return retrofit.create(Service::class.java)
            }
        }

        @POST("download")
        @FormUrlEncoded
        @Headers("x-requested-with: XMLHttpRequest")
        suspend fun getDownload(
            @Field("op") op: String,
            @Field("id") id: String,
            @Field("ajax") ajax: String,
            @Field("method_free") methodFree: String,
            @Field("dataType") dataType: String,
            @Header("Referer") referer: String
        ): ResponseBody
    }
}
