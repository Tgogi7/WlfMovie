package com.mew.wlfmovie.providers

import android.util.Base64
import android.util.Log
import com.mew.wlfmovie.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * WLFMOVIE V6: Provider para GnulaHD.
 *
 * No implementa el interface Provider completo porque Gnula no tiene catálogo.
 * Se llama desde TmdbProvider.getServersFlow() en paralelo con los otros providers.
 *
 * Flujo:
 * 1. Busca el título en la API de búsqueda de Gnula
 * 2. Hace fuzzy match con el resultado
 * 3. Si es serie: construye la URL del episodio ({slug}-{S}x{E:02d}/)
 * 4. Extrae _gnrdPid y _gnrdTok del HTML
 * 5. Llama la API del player
 * 6. Desencripta la respuesta XOR
 * 7. Filtra solo Voe, Vidara y Vidsonic
 * 8. Devuelve Video.Server con nombres "VOE HD", "Vidara HD", "Vidsonic HD"
 */
object GnulaProvider {
    private const val TAG = "WlfMovie-Gnula"
    private const val BASE_URL = "https://ww3.gnulahd.nu"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class SearchResponse(val q: String, val results: List<SearchResult>)

    @Serializable
    private data class SearchResult(
        val title: String,
        val url: String,
        val img: String,
        val type: String,
        val year: String,
    )

    @Serializable
    private data class PlayerResponse(val p: String)

    @Serializable
    private data class PlayerData(val t: String, val langs: List<AudioGroup>)

    @Serializable
    private data class AudioGroup(val flag: String, val label: String, val servers: List<ServerEntry>)

    @Serializable
    private data class ServerEntry(val title: String, val src: String)

    // Dominios que aceptamos (Voe, Vidara, Vidsonic)
    private val acceptedDomains = listOf("voe", "vidara", "vidsonic")

    private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response from Gnula")
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: ${response.message}")
        }
        body
    }

    private fun xorDecrypt(encrypted: String): String {
        val decoded = Base64.decode(encrypted, Base64.DEFAULT)
        val key = byteArrayOf(103, 78, 55, 100) // "gN7d"
        val result = ByteArray(decoded.size) { i ->
            (decoded[i].toInt() xor key[i % 4].toInt()).toByte()
        }
        return String(result, Charsets.UTF_8)
    }

    private fun extractTokens(html: String): Pair<Int, String> {
        val pidRegex = Regex("""[,_]?\s*_gnrdPid\s*=\s*(\d+)""")
        val tokRegex = Regex("""[,_]?\s*_gnrdTok\s*=\s*"([a-f0-9]+)""")

        val pid = pidRegex.find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw Exception("No se encontró _gnrdPid en la página de Gnula")
        val tok = tokRegex.find(html)?.groupValues?.get(1)
            ?: throw Exception("No se encontró _gnrdTok en la página de Gnula")

        return Pair(pid, tok)
    }

    private fun normalize(s: String): String {
        val noAccents = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return noAccents.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isMatch(found: String, target: String): Boolean {
        val nFound = normalize(found)
        val nTarget = normalize(target)
        if (nFound == nTarget) return true
        if (nFound.contains(nTarget) || nTarget.contains(nFound)) return true
        val foundWords = nFound.split(" ").filter { it.length > 2 }.toSet()
        val targetWords = nTarget.split(" ").filter { it.length > 2 }.toSet()
        if (foundWords.isEmpty() || targetWords.isEmpty()) return false
        return foundWords.intersect(targetWords).size.toFloat() / targetWords.size >= 0.5f
    }

    /**
     * Busca servidores de una película o episodio en Gnula HD.
     * Filtra solo Voe, Vidara y Vidsonic.
     * Devuelve Video.Server con nombres "VOE HD", "Vidara HD", "Vidsonic HD".
     */
    suspend fun searchServers(
        title: String,
        isMovie: Boolean,
        seasonNum: Int? = null,
        episodeNum: Int? = null,
    ): List<Video.Server> {
        Log.i(TAG, "searchServers: title=\"$title\", isMovie=$isMovie, S${seasonNum}E${episodeNum}")

        // 1. Buscar en la API de Gnula
        val query = URLEncoder.encode(title, "UTF-8")
        val searchUrl = "$BASE_URL/wp-json/gnrd/v1/search?q=$query"
        val searchBody = try {
            httpGet(searchUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Error en búsqueda: ${e.message}")
            return emptyList()
        }

        val searchResp = try {
            json.decodeFromString<SearchResponse>(searchBody)
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando respuesta de búsqueda: ${e.message}")
            return emptyList()
        }

        Log.i(TAG, "${searchResp.results.size} resultado(s) en Gnula")

        // 2. Fuzzy match
        val expectedType = if (isMovie) "Pelicula" else "Serie"
        val match = searchResp.results.firstOrNull { result ->
            result.type.equals(expectedType, ignoreCase = true) && isMatch(result.title, title)
        }

        if (match == null) {
            Log.i(TAG, "Sin coincidencia para \"$title\" como $expectedType")
            return emptyList()
        }

        Log.i(TAG, "Match: \"${match.title}\" (${match.url})")

        // 3. Construir URL de la página
        val pageUrl = if (!isMovie && seasonNum != null && episodeNum != null) {
            val slug = match.url.trimEnd('/').substringAfterLast('/')
            val epUrl = "$BASE_URL/$slug-${seasonNum}x${String.format("%02d", episodeNum)}/"
            Log.i(TAG, "URL episodio: $epUrl")
            epUrl
        } else {
            match.url
        }

        // 4. Obtener página y extraer tokens
        val pageHtml = try {
            httpGet(pageUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo página: ${e.message}")
            return emptyList()
        }

        val (pid, tok) = try {
            extractTokens(pageHtml)
        } catch (e: Exception) {
            Log.e(TAG, e.message ?: "Error extrayendo tokens")
            return emptyList()
        }

        // 5. Llamar API del player
        val playerUrl = "$BASE_URL/wp-json/gnrd/v1/player?id=$pid&t=$tok"
        val playerBody = try {
            httpGet(playerUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Error en API del player: ${e.message}")
            return emptyList()
        }

        val playerResp = try {
            json.decodeFromString<PlayerResponse>(playerBody)
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando respuesta del player: ${e.message}")
            return emptyList()
        }

        // 6. Desencriptar
        val decrypted = try {
            xorDecrypt(playerResp.p)
        } catch (e: Exception) {
            Log.e(TAG, "Error desencriptando: ${e.message}")
            return emptyList()
        }

        // 7. Parsear servidores
        val playerData = try {
            json.decodeFromString<PlayerData>(decrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando player data: ${e.message}")
            return emptyList()
        }

        // 8. Filtrar solo Voe, Vidara, Vidsonic y mapear a Video.Server
        // Gnula separa por idioma: Latino, Subtitulado, Castellano.
        // Agregamos el idioma al nombre para que no haya duplicados sin distinguish.
        val servers = mutableListOf<Video.Server>()
        for (group in playerData.langs) {
            // Mapear el label del idioma a un tag corto
            val langTag = when {
                group.label.contains("latino", ignoreCase = true) -> "LAT"
                group.label.contains("sub", ignoreCase = true) -> "SUB"
                group.label.contains("cast", ignoreCase = true) -> "CAST"
                group.label.contains("español", ignoreCase = true) ||
                group.label.contains("espanol", ignoreCase = true) -> "ES"
                else -> group.label.uppercase().take(4)
            }

            for (server in group.servers) {
                val domain = try {
                    java.net.URL(server.src).host.lowercase()
                } catch (e: Exception) {
                    server.src.substringBefore("/").lowercase()
                }

                val serverName = when {
                    domain.contains("vidara") -> "Vidara HD [$langTag]"
                    domain.contains("voe") -> "VOE HD [$langTag]"
                    domain.contains("vidsonic") -> "Vidsonic HD [$langTag]"
                    else -> null // ignorar otros servidores
                }

                if (serverName != null) {
                    servers.add(Video.Server(
                        id = server.src,
                        name = serverName,
                        src = server.src,
                    ).also { it.video = null })
                    Log.i(TAG, "  → $serverName: $domain")
                }
            }
        }

        Log.i(TAG, "${servers.size} servidor(es) de Gnula (filtrados)")
        return servers
    }
}
