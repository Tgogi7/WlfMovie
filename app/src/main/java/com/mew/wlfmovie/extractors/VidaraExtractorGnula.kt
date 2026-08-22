package com.mew.wlfmovie.extractors

import android.util.Log
import com.google.gson.JsonParser
import com.mew.wlfmovie.models.Video
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL

/**
 * WLFMOVIE V6: Extractor Vidara para Gnula (separado del VidaraExtractor original).
 *
 * Incluye el alias vidaraa.cc que Gnula usa.
 * Llama a la API de Vidara para obtener la URL de streaming.
 */
class VidaraExtractorGnula : Extractor() {

    override val name = "Vidara HD"
    override val mainUrl = "https://vidara.to"
    override val aliasUrls = listOf(
        "https://vidara.so",
        // Alias que Gnula usa:
        "https://vidaraa.cc",
    )

    companion object {
        private const val TAG = "WlfMovie-VidaraGnula"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    override suspend fun extract(link: String): Video {
        val fileCode = URL(link).path.split("/").last { it.isNotEmpty() }
        if (fileCode.isEmpty()) {
            throw Exception("Vidara HD: File code not found in URL: $link")
        }

        val baseUrl = URL(link).protocol + "://" + URL(link).host
        Log.i(TAG, "Extracting: fileCode=$fileCode, baseUrl=$baseUrl")

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val jsonBody = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val mediaType = "application/json".toMediaType()
            val body = "{\"filecode\":\"$fileCode\",\"device\":\"web\"}"
                .toRequestBody(mediaType)
            val request = okhttp3.Request.Builder()
                .url("$baseUrl/api/stream")
                .header("User-Agent", UA)
                .header("Content-Type", "application/json")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: throw Exception("Vidara HD: Empty response from API")
            if (!response.isSuccessful) {
                throw Exception("Vidara HD: HTTP ${response.code} from API")
            }
            respBody
        }

        val json = JsonParser.parseString(jsonBody).asJsonObject
        val streamingUrl = json.get("streaming_url")?.asString
            ?: throw Exception("Vidara HD: streaming_url not found in API response")

        Log.i(TAG, "Extraído: ${streamingUrl.take(80)}...")
        return Video(source = streamingUrl)
    }
}
