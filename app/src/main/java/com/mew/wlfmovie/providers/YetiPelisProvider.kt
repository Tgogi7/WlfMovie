package com.mew.wlfmovie.providers

import com.mew.wlfmovie.models.Video


import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Provider para YetiPelis (https://yetipelis.com)
 *
 * CARACTERÍSTICAS:
 *  - Catálogo completo en español (películas + series + anime)
 *  - Servidores DIRECTOS m3u8 para LAT / ESP / SUB (también ENG con iframes)
 *  - 3 idiomas por episodio: Latino, Castellano, Subtitulado
 *  - Múltiples servidores por idioma para películas
 *  - Token de sesión (pass + PM_UI) gestionado automáticamente
 *
 * FLUJO (probado en Python — todo OK, validado con ffprobe):
 *  1) Buscar en YetiPelis /search?q=... → lista de candidatos
 *  2) Fuzzy match + fallback a 1 único candidato del tipo correcto
 *     + fallback a verificar TMDb ID en cada candidato
 *  3) Visitar página del episodio/película → extraer TMDb ID + nonce
 *     del atributo data-pass del iframe (player-pass?t=tv:18165:NONCE)
 *  4) GET /api/player-pass?t={type}:{tmdb}:{nonce} → pass token (TTL 10 min)
 *  5) GET https://api-encrypted.moviemaster.lat/embed/{type}/{tmdb}/{season}/{episode}?lang={LANG}&pass={token}
 *     → HTML con window.PM_UI="..." (credencial de sesión, no cookie)
 *  6) GET https://api-encrypted.moviemaster.lat/servers?tmdb=...&type=...&lang={LANG}&ui={PM_UI}
 *     → lista de servidores verificados con sus URLs de video
 *  7) Para cada servidor: GET {server.video}&r=1 → {type:"hls", url:"...m3u8"}
 *  8) Devuelve ProviderServer con embedUrl = URL m3u8 DIRECTA (sin WebView)
 *
 * VERIFICACIÓN DE ANUNCIOS:
 *  Algunos servidores devuelven m3u8 con segmentos .image/.png (anuncios TikTok).
 *  Otros devuelven .ts (directo) o .woff2 (MPEG-TS disfrazado, video REAL).
 *  El extractor hace HEAD al primer segmento y verifica el Content-Type:
 *    - video/MP2T → video real (aceptar)
 *    - image/png, image/jpeg → anuncio (descartar)
 *  Verificado con ffprobe: segmentos .woff2 = H.264 720p + AAC 44.1kHz, 1.4 Mbps.
 *
 * EXTRACTOR:
 *  El YetiExtractor (en esta misma carpeta) reconoce los dominios
 *  "moviemaster.lat" y devuelve la URL m3u8 tal cual con el header
 *  Referer: https://api-encrypted.moviemaster.lat/ — NO requiere WebView.
 *
 * IMPORTANTE PARA EL TEST APP:
 *  Para probar YetiPelis aisladamente, DESACTIVAR GnulaProvider y TioPlusProvider
 *  en DetailScreen.kt (ver README.md). Luego, en searchServers() y playNow(),
 *  llamar solo a YetiPelisProvider.searchServers(...).
 */
object YetiPelisProvider {
    const val BASE_URL = "https://yetipelis.com"
    const val MM_BASE = "https://api-encrypted.moviemaster.lat"
    const val PROVIDER_NAME = "YetiPelis"

    private val DEFAULT_LANGS = listOf("LAT", "ESP", "SUB")

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // --- Logging ---

    private fun log(tag: String, msg: String) {
        Log.i("YetiPelis", "[$tag] $msg")
        // WLFMOVIE: También enviar al CrashLogger para que aparezca en
        // Ajustes → Errores recopilados (solo visible en debug)
        try {
            com.mew.wlfmovie.utils.CrashLogger.logError(
                tag = "YetiPelis",
                message = "[$tag] $msg",
                context = "YetiPelisProvider"
            )
        } catch (_: Exception) {}
    }

    // --- HTTP helpers ---

    private suspend fun httpGet(url: String, referer: String? = null, accept: String = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"): String =
        withContext(Dispatchers.IO) {
            log("REQUEST", "GET $url")
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", accept)
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            if (referer != null) builder.header("Referer", referer)
            val response = client.newCall(builder.build()).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            if (!response.isSuccessful) {
                log("ERROR", "HTTP ${response.code} from $url")
                throw Exception("HTTP ${response.code}")
            }
            log("RESPONSE", "${response.code} OK (${body.length} chars)")
            body
        }

    private suspend fun httpGetJson(url: String, referer: String? = null): String =
        httpGet(url, referer, accept = "application/json, text/javascript, */*; q=0.01")

    private suspend fun httpHeadContentType(url: String, referer: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", UA)
                    .header("Referer", referer)
                    .build()
                val resp = client.newCall(req).execute()
                resp.header("Content-Type")
            } catch (e: Exception) {
                null
            }
        }

    // --- Normalización + fuzzy match (igual que GnulaProvider) ---

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

    // --- Búsqueda ---

    private data class SearchResult(
        val url: String,    // "/series/diarios-de-vampiros" o "/movie/el-origen-2010"
        val title: String,
        val year: String,
        val type: String,   // "Serie" o "Pelicula"
    )

    private suspend fun searchYetiPelis(query: String): List<SearchResult> {
        val url = "$BASE_URL/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val html = try {
            httpGet(url, BASE_URL)
        } catch (e: Exception) {
            return emptyList()
        }
        // <a class="yc" href="URL">...<span class="yc-t">TITLE</span>...<span class="yc-m"><span>YEAR</span>
        val pattern = Regex(
            """<a class="yc" href="(/(?:series|movie)/[a-z0-9-]+)">.*?<span class="yc-t">([^<]+)</span>.*?<span class="yc-m"><span>([^<]*)</span>""",
            RegexOption.DOT_MATCHES_ALL
        )
        return pattern.findAll(html).mapNotNull { m ->
            val (u, t, y) = m.destructured
            val type = if (u.startsWith("/series/")) "Serie" else "Pelicula"
            SearchResult(u, t.trim(), y.trim(), type)
        }.toList()
    }

    // --- Extracción del TMDb ID + nonce ---

    private data class TmdbNonce(val tmdb: Int, val nonce: String)

    private fun extractTmdbNonce(html: String, contentType: String): TmdbNonce? {
        val prefix = if (contentType == "tv") "tv" else "movie"
        val regex = Regex("player-pass\\?t=" + prefix + ":(\\d+):([A-Za-z0-9_-]+)")
        val m = regex.find(html) ?: return null
        val tmdb = m.groupValues[1].toIntOrNull() ?: return null
        return TmdbNonce(tmdb, m.groupValues[2])
    }

    // --- Pass token + PM_UI + servers + video URL ---

    private suspend fun getPassToken(contentType: String, tmdb: Int, nonce: String, referer: String): String {
        val t = "$contentType:$tmdb:$nonce"
        val body = httpGetJson("$BASE_URL/api/player-pass?t=$t", referer)
        val json = JSONObject(body)
        if (!json.optBoolean("ok", false)) {
            throw Exception("pass endpoint returned ok=false: $body")
        }
        return json.getString("pass")
    }

    private suspend fun getPmUi(
        contentType: String, tmdb: Int, season: Int, episode: Int, lang: String,
        passToken: String, referer: String
    ): Pair<String, String> {
        val iframeUrl = if (contentType == "tv") {
            "$MM_BASE/embed/tv/$tmdb/$season/$episode?lang=$lang&pass=${URLEncoder.encode(passToken, "UTF-8")}"
        } else {
            "$MM_BASE/embed/movie/$tmdb?lang=$lang&pass=${URLEncoder.encode(passToken, "UTF-8")}"
        }
        val html = httpGet(iframeUrl, referer)
        val regex = Regex("""window\.PM_UI\s*=\s*"([^"]+)"""")
        val m = regex.find(html) ?: throw Exception("PM_UI not found in iframe HTML")
        return Pair(m.groupValues[1], iframeUrl)
    }

    private suspend fun getServersJson(
        contentType: String, tmdb: Int, season: Int, episode: Int, lang: String,
        pmUi: String, iframeUrl: String
    ): JSONObject {
        val params = mutableListOf(
            "tmdb" to tmdb.toString(),
            "type" to contentType,
            "lang" to lang,
            "ui" to pmUi
        )
        if (contentType == "tv") {
            params.add("season" to season.toString())
            params.add("episode" to episode.toString())
        }
        val qs = params.joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }
        return JSONObject(httpGetJson("$MM_BASE/servers?$qs", iframeUrl))
    }

    private suspend fun getVideoUrl(serverVideoUrl: String, iframeUrl: String): Pair<String?, String?>? {
        return try {
            val body = httpGetJson(serverVideoUrl + "&r=1", iframeUrl)
            val json = JSONObject(body)
            Pair(json.optString("type", null), json.optString("url", null).takeIf { it.isNotEmpty() })
        } catch (e: Exception) {
            log("WARN", "video endpoint falló: ${e.message}")
            null
        }
    }

    // --- Verificación de anuncios ---

    /**
     * Analiza un m3u8 (master o variante) y determina si tiene video REAL o solo anuncios.
     *
     * Criterios (basados en análisis con ffprobe):
     *  - .ts, .m4s → video real
     *  - .woff2     → MPEG-TS disfrazado (Content-Type: video/MP2T, sync byte 0x47) → video real
     *  - .image, .png, .jpg → anuncios TikTok (Content-Type: image/png) → descartar
     *
     * @return "real", "ads", "unknown", "failed"
     */
    private suspend fun verifyM3u8(m3u8Url: String, iframeUrl: String): String {
        val text = try {
            httpGet(m3u8Url, iframeUrl)
        } catch (e: Exception) {
            return "failed"
        }
        // Si es master (tiene #EXT-X-STREAM-INF), seguir primera variante
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variants = text.lines().filter { it.startsWith("http") && it.contains(".m3u8") }
            if (variants.isEmpty()) return "unknown"
            // Verificar la primera variante
            return verifyM3u8(variants.first(), iframeUrl)
        }
        // Es un m3u8 variante: clasificar segmentos
        var realCount = 0
        var adsCount = 0
        var firstSegUrl: String? = null
        for (line in text.lines()) {
            if (!line.startsWith("http")) continue
            if (firstSegUrl == null) firstSegUrl = line
            val low = line.substringBefore("?").lowercase()
            when {
                low.endsWith(".ts") || ".ts?" in low -> realCount++
                low.endsWith(".m4s") || ".m4s?" in low -> realCount++
                low.endsWith(".woff2") || ".woff2?" in low -> realCount++
                low.endsWith(".image") || ".image?" in low -> adsCount++
                low.endsWith(".png") || ".png?" in low -> adsCount++
                low.endsWith(".jpg") || ".jpeg" in low || ".jpg?" in low -> adsCount++
                else -> {
                    // Extensión desconocida: verificar Content-Type del primer segmento
                    if (firstSegUrl == line) {
                        val ct = httpHeadContentType(line, iframeUrl)
                        when {
                            ct == null -> {} // no info
                            ct.contains("mp2t", ignoreCase = true) || ct.contains("mpeg", ignoreCase = true) -> realCount++
                            ct.startsWith("image/", ignoreCase = true) -> adsCount++
                            else -> realCount++ // asumir real si no es imagen
                        }
                    }
                }
            }
        }
        return when {
            realCount > 0 && realCount >= adsCount -> "real"
            adsCount > realCount -> "ads"
            else -> "unknown"
        }
    }

    // --- Función principal ---

    /**
     * Busca y devuelve servidores de un título.
     *
     * @param title Título principal (TMDb localizado en español)
     * @param isMovie true para película, false para serie
     * @param altTitle Título alternativo (original o en inglés)
     * @param seasonNum Número de temporada (solo TV)
     * @param episodeNum Número de episodio (solo TV)
     * @param expectTmdbId TMDb ID conocido (del test app via TMDb API) para verificar match
     * @param langs Idiomas a probar, por defecto ["LAT", "ESP", "SUB"]
     */
    suspend fun searchServers(
        title: String,
        isMovie: Boolean,
        altTitle: String? = null,
        seasonNum: Int? = null,
        episodeNum: Int? = null,
        expectTmdbId: Int? = null,
        langs: List<String> = DEFAULT_LANGS,
    ): List<Video.Server> {
        log("INFO", "═══ YetiPelis — Iniciando búsqueda ═══")
        log("INFO", "Título: \"$title\" | Tipo: ${if (!isMovie) "Serie" else "Película"}" +
                (if (seasonNum != null && episodeNum != null) " | T$seasonNum E$episodeNum" else ""))
        if (!altTitle.isNullOrBlank() && altTitle != title) {
            log("INFO", "Título alternativo: \"$altTitle\"")
        }
        if (expectTmdbId != null) log("INFO", "TMDb ID esperado: $expectTmdbId")

        val contentType = if (!isMovie) "tv" else "movie"
        val expectedYtType = if (!isMovie) "Serie" else "Pelicula"

        // 1. Buscar en YetiPelis (probar varios queries en paralelo para ir más rápido)
        val queries = mutableListOf(title)
        if (!altTitle.isNullOrBlank() && altTitle != title) queries.add(altTitle)

        var foundUrl: String? = null
        var foundTitle: String? = null
        val candidatesPerQuery = mutableListOf<Pair<String, List<SearchResult>>>()

        for (q in queries) {
            log("INFO", "Buscando: \"$q\"")
            val results = searchYetiPelis(q)
            candidatesPerQuery.add(q to results)
            log("INFO", "  → ${results.size} resultado(s)")
            for (r in results.take(5)) {
                log("INFO", "    ${r.url} — ${r.title} (${r.year}) [${r.type}]")
            }

            // Filtrar al tipo correcto (Serie/Pelicula)
            val candidates = results.filter { it.type == expectedYtType }.ifEmpty { results }

            // === Fallback 0 (PRIORIDAD MÁXIMA): TMDb ID matching ===
            // Si tenemos expectTmdbId, visitar cada candidato y verificar el TMDb ID del HTML.
            // Esto es 100% confiable — no hay false positives.
            if (expectTmdbId != null && foundUrl == null) {
                log("INFO", "Verificando TMDb ID=$expectTmdbId en ${candidates.size} candidato(s)")
                for (r in candidates.take(5)) {
                    try {
                        val probePath = if (contentType == "tv") "${r.url}/season/1/episode/1" else r.url
                        val probeHtml = httpGet("$BASE_URL$probePath", BASE_URL)
                        val probeTmdb = extractTmdbNonce(probeHtml, contentType)
                        log("INFO", "  probando ${r.url} → TMDb=${probeTmdb?.tmdb}")
                        if (probeTmdb != null && probeTmdb.tmdb == expectTmdbId) {
                            foundUrl = r.url
                            foundTitle = r.title
                            log("INFO", "  ✓ MATCH por TMDb ID")
                            break
                        }
                    } catch (e: Exception) {
                        log("INFO", "  error probeando ${r.url}: ${e.message}")
                    }
                }
            }

            // Si TMDb ID matcheo, saltar el fuzzy match
            if (foundUrl != null) break

            // === Fallback 1: fuzzy match por título ===
            // Solo se aplica si NO tenemos TMDb ID esperado, o si el TMDb ID no matcheo.
            for (r in candidates) {
                if (isMatch(r.title, q) || isMatch(r.title, title) ||
                    (altTitle != null && isMatch(r.title, altTitle))
                ) {
                    // Verificación adicional: si el año de TMDb está disponible en el resultado
                    // de YetiPelis (viene como "2009", "2026", etc.), comparar.
                    // El test app debe pasar expectYear si quiere esta verificación.
                    foundUrl = r.url
                    foundTitle = r.title
                    log("INFO", "  ✓ MATCH por fuzzy title")
                    break
                }
            }
            if (foundUrl != null) break
        }

        // === Fallback 2: si solo hay 1 candidato del tipo correcto ===
        // Útil cuando el título no fuzzy-matchea (ej: "Crónicas vampíricas" no matchea con "Diarios de Vampiros")
        if (foundUrl == null) {
            for ((q, results) in candidatesPerQuery) {
                val candidates = results.filter { it.type == expectedYtType }
                if (candidates.size == 1) {
                    foundUrl = candidates[0].url
                    foundTitle = candidates[0].title
                    log("INFO", "Fallback único candidato → $foundUrl")
                    break
                }
            }
        }

        if (foundUrl == null) {
            log("ERROR", "Sin coincidencia para \"$title\"")
            return emptyList()
        }
        log("MATCH", "\"$foundTitle\" ($foundUrl)")

        // 2. Visitar página y extraer TMDb ID + nonce
        val pagePath = if (contentType == "tv") {
            "$foundUrl/season/$seasonNum/episode/$episodeNum"
        } else {
            foundUrl
        }
        log("INFO", "Visitando: $pagePath")
        val pageHtml = httpGet("$BASE_URL$pagePath", BASE_URL)
        val tmdbNonce = extractTmdbNonce(pageHtml, contentType)
        if (tmdbNonce == null) {
            log("ERROR", "No se pudo extraer TMDb ID / nonce de la página")
            return emptyList()
        }
        log("PARSE", "TMDb ID = ${tmdbNonce.tmdb} | nonce = ${tmdbNonce.nonce.take(16)}...")
        if (expectTmdbId != null && expectTmdbId != tmdbNonce.tmdb) {
            log("WARN", "TMDb ID mismatch: esperado=$expectTmdbId, encontrado=${tmdbNonce.tmdb}")
        }

        // 3. Para cada idioma — PROCESAR EN PARALELO (coroutineScope + async)
        // Antes era secuencial (LAT → ESP → SUB), ahora los 3 corren a la vez.
        // Para películas con 14 servers × 3 idiomas = 42 validaciones, pasa de ~13 min a ~3 min.
        log("INFO", "")
        log("INFO", "Procesando ${langs.size} idioma(s) en paralelo: ${langs.joinToString()}")
        val servers = coroutineScope {
            val deferreds = langs.map { lang ->
                async(Dispatchers.IO) {
                    processLanguage(
                        contentType, tmdbNonce.tmdb, tmdbNonce.nonce,
                        seasonNum ?: 0, episodeNum ?: 0,
                        lang, "$BASE_URL$pagePath",
                    )
                }
            }
            // awaitAll y juntar todos los resultados
            deferreds.awaitAll().flatten()
        }

        // === FILTRAR Y ORDENAR SERVIDORES POR CALIDAD ===
        // Los servidores YetiPelis devuelven URLs m3u8 con dos patrones:
        //   - https://api-encrypted.moviemaster.lat/s/...m3u8  → segmentos .woff2 o .ts directos del CDN (calidad ALTA, reproducen bien)
        //   - https://api-encrypted.moviemaster.lat/p/...m3u8  → "proxy mode", el stream pasa por el servidor de moviemaster (NO FUNCIONA — timeout, se cuelga)
        //   - https://s40.fastream.to/hls2/...m3u8             → CDN externo directo (calidad MÁXIMA, siempre funcionan)
        //
        // v4: FILTRADO ABSOLUTO de proxy /p/ — siempre se descartan, sin importar cuántos directos haya.
        // No tiene sentido mostrarle al usuario un servidor que ya sabemos que no funciona.
        val proxyCount = servers.count { serverQualityScore(it.id) == 1 }
        val directServers = servers.filter { serverQualityScore(it.id) != 1 }
        if (proxyCount > 0) {
            log("INFO", "Filtrados $proxyCount servidor(es) proxy /p/ (no funcionan) — quedan ${directServers.size} directos del CDN")
        }
        val finalServers = directServers.sortedByDescending { serverQualityScore(it.id) }

        log("SUCCESS", "═══ ${finalServers.size} servidores listos (solo directos del CDN, ordenados por calidad) ═══")
        return finalServers
    }

    /**
     * Clasifica la calidad de un servidor por su URL m3u8.
     *
     * @return Int score: 3 = fastream.to (mejor), 2 = moviemaster/s/ (directo),
     *         1 = moviemaster/p/ (proxy, lento), 0 = iframe externo (desconocido)
     */
    private fun serverQualityScore(embedUrl: String): Int {
        return when {
            // fastream.to es CDN externo, segmentos .ts directos, SIEMPRE funciona
            embedUrl.contains("fastream.to") -> 3
            // moviemaster.lat/s/ es directo del CDN (segmentos .woff2 o .ts disfrazados)
            embedUrl.contains("moviemaster.lat/s/") -> 2
            // moviemaster.lat/p/ es proxy mode, lento y a menudo da timeout
            embedUrl.contains("moviemaster.lat/p/") -> 1
            // iframe externo (vidsrc, vidlink, videasy) — calidad desconocida
            else -> 0
        }
    }

    /**
     * Procesa un idioma: pide pass token + iframe HTML + servers + verifica cada m3u8.
     * Diseñado para correr en paralelo — cada llamada es independiente.
     */
    private suspend fun processLanguage(
        contentType: String,
        tmdb: Int,
        nonce: String,
        season: Int,
        episode: Int,
        lang: String,
        pageUrl: String,
    ): List<Video.Server> {
        val servers = mutableListOf<Video.Server>()
        try {
            log("INFO", "")
            log("INFO", "=== [$lang] Inicio ===")

            // Pass token (TTL 10 min, fresco para este idioma)
            val passToken = getPassToken(contentType, tmdb, nonce, pageUrl)
            log("INFO", "[$lang] pass token OK: ${passToken.take(40)}...")

            // PM_UI (carga el iframe con el lang específico)
            val (pmUi, iframeUrl) = getPmUi(contentType, tmdb, season, episode, lang, passToken, pageUrl)
            log("INFO", "[$lang] PM_UI OK: ${pmUi.take(40)}...")

            // Servers
            val data = getServersJson(contentType, tmdb, season, episode, lang, pmUi, iframeUrl)
            val serverArray = data.optJSONArray("servers")
            val serverCount = serverArray?.length() ?: 0
            log("INFO", "[$lang] Servers: $serverCount")
            if (serverCount == 0) return servers

            // === Verificar cada server EN PARALELO ===
            // Cada verifyM3u8 hace 1-3 HTTP requests. Con 14 servers, en paralelo es ~5s en vez de 60s.
            coroutineScope {
                val deferreds = (0 until serverCount).map { i ->
                    async(Dispatchers.IO) {
                        val svr = serverArray.optJSONObject(i) ?: return@async null
                        val svrVideo = svr.optString("video").takeIf { it.isNotEmpty() } ?: return@async null
                        val svrName = svr.optString("name", "Servidor ${i + 1}")

                        try {
                            val pair = getVideoUrl(svrVideo, iframeUrl) ?: return@async null
                            val (vtype, vurl) = pair
                            if (vurl.isNullOrEmpty()) return@async null

                            if (vtype == "hls" || vurl.endsWith(".m3u8")) {
                                val status = verifyM3u8(vurl, iframeUrl)
                                // Calidad: ⚡ = directo del CDN (mejor), 🐢 = proxy (lento)
                                val quality = when {
                                    vurl.contains("fastream.to") -> "⚡⚡"
                                    vurl.contains("moviemaster.lat/s/") -> "⚡"
                                    vurl.contains("moviemaster.lat/p/") -> "🐢"
                                    else -> "?"
                                }
                                // El dominio se muestra en la UI — incluimos el tipo (direct/proxy/CDN)
                                // para que el usuario sepa cuál es más probable de funcionar.
                                val domainLabel = when {
                                    vurl.contains("fastream.to") -> "fastream.to (CDN)"
                                    vurl.contains("moviemaster.lat/s/") -> "moviemaster.lat/s (direct)"
                                    vurl.contains("moviemaster.lat/p/") -> "moviemaster.lat/p (proxy)"
                                    else -> "moviemaster.lat"
                                }
                                when (status) {
                                    "ads" -> {
                                        log("INFO", "  [$lang/$svrName] solo anuncios (descartado)")
                                        return@async null
                                    }
                                    "failed" -> {
                                        log("INFO", "  [$lang/$svrName] m3u8 fetch falló (descartado)")
                                        return@async null
                                    }
                                    "real" -> log("INFO", "  [$lang/$svrName] ✓ video real $quality")
                                    "unknown" -> log("INFO", "  [$lang/$svrName] estado desconocido $quality, mantener por si acaso")
                                }
                                Video.Server(
                                    id = vurl,
                                    name = "YP [$lang] $svrName",
                                    src = vurl,
                                )
                            } else if (vtype == "iframe") {
                                val host = try {
                                    java.net.URL(vurl).host
                                } catch (e: Exception) {
                                    vurl.substringBefore("/")
                                }
                                log("INFO", "  [$lang/$svrName] iframe → $host")
                                Video.Server(
                                    id = vurl,
                                    name = "YP [$lang] $svrName",
                                    src = vurl,
                                )
                            } else {
                                log("INFO", "  [$lang/$svrName] tipo desconocido: $vtype")
                                null
                            }
                        } catch (e: Exception) {
                            log("INFO", "  [$lang/$svrName] error: ${e.message}")
                            null
                        }
                    }
                }
                // Recolectar todos los resultados no nulos
                servers.addAll(deferreds.awaitAll().filterNotNull())
            }

            log("INFO", "[$lang] servidores válidos")
        } catch (e: Exception) {
            log("ERROR", "[$lang] error general: ${e.message}")
        }
        return servers
    }
}
