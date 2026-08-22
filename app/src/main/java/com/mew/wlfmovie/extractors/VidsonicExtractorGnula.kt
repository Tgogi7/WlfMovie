package com.mew.wlfmovie.extractors

import android.util.Log
import com.mew.wlfmovie.models.Video
import org.jsoup.Jsoup

/**
 * WLFMOVIE V6: Extractor Vidsonic para Gnula (separado del VidsonicExtractor original).
 *
 * Extrae la URL del video del HTML de Vidsonic decodificando el string hex.
 * Devuelve headers con Referer y Origin correctos para que ExoPlayer pueda reproducir.
 */
class VidsonicExtractorGnula : Extractor() {

    override val name = "Vidsonic HD"
    override val mainUrl = "https://vidsonic.net"

    companion object {
        private const val TAG = "WlfMovie-VidsonicGnula"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    override suspend fun extract(link: String): Video {
        Log.i(TAG, "Extracting: $link")

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val html = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val request = okhttp3.Request.Builder()
                .url(link)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Vidsonic HD: Empty response")
            if (!response.isSuccessful) {
                throw Exception("Vidsonic HD: HTTP ${response.code}")
            }
            body
        }

        // Buscar el string codificado en el HTML
        val encodedMatch = Regex("'([a-fA-F0-9|]{60,})'").find(html)
            ?: throw Exception("Vidsonic HD: No se encontró el string codificado en el HTML")

        val cleaned = encodedMatch.groupValues[1].replace("|", "")

        // Decodificar hex a ASCII
        val asciiBuilder = StringBuilder()
        for (i in cleaned.indices step 2) {
            val hexPair = cleaned.substring(i, i + 2)
            asciiBuilder.append(hexPair.toInt(16).toChar())
        }

        // La URL está invertida
        val sourceUrl = asciiBuilder.toString().reversed()

        Log.i(TAG, "Extraído: ${sourceUrl.take(80)}...")

        // IMPORTANTE: Vidsonic requiere Referer y Origin para reproducir
        return Video(
            source = sourceUrl,
            headers = mapOf(
                "Referer" to mainUrl,
                "Origin" to mainUrl
            )
        )
    }
}
