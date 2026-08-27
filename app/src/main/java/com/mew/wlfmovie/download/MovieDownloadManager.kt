package com.mew.wlfmovie.download

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.media3.common.MimeTypes
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.models.DownloadedMovie
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.models.Video
import com.mew.wlfmovie.providers.CineHaxProvider
import com.mew.wlfmovie.providers.Provider
import com.mew.wlfmovie.utils.TitleMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * WLFMOVIE V6.3: Orquesta las descargas offline (peliculas por HLS, multi-provider).
 *
 * CAMBIO V6.3 vs V6.2.x:
 *  - Ya NO usa CineHaxProvider exclusivamente. Ahora hace scraper a TODOS los providers
 *    en [Provider.providers] que soportan peliculas — el mismo set que TmdbProvider.getServers()
 *    cuando el usuario va a ver una pelicula. Esto aumenta drasticamente la probabilidad
 *    de encontrar un servidor HLS valido para cualquier pelicula.
 *  - Remux eliminado: el CDN `remux.unlimuxplay.com` esta caido permanentemente (verificado
 *    por el usuario en la web). Solo HLS es soportado ahora.
 *
 * Flujo de [findBestDownloadableServer]:
 *  1. Para cada provider (los MISMOS 12 que TmdbProvider.getServers al ver online):
 *     a. `provider.search(targetTitle, 1)` → resultados.
 *     b. Match por similitud (normalizacion de acentos, lowercasing, coverage >= 60%).
 *     c. `provider.getServers(matchedId, Video.Type.Movie)` → servers.
 *     d. Para cada server: `provider.getVideo(server)` → Video.source (m3u8 URL).
 *     e. Filtrar los que sean HLS (mime `application/vnd.apple.mpegurl` o URL `.m3u8`).
 *  2. Agrupar servers HLS por idioma (detectado del nombre del server).
 *  3. Devolver `DownloadCandidate(kind=HLS, languages=[...], serversByLanguage={lang -> [servers]})`.
 *
 * WLFMOVIE V6.3.4: serversByLanguage ahora contiene TODOS los servers por
 * idioma (tope de 8, deduplicados por src, ordenados por prioridad de
 * provider) — el usuario elige servidor en el dialogo. Ademas, los servers
 * cuyo m3u8 resuelto apunta a un dominio bloqueado (ibra.lat, DMCA) se
 * filtran por DOMINIO, no solo por nombre.
 */
object MovieDownloadManager {

    private const val TAG = "MovieDownloadManager"
    private const val WORK_PREFIX = "movie_download_"
    private const val MAX_RETRIES = 5

    /**
     * WLFMOVIE V6.3.4: maximo de servers que se ofrecen por idioma en el
     * dialogo de seleccion (ordenados por prioridad de provider).
     */
    private const val MAX_SERVERS_PER_LANGUAGE = 10

    /**
     * Estado en vivo de descarga por movieId. Lo publica [MovieDownloadService] via
     * [publishDownloading]/[publishCompleted]/[publishFailed]/[publishCancelled].
     */
    private val progressFlow = MutableStateFlow<Map<String, DownloadProgressState>>(emptyMap())

    // ----------------------------------------------------------------------
    // Candidate finding
    // ----------------------------------------------------------------------

    /**
     * Tipo de descarga.
     *
     * WLFMOVIE V6.3: REMUX sigue definido por compatibilidad con datos persistedos
     * (work data, intents antigos) pero [findBestDownloadableServer] nunca lo devuelve
     * — el CDN de Remux esta caido permanentemente. Todo es HLS ahora.
     */
    enum class Kind {
        REMUX,  // Deprecated V6.3: CDN muerto. Se mantiene por compatibilidad.
        HLS,
    }

    /**
     * Resultado de buscar servers para una pelicula.
     * - [kind]: siempre HLS en V6.3.
     * - [languages]: lista de idiomas disponibles (latino/subtitulado/castellano).
     * - [serversByLanguage]: TODOS los servers HLS resueltos por idioma, ordenados
     *   por prioridad de provider (V6.3.4 — antes solo guardabamos el primero por
     *   idioma; ahora el usuario puede elegir servidor cuando hay varios).
     */
    data class DownloadCandidate(
        val kind: Kind,
        val languages: List<String>,
        val serversByLanguage: Map<String, List<Video.Server>>,
    )

    /**
     * WLFMOVIE V6.3: Busca servers HLS para la pelicula en TODOS los providers.
     *
     * Replica exactamente la logica de [com.mew.wlfmovie.providers.TmdbProvider.getServers]
     * cuando `lang == "es"`: recorre la lista de providers espanoles, hace search, hace
     * match por similitud, y llama a `getServers`. Luego, para cada server, resuelve
     * el m3u8 via `provider.getVideo(server)`, filtra los que sean HLS, y agrupa por
     * idioma detectado del nombre del server.
     *
     * El usuario elige el idioma; nosotros descargamos el primero disponible (mejor
     * provider primero, ya que [Provider.providers] esta ordenado manualmente).
     *
     * Si ningun provider tiene HLS para la pelicula, devuelve null (la UI muestra
     * el dialogo slow-link).
     */
    suspend fun findBestDownloadableServer(
        movieId: String,
        videoType: Video.Type.Movie,
    ): DownloadCandidate? {
        val targetTitle = videoType.title
        Log.i(TAG, "findBestDownloadableServer: movieId=$movieId title='$targetTitle'")

        // WLFMOVIE V6.3.5: sondear los MISMOS 12 providers que el flujo de VER
        // ONLINE (TmdbProvider.getServers para "es"), incluyendo los marcados
        // movies=false (SeriesFlix, CableVisionHD, CineCity).
        //
        // El usuario reporto que al ver online salian 15-30 servers pero al
        // descargar solo 1-2. Una de las causas: aqui sondeabamos solo los 9
        // con movies=true, mientras que "ver online" sondea los 12. Si un
        // provider no tiene la pelicula, su search/match devuelve vacio y no
        // pasa nada — exactamente igual que al ver online.
        val providers = listOf(
            com.mew.wlfmovie.providers.CuevanaEuProvider,
            com.mew.wlfmovie.providers.CineCalidadProvider,
            com.mew.wlfmovie.providers.PoseidonHD2Provider,
            com.mew.wlfmovie.providers.PelisplustoProvider,
            com.mew.wlfmovie.providers.PelisflixHdProvider,
            com.mew.wlfmovie.providers.SeriesFlixProvider,
            com.mew.wlfmovie.providers.FlixLatamProvider,
            com.mew.wlfmovie.providers.SoloLatinoProvider,
            com.mew.wlfmovie.providers.CableVisionHDProvider,
            com.mew.wlfmovie.providers.CineCityProvider,
            com.mew.wlfmovie.providers.DoramasflixProvider,
            com.mew.wlfmovie.providers.CineHaxProvider,
        )

        Log.d(TAG, "Probing ${providers.size} providers: ${providers.joinToString { it.name }}")

        // Para cada provider en paralelo: search → match (año + titulos
        // alternativos) → getServers → getVideo.
        //
        // WLFMOVIE V6.3.6: el matching ahora valida el AÑO contra TMDB y, si el
        // titulo es no matchea, reintenta con titulos alternativos (es-MX,
        // es-CO, en) — "Noche de Bodas" (es) se llama "Boda Sangrienta" en
        // Mexico. Ademas, gana el MEJOR candidato (puntaje), no el primero.
        val allHlsServers: List<Pair<Video.Server, Provider>> = coroutineScope {
            // Titulos alternativos desde TMDB (en paralelo; solo se esperan si
            // el titulo es no alcanza para matchear).
            val altTitlesDeferred = async(Dispatchers.IO) {
                TitleMatcher.searchTitlesFor(movieId, targetTitle)
            }
            val deferred = providers.map { provider ->
                async(Dispatchers.IO) {
                    // Timeout de 15s por provider (incluye reintentos con titulos alternativos).
                    withTimeoutOrNull(15_000L) {
                        try {
                            val targetYear = TitleMatcher.parseYear(videoType.releaseDate)
                                ?: altTitlesDeferred.await().year
                            val searchResults = provider.search(targetTitle, 1)
                            if (searchResults.isEmpty()) {
                                Log.d(TAG, "[${provider.name}] sin resultados para '$targetTitle'")
                            }

                            var found: com.mew.wlfmovie.adapters.AppAdapter.Item? =
                                TitleMatcher.bestMatch(searchResults, targetTitle, targetYear, moviesOnly = true)
                            var matchedVia = targetTitle
                            if (found == null) {
                                // Titulos alternativos UNO POR UNO: es-MX -> es-CO -> en.
                                for (alt in altTitlesDeferred.await().titles.drop(1)) {
                                    val altResults = try {
                                        provider.search(alt, 1)
                                    } catch (e: Exception) {
                                        emptyList()
                                    }
                                    found = TitleMatcher.bestMatch(altResults, alt, targetYear, moviesOnly = true)
                                    if (found != null) {
                                        matchedVia = alt
                                        break
                                    }
                                }
                            }

                            val bestMatch = found
                            val matchedId = when (bestMatch) {
                                is Movie -> bestMatch.id
                                is com.mew.wlfmovie.models.TvShow -> bestMatch.id
                                else -> null
                            }
                            if (matchedId == null) {
                                Log.d(TAG, "[${provider.name}] sin match para '$targetTitle' (${targetYear ?: "?"}): " +
                                    "${searchResults.size} resultados, ninguno con titulo+año OK")
                                return@withTimeoutOrNull emptyList<Pair<Video.Server, Provider>>()
                            }

                            Log.i(TAG, "[${provider.name}] match OK: '$targetTitle' via '$matchedVia' -> id=$matchedId")
                            val allServers = provider.getServers(matchedId, videoType)
                            Log.i(TAG, "[${provider.name}] getServers OK: ${allServers.size} servers")

                            // Devolver todos los servers junto con el provider para
                            // poder resolver el m3u8 despues via provider.getVideo.
                            allServers.map { it to provider }
                        } catch (e: Exception) {
                            Log.w(TAG, "[${provider.name}] fallo: ${e.message}")
                            emptyList()
                        }
                    } ?: emptyList()
                }
            }
            deferred.awaitAll().flatten()
        }

        if (allHlsServers.isEmpty()) {
            Log.w(TAG, "Ningun provider devolvio servers para '$targetTitle'")
            return null
        }

        Log.i(TAG, "Total servers antes de filtrar HLS: ${allHlsServers.size}")

        // Resolver cada server a un Video.source (m3u8 real) y filtrar HLS.
        // Esto se hace en paralelo tambien porque cada provider.getVideo es una
        // request HTTP al extractor correspondiente.
        //
        // WLFMOVIE V6.3.1: Filtrar servers en blacklist (vimeos) — sabenos que
        // HlsDownloader no los puede parsear, asi que ni los intentamos.
        //
        // WLFMOVIE V6.3.4: El filtro por nombre NO basta. El usuario reporto que
        // sv4.ibra.lat seguia apareciendo con nombre "Opción 1 · Latino" (sin
        // "ibra" en el nombre) y src pre-resolucion sin el dominio. El dominio
        // real (sv4.ibra.lat) solo aparece en el m3u8 RESUELTO por getVideo.
        // Ahora tambien filtramos por DOMINIO de la URL resuelta.
        //
        // WLFMOVIE V6.3.5: (a) timeout de 15s POR RESOLUCION — antes no habia
        // limite y un extractor colgado bloqueaba el dialogo de descarga entero;
        // (b) contadores de diagnostico: el usuario pregunta "por que tan
        // poquitos servers" — el RESUMEN del final desglosa exactamente donde
        // se cayo cada server (blacklist, dominio bloqueado, no-HLS, extractor).
        val statBlacklistName = java.util.concurrent.atomic.AtomicInteger(0)
        val statDomainBlocked = java.util.concurrent.atomic.AtomicInteger(0)
        val statNonHls = java.util.concurrent.atomic.AtomicInteger(0)
        val statExtractFailed = java.util.concurrent.atomic.AtomicInteger(0)
        val resolvedHls: List<Video.Server> = coroutineScope {
            val deferred = allHlsServers.map { (server, provider) ->
                async(Dispatchers.IO) {
                    try {
                        if (isBlacklistedForDownload(server)) {
                            statBlacklistName.incrementAndGet()
                            Log.d(TAG, "Server '${server.name}' (${provider.name}) en blacklist para descarga, skip")
                            return@async null
                        }
                        val video = withTimeoutOrNull(15_000L) { provider.getVideo(server) }
                        if (video == null) {
                            statExtractFailed.incrementAndGet()
                            Log.w(TAG, "getVideo TIMEOUT (15s) para '${server.name}' (${provider.name})")
                            return@async null
                        }
                        val src = video.source
                        if (src.isEmpty()) {
                            statExtractFailed.incrementAndGet()
                            return@async null
                        }

                        // WLFMOVIE V6.3.4: blacklist por DOMINIO de la URL resuelta.
                        // El nombre del server ("Opción 1 · Latino") no revela el CDN
                        // real; el host del m3u8 si. Aqui es donde sv4.ibra.lat se
                        // cuela — lo bloqueamos antes de ofrecerlo al usuario.
                        if (isBlacklistedDownloadUrl(src)) {
                            statDomainBlocked.incrementAndGet()
                            Log.i(TAG, "Server '${server.name}' (${provider.name}) resuelve a dominio bloqueado: $src — skip")
                            return@async null
                        }

                        val isHls = video.type?.let { type ->
                            type.equals(MimeTypes.APPLICATION_M3U8, ignoreCase = true) ||
                                type.equals("application/x-mpegURL", ignoreCase = true) ||
                                type.equals("application/vnd.apple.mpegurl", ignoreCase = true)
                        } ?: src.endsWith(".m3u8", ignoreCase = true) ||
                            src.contains(".m3u8?", ignoreCase = true)

                        if (!isHls) {
                            statNonHls.incrementAndGet()
                            Log.d(TAG, "Server '${server.name}' (${provider.name}) no es HLS (type=${video.type}), skip")
                            return@async null
                        }

                        // Sustituir el src del server por el m3u8 real resuelto por el extractor.
                        server.copy(src = src, id = src)
                    } catch (e: Exception) {
                        statExtractFailed.incrementAndGet()
                        Log.w(TAG, "getVideo fallo para '${server.name}' (${provider.name}): ${e.message}")
                        null
                    }
                }
            }
            deferred.awaitAll().filterNotNull()
        }

        if (resolvedHls.isEmpty()) {
            Log.w(TAG, "Ningun server es HLS para '$targetTitle'")
            return null
        }

        Log.i(TAG, "Total servers HLS resueltos: ${resolvedHls.size}")

        // Agrupar por idioma detectado del nombre del server.
        // WLFMOVIE V6.3.4: guardar TODOS los servers por idioma (no solo el
        // primero) para que el usuario pueda elegir servidor en el dialogo.
        // Se deduplica por src (distintos providers pueden resolver el mismo
        // m3u8) y se conserva el orden de prioridad de providers.
        // Tope de MAX_SERVERS_PER_LANGUAGE por idioma (V6.3.5: 10) para que
        // el dialogo no crezca sin limite.
        val byLanguage = mutableMapOf<String, MutableList<Video.Server>>()
        var statDedupDropped = 0
        var statCapDropped = 0
        for (server in resolvedHls) {
            val lang = detectLanguage(server.name)
                // Si no detectamos idioma, agruparlo bajo "subtitulado" (mismo
                // comportamiento que la version anterior con fallbackUnmapped).
                ?: "subtitulado"
            val list = byLanguage.getOrPut(lang) { mutableListOf() }
            if (list.size >= MAX_SERVERS_PER_LANGUAGE) {
                statCapDropped++
                continue
            }
            if (list.none { it.src == server.src }) {
                list.add(server)
            } else {
                statDedupDropped++
            }
        }

        if (byLanguage.isEmpty()) {
            Log.w(TAG, "HLS disponible pero sin idioma detectable para '$targetTitle'")
            return null
        }

        // Ordenar idiomas: latino > subtitulado > castellano > otros
        val preferred = listOf("latino", "subtitulado", "castellano")
        val ordered = preferred.filter { it in byLanguage } +
            byLanguage.keys.filter { it !in preferred }

        // WLFMOVIE V6.3.5: RESUMEN de diagnostico. Responde directamente a la
        // pregunta del usuario ("por que tan poquitos servers si al ver online
        // salen 15-30?"): aqui se ve cuantos providers contestaron, cuantos
        // servers crudos habia y donde se fue cayendo cada uno. La diferencia
        // vs "ver online" es esperable: al descargar hay que RESOLVER cada m3u8
        // y filtrar los que no se pueden bajar (blacklist/DMCA/no-HLS/rotos).
        val providersResponded = allHlsServers.map { it.second }.distinct().size
        Log.i(
            TAG,
            "findBestDownloadableServer RESUMEN '$targetTitle': " +
                "providersConServers=$providersResponded/${providers.size} | " +
                "crudos=${allHlsServers.size} | " +
                "blacklistNombre=${statBlacklistName.get()} | " +
                "dominioBloqueado=${statDomainBlocked.get()} | " +
                "noHls=${statNonHls.get()} | " +
                "falloExtract=${statExtractFailed.get()} | " +
                "hlsValidos=${resolvedHls.size} | " +
                "duplicados=$statDedupDropped | " +
                "sobreTope=$statCapDropped | " +
                "porIdioma=" + ordered.joinToString { "$it=${byLanguage[it]?.size}" }
        )
        return DownloadCandidate(
            kind = Kind.HLS,
            languages = ordered,
            serversByLanguage = byLanguage.toMap(),
        )
    }

    /**
     * WLFMOVIE V6.3.6: el matching historico (isMatch/normalize/wordsOf) fue
     * reemplazado por [com.mew.wlfmovie.utils.TitleMatcher], que ademas del
     * titulo valida el AÑO contra TMDB, rechaza secuelas ("... 2") y solo
     * acepta items Movie para una pelicula. Ver su documentacion.
     */

    /**
     * Detecta el idioma a partir del nombre del server.
     *
     * WLFMOVIE V6.3.5: antes solo matcheaba palabras completas ("latino",
     * "subtitulado", "castellano"). El usuario reporto un server llamado
     * "LAT" apareciendo bajo "subtitulado": el tag abreviado no matcheaba
     * y caia al fallback. Ahora se reconocen los MISMOS tags que usa el
     * sorter de TmdbProvider al ver online: [LAT]/(LAT)/LAT/Latino,
     * [CAST]/[CAS]/CASTELLANO, [ES]/(ESP)/ES/ESPAÑOL/SPANISH/SPAIN,
     * [SUB]/(SUB)/SUB/Subtitulado — con limites de palabra para no falsear
     * ("series" NO contiene el tag ES, "subtitulado" SI es sub).
     */
    private fun detectLanguage(serverName: String): String? {
        // Normalizar: quitar acentos (español→espanol), lowercase y separar
        // todo lo que no sea alfanumerico ("[LAT]" → " lat ") para que los
        // limites de palabra funcionen con tags entre corchetes/parentesis.
        val n = Normalizer.normalize(serverName, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
        return when {
            Regex("\\blat\\b").containsMatchIn(n) || n.contains("latino") -> "latino"
            Regex("\\bcast\\b|\\bcas\\b|\\bes\\b|\\besp\\b").containsMatchIn(n) ||
                n.contains("castellano") || n.contains("espanol") ||
                n.contains("spanish") || n.contains("spain") -> "castellano"
            Regex("\\bsub\\b").containsMatchIn(n) ||
                n.contains("subtitulado") || n.contains("subtitulada") -> "subtitulado"
            else -> null
        }
    }

    /**
     * WLFMOVIE V6.3.1 / V6.3.2: Bloquea servers que NO sirven para descargar.
     *
     * - vimeos: El CDN de vimeos.zip devuelve un master.m3u8 con URLs
     *   `_,n,h,.urlset/master.m3u8?t=...` que nuestro HlsDownloader no logra
     *   parsear (devuelve "No se pudo parsear el playlist HLS"). El usuario
     *   reporta que ya ningún otro server resuelve HLS para sus películas de
     *   prueba, así que bloqueamos vimeos para que el diálogo slow-link
     *   aparezca en vez de intentar descargar sabiendo que va a fallar.
     *
     *   Para ver online, vimeos SÍ funciona (ExoPlayer maneja el m3u8
     *   directamente sin necesidad de parsear nosotros). Solo lo bloqueamos
     *   para descarga.
     *
     * - goodstream (V6.3.2): El usuario reporta que goodstream pasa lo mismo
     *   que con vimeos: inicia la descarga pero se pausa sin más alrededor de
     *   732 MB sin error. Asumimos que goodstream es un mirror/redirección
     *   del mismo CDN roto que vimeos (los dominios goodstream.unlimplay.com
     *   y vimeos.zip suelen compartir infraestructura), asi que lo bloqueamos.
     *
     * - ibra.lat (V6.3.3): sv3.ibra.lat responde el m3u8 con HTTP 200 pero el
     *   cuerpo no tiene segmentos ("File deleted by DMCA request"). El usuario
     *   lo confirmó abriendo el enlace en el navegador: son archivos eliminados
     *   por DMCA que el server igual lista como opción. La descarga muere con
     *   "Playlist HLS no tiene segmentos". Lo bloqueamos para descarga (para
     *   ver online puede seguir funcionando si ExoPlayer encuentra otra variante).
     */
    private fun isBlacklistedForDownload(server: Video.Server): Boolean {
        val n = server.name.lowercase()
        val s = server.src.lowercase()
        return n.contains("vimeos") || s.contains("vimeos.zip") || s.contains("vimeos.") ||
            n.contains("goodstream") || s.contains("goodstream.") ||
            n.contains("ibra") || s.contains("ibra.lat")
    }

    /**
     * WLFMOVIE V6.3.4: Dominios cuyos m3u8 resueltos NO se ofrecen para descarga.
     *
     * Se comprueba contra el HOST de la URL resuelta (no contra el nombre del
     * server, que puede ser "Opción 1 · Latino" y no revelar nada).
     */
    private val BLOCKED_DOWNLOAD_DOMAINS = listOf("ibra.lat")

    /**
     * WLFMOVIE V6.3.4: True si la URL resuelta apunta a un dominio bloqueado.
     * Compara por host exacto o por sufijo de subdominio ("sv4.ibra.lat" termina
     * en ".ibra.lat" → bloqueado; "ibramedia.com" NO → sigue disponible).
     */
    private fun isBlacklistedDownloadUrl(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
            ?: return url.lowercase().contains("ibra.lat")
        return BLOCKED_DOWNLOAD_DOMAINS.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }

    // ----------------------------------------------------------------------
    // WLFMOVIE V6.3.4: Cache de servers de descarga (por pelicula)
    // ----------------------------------------------------------------------

    /**
     * Entrada de la cache de servers de descarga para una pelicula.
     *
     * - [candidate]: TODOS los servers encontrados en la ultima busqueda.
     * - [lastLanguage]: ultimo idioma elegido por el usuario (para reofrecer
     *   la lista de servers de ese mismo idioma cuando un server falla).
     * - [lastServerSrc]: src del server que se esta descargando (o el ultimo
     *   que se intento) — al fallar, este es el que se excluye de la lista.
     * - [failedServers]: servers que fallaron en ESTA consulta. No es
     *   persistente: la proxima busqueda (nuevo click en Descargar) vuelve
     *   a incluirlos porque los links cambian constantemente.
     */
    private data class DownloadServerCacheEntry(
        val candidate: DownloadCandidate,
        var lastLanguage: String? = null,
        var lastServerSrc: String? = null,
        val failedServers: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf()),
    )

    /**
     * Cache en memoria (muere al cerrar la app — a proposito, los links
     * cambian constantemente). SOLO afecta al flujo de descarga: la cache
     * general de la app (home, userdata) queda intacta.
     */
    private val downloadServerCache = ConcurrentHashMap<String, DownloadServerCacheEntry>()

    /**
     * Setea (reemplaza) la cache de servers de una pelicula. Se llama cada vez
     * que el usuario pulsa "Descargar" y la busqueda termina OK — asi la cache
     * siempre contiene links frescos.
     */
    fun setDownloadServerCache(movieId: String, candidate: DownloadCandidate) {
        downloadServerCache[movieId] = DownloadServerCacheEntry(candidate)
    }

    /**
     * Servers disponibles para un idioma, EXCLUYENDO los que ya fallaron en
     * esta consulta. Devuelve lista vacia si no hay cache (p.ej. proceso
     * reiniciado) o no hay servers vivos para ese idioma.
     */
    fun availableServersFor(movieId: String, language: String): List<Video.Server> {
        val entry = downloadServerCache[movieId] ?: return emptyList()
        return entry.candidate.serversByLanguage[language]
            ?.filter { it.src !in entry.failedServers }
            ?: emptyList()
    }

    /**
     * Datos de la cache para el flujo de reintento (idioma + servers vivos).
     */
    data class DownloadServerSnapshot(
        val language: String,
        val servers: List<Video.Server>,
    )

    /**
     * Snapshot para reofrecer la lista tras un fallo: marca el server que
     * fallo como excluido SOLO para esta consulta y devuelve los servers
     * restantes del ultimo idioma elegido. Si no queda ninguno (o no hay
     * cache), devuelve null → la UI no muestra la lista de nuevo.
     */
    fun markFailedAndSnapshot(movieId: String): DownloadServerSnapshot? {
        val entry = downloadServerCache[movieId] ?: return null
        val failedSrc = entry.lastServerSrc ?: return null
        val language = entry.lastLanguage ?: return null
        entry.failedServers.add(failedSrc)
        val remaining = entry.candidate.serversByLanguage[language]
            ?.filter { it.src !in entry.failedServers }
            ?: emptyList()
        if (remaining.isEmpty()) return null
        return DownloadServerSnapshot(language = language, servers = remaining)
    }

    /**
     * Registra el server/idioma que se va a descargar ahora (para saber a
     * quien excluir si la descarga falla).
     */
    fun setActiveDownloadServer(movieId: String, language: String, serverSrc: String) {
        val entry = downloadServerCache[movieId] ?: return
        entry.lastLanguage = language
        entry.lastServerSrc = serverSrc
    }

    /**
     * Limpia la cache de servers de una pelicula. Se llama al pulsar
     * "Descargar" (antes de la nueva busqueda) para forzar links frescos.
     */
    fun clearDownloadServerCache(movieId: String) {
        downloadServerCache.remove(movieId)
    }

    // ----------------------------------------------------------------------
    // HEAD request to estimate file size
    // ----------------------------------------------------------------------

    private val headClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * WLFMOVIE V6.3: Cliente HTTP para descargas HLS.
     *
     * Optimizado para velocidad:
     *  - Connection pool de 32 conexiones keep-alive (16 segmentos paralelos + headroom).
     *  - Timeouts amplios: connect 20s, read 30s, write 60s (algunos CDNs tardan en
     *    establecer la primera conexion TLS pero despues bombean rapido).
     *  - retryOnConnectionFailure(true) — si una conexion se rompe a mitad, OkHttp la
     *    reestablece transparentemente (no afecta a otros segmentos paralelos).
     *
     * WLFMOVIE V6.3.2: Bajado readTimeout de 60s a 30s. El watchdog de HlsDownloader
     * tambien dispara a 30s sin progreso, asi que maximo entre watchdog detecta stall
     * y catch captura es ~30s (antes era hasta 60s con el readTimeout antiguo).
     * Si algun segmento legítimamente tarda >30s en recibir un byte (raro con
     * segmentos de 2-5 MB), downloadSegmentWithRetry lo reintenta con backoff.
     */
    val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // WLFMOVIE V6.3: pool mas grande para soportar 16 conexiones paralelas
            // sin agotar el pool. 32 max, 16 idle (keep-alive de 5 min).
            .connectionPool(okhttp3.ConnectionPool(32, 5, TimeUnit.MINUTES))
            .build()
    }

    /**
     * Hace un HEAD request para estimar el tamano del archivo a descargar.
     * Solo tiene sentido para Remux (MP4 directo). Para HLS no funciona
     * (no hay un Content-Length unico) — la UI se queda con "Calculando tamano...".
     */
    suspend fun estimateFileSize(url: String): Long? {
        return runCatching {
            val request = Request.Builder().url(url).head().build()
            headClient.newCall(request).execute().use { resp ->
                val len = resp.header("Content-Length")?.toLongOrNull()
                if (len != null && len > 0L) len else null
            }
        }.getOrNull()
    }

    // ----------------------------------------------------------------------
    // Start / Cancel
    // ----------------------------------------------------------------------

    /**
     * Encola WorkManager (reintentos) y arranca el foreground service.
     * Devuelve el unique workName para que la UI pueda observar su estado.
     *
     * WLFMOVIE V6.3: `kind` siempre deberia ser HLS. Si se pasa REMUX por error
     * (por datos persistedos antigos), el service lo rutea a HLS igualmente porque
     * el CDN de Remux esta caido.
     */
    fun startDownload(
        context: Context,
        movie: Movie,
        language: String,
        url: String,
        serverName: String,
        kind: Kind,
    ): String {
        val movieId = movie.id
        val workName = workNameFor(movieId)
        val downloadDir = context.getDir(MovieDownloadService.DIR_NAME, Context.MODE_PRIVATE).absolutePath

        // WLFMOVIE V6.3: forzar HLS siempre. Remux CDN esta caido permanentemente.
        val effectiveKind = Kind.HLS

        val data = MovieDownloadWorker.inputDataOf(
            movieId = movieId,
            title = movie.title,
            poster = movie.poster,
            banner = movie.banner,
            url = url,
            language = language,
            serverName = serverName,
            kind = effectiveKind.name,
            headersJson = "{}",
            downloadDir = downloadDir,
        )

        val request = OneTimeWorkRequestBuilder<MovieDownloadWorker>()
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.REPLACE,
            request,
        )

        // Arrancar el servicio en foreground inmediatamente.
        try {
            MovieDownloadService.startDownload(
                context.applicationContext,
                movieId,
                movie.title,
                movie.poster,
                movie.banner,
                url,
                language,
                serverName,
                effectiveKind,
                "{}",
                downloadDir,
            )
        } catch (e: Throwable) {
            com.mew.wlfmovie.utils.CrashLogger.logError(
                tag = "DownloadStart",
                message = "No se pudo arrancar MovieDownloadService para $movieId: ${e.message}",
                throwable = e,
                context = "url=$url, server=$serverName, lang=$language, kind=$effectiveKind",
            )
            publishFailed(movieId, "No se pudo iniciar el servicio de descarga: ${e.message}")
        }

        return workName
    }

    /**
     * Cancela una descarga: cancela el WorkManager (si esta encolado) y arranca el
     * service con ACTION_CANCEL para matar el job en curso y borrar el parcial.
     */
    fun cancelDownload(context: Context, movieId: String) {
        val workName = workNameFor(movieId)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName)
        runCatching { MovieDownloadService.startCancel(context.applicationContext, movieId) }
        publishCancelled(movieId)
    }

    // ----------------------------------------------------------------------
    // Progress observation
    // ----------------------------------------------------------------------

    fun observeProgress(movieId: String): Flow<DownloadProgressState> {
        return combine(
            progressFlow,
            AppDatabase.getInstance(com.mew.wlfmovie.StreamFlixApp.instance).downloadDao().getByIdAsFlow(movieId),
        ) { live, dbRow ->
            val liveState = live[movieId]
            when {
                liveState is DownloadProgressState.Downloading -> liveState
                liveState is DownloadProgressState.Failed -> liveState
                liveState is DownloadProgressState.Cancelled -> liveState
                dbRow != null && File(dbRow.filePath).exists() ->
                    DownloadProgressState.Completed(dbRow.filePath)
                else -> DownloadProgressState.Idle
            }
        }
    }

    /**
     * WLFMOVIE V6.3.3: Snapshot SINCRONO del estado en vivo de una descarga.
     * Lo usa MovieViewHolder.displayMovieMobile para pintar el estado inicial
     * al abrir la pantalla de detalles SIN esperar la primera emision del
     * Flow (el combine de observeProgress tarda porque incluye una query de
     * Room). Si no hay estado en vivo (descarga idle/completada en DB),
     * devuelve Idle — el collector corrige con el estado de DB enseguida.
     */
    fun snapshotProgressState(movieId: String): DownloadProgressState {
        return when (val live = progressFlow.value[movieId]) {
            is DownloadProgressState.Downloading -> live
            is DownloadProgressState.Failed -> live
            is DownloadProgressState.Cancelled -> live
            else -> DownloadProgressState.Idle
        }
    }

    /**
     * WLFMOVIE V6.3.1: Emite true si hay CUALQUIER descarga en curso en este
     * momento (cualquier movieId). Lo usa la UI para:
     *  - Deshabilitar el boton de descarga de OTRAS peliculas mientras
     *    una descarga esta corriendo (evita paralelas que pueden saturar
     *    el CDN y confundir al usuario).
     *  - Mostrar el texto "Espera..." en esos botones bloqueados.
     */
    fun observeAnyActiveDownload(): Flow<Boolean> = progressFlow.map { live ->
        live.values.any { it is DownloadProgressState.Downloading }
    }

    /**
     * WLFMOVIE V6.3.1: Snapshot sincrono — true si hay una descarga en curso
     * ahora mismo. Lo usa [com.mew.wlfmovie.adapters.viewholders.MovieViewHolder.startNewDownloadFlow]
     * para decidir si mostrar el dialogo "Ya hay una descarga en curso".
     */
    fun isAnyDownloadActive(): Boolean =
        progressFlow.value.values.any { it is DownloadProgressState.Downloading }

    fun observeAllDownloads(context: Context): Flow<List<DownloadedMovie>> {
        return AppDatabase.getInstance(context.applicationContext)
            .downloadDao()
            .getAllAsFlow()
            .map { rows ->
                // Filtrar rows huerfanas (archivo fisico borrado).
                rows.filter { File(it.filePath).exists() }
            }
    }

    // ----------------------------------------------------------------------
    // Delete / Verify
    // ----------------------------------------------------------------------

    suspend fun deleteDownload(context: Context, id: String) {
        val dao = AppDatabase.getInstance(context.applicationContext).downloadDao()
        val row = dao.getById(id)
        if (row != null) {
            runCatching { File(row.filePath).delete() }
            // WLFMOVIE V6.3.4: borrar tambien el poster/banner local (artwork
            // offline) para no dejar archivos huerfanos de ~100-500 KB.
            runCatching { artworkFileFor(context, id, isPoster = true).delete() }
            runCatching { artworkFileFor(context, id, isPoster = false).delete() }
        }
        dao.deleteById(id)
        // Tambien limpiar notificacion y estado en vivo si quedaba.
        MovieDownloadNotifier.cancel(context.applicationContext, id)
        publishCancelled(id)
        // WLFMOVIE V6.3.4: la cache de servers de esta pelicula ya no sirve
        // (la descarga se borro) — liberarla.
        clearDownloadServerCache(id)
    }

    suspend fun verifyExists(context: Context, id: String): Boolean {
        val dao = AppDatabase.getInstance(context.applicationContext).downloadDao()
        val row = dao.getById(id) ?: return false
        val file = File(row.filePath)
        return if (file.exists()) true else {
            dao.deleteById(id)
            false
        }
    }

    suspend fun verifyAll(context: Context) {
        val dao = AppDatabase.getInstance(context.applicationContext).downloadDao()
        val rows = dao.getAll()
        for (row in rows) {
            if (!File(row.filePath).exists()) {
                dao.deleteById(row.id)
            }
        }
    }

    // ----------------------------------------------------------------------
    // Publisher API (called by MovieDownloadService)
    // ----------------------------------------------------------------------

    fun publishDownloading(
        movieId: String,
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBps: Long,
    ) {
        progressFlow.value = progressFlow.value.toMutableMap().apply {
            put(movieId, DownloadProgressState.Downloading(progress, downloadedBytes, totalBytes, speedBps))
        }
    }

    fun publishCompleted(movieId: String, filePath: String) {
        progressFlow.value = progressFlow.value.toMutableMap().apply {
            put(movieId, DownloadProgressState.Completed(filePath))
        }
        // Tras 1s, limpiar el estado en vivo para que la row de DB tome el control.
        GlobalScope.launch {
            delay(1_500L)
            progressFlow.value = progressFlow.value.toMutableMap().apply { remove(movieId) }
        }
    }

    fun publishFailed(movieId: String, error: String) {
        progressFlow.value = progressFlow.value.toMutableMap().apply {
            put(movieId, DownloadProgressState.Failed(error))
        }
        GlobalScope.launch {
            delay(3_000L)
            progressFlow.value = progressFlow.value.toMutableMap().apply { remove(movieId) }
        }
    }

    fun publishCancelled(movieId: String) {
        progressFlow.value = progressFlow.value.toMutableMap().apply {
            put(movieId, DownloadProgressState.Cancelled)
        }
        GlobalScope.launch {
            delay(1_500L)
            progressFlow.value = progressFlow.value.toMutableMap().apply { remove(movieId) }
        }
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    fun workNameFor(movieId: String): String = "$WORK_PREFIX$movieId"

    /**
     * Formatea un idioma crudo ("latino") a su label traducido.
     * El contexto es necesario para resolver strings.
     */
    fun languageLabel(context: Context, language: String): String = when (language) {
        "latino" -> context.getString(com.mew.wlfmovie.R.string.download_language_latino)
        "subtitulado" -> context.getString(com.mew.wlfmovie.R.string.download_language_subtitulado)
        "castellano" -> context.getString(com.mew.wlfmovie.R.string.download_language_castellano)
        else -> language.replaceFirstChar { it.uppercase() }
    }

    /**
     * Formatea bytes en formato humano (usado en dialogs de tamano estimado).
     */
    fun humanReadableBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIdx = 0
        while (size >= 1024.0 && unitIdx < units.size - 1) {
            size /= 1024.0
            unitIdx++
        }
        return if (unitIdx == 0) "${bytes.toInt()} B"
        else String.format(java.util.Locale.US, "%.1f %s", size, units[unitIdx])
    }

    // ----------------------------------------------------------------------
    // WLFMOVIE V6.3.4: Artwork offline (poster/banner de peliculas descargadas)
    // ----------------------------------------------------------------------

    /**
     * Archivo local donde se guarda el poster/banner de una pelicula descargada.
     * Vive en el mismo directorio privado de descargas, con nombre
     * poster_{movieId}.jpg / banner_{movieId}.jpg. Si aun no existe, devuelve
     * el File objetivo (para poder escribirlo).
     */
    fun artworkFileFor(context: Context, movieId: String, isPoster: Boolean): File {
        // getDir() ya devuelve un File (y crea el directorio si no existe) —
        // NO envolver en File(...) porque el constructor File(File) no existe.
        val dir = context.getDir(MovieDownloadService.DIR_NAME, Context.MODE_PRIVATE)
        if (!dir.exists()) dir.mkdirs()
        val safeId = movieId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, if (isPoster) "poster_$safeId.jpg" else "banner_$safeId.jpg")
    }

    /**
     * Descarga una imagen (poster o banner) a archivo local. Best-effort:
     * devuelve null si falla (sin internet, 404, etc.) — NUNCA tira.
     *
     * El usuario reporto que al entrar sin conexion las peliculas descargadas
     * aparecen sin caratula: el poster era una URL de TMDB que obviamente no
     * carga offline. Ahora guardamos la imagen junto al video.
     */
    private suspend fun downloadArtworkToFile(url: String?, target: File): String? {
        if (url.isNullOrBlank() || !url.startsWith("http")) return null
        return try {
            val request = Request.Builder().url(url).build()
            withTimeoutOrNull(20_000L) {
                downloadClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withTimeoutOrNull null
                    val body = resp.body ?: return@withTimeoutOrNull null
                    val tmp = File(target.absolutePath + ".tmp")
                    body.byteStream().use { input ->
                        java.io.FileOutputStream(tmp).use { output ->
                            input.copyTo(output, 16 * 1024)
                        }
                    }
                    if (tmp.length() <= 0L) {
                        tmp.delete()
                        return@withTimeoutOrNull null
                    }
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) {
                        tmp.delete()
                        return@withTimeoutOrNull null
                    }
                    target.absolutePath
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "downloadArtworkToFile fallo para ${target.name}: ${e.message}")
            null
        }
    }

    /**
     * Descarga poster + banner de una pelicula recien descargada y devuelve
     * las rutas LOCALES (o null si fallo cada una). Lo llama
     * MovieDownloadService al completar una descarga, ANTES de insertar la
     * row en DB — asi la row queda con rutas locales que Glide carga sin
     * internet.
     *
     * Si la descarga del artwork falla (p.ej. red inestable justo al final),
     * la row se inserta igual con las URLs remotas — el video es lo importante
     * — y el backfill lo repara en cuanto haya red.
     */
    suspend fun downloadArtworkForMovie(
        context: Context,
        movieId: String,
        posterUrl: String?,
        bannerUrl: String?,
    ): Pair<String?, String?> = coroutineScope {
        val posterDeferred = async(Dispatchers.IO) {
            downloadArtworkToFile(posterUrl, artworkFileFor(context, movieId, isPoster = true))
        }
        val bannerDeferred = async(Dispatchers.IO) {
            downloadArtworkToFile(bannerUrl, artworkFileFor(context, movieId, isPoster = false))
        }
        Pair(posterDeferred.await(), bannerDeferred.await())
    }

    /**
     * WLFMOVIE V6.3.4: Repara el artwork offline de descargas ANTIGUAS (hechas
     * antes de esta version, cuyas rows guardan URLs remotas de TMDB en vez de
     * rutas locales). Para cada row cuyo poster/banner empieza por "http",
     * descarga la imagen, actualiza la row, y Room re-emitira el Flow → la UI
     * del home se refresca sola con las imagenes locales.
     *
     * Se llama desde HomeViewModel.getHome() (fire-and-forget) la primera vez
     * que hay descargas en la sesion. Con flag en memoria para no repetirlo
     * en cada carga del home.
     */
    @Volatile
    private var artworkBackfillDone = false

    fun backfillDownloadedArtworkIfNeeded(context: Context) {
        if (artworkBackfillDone) return
        artworkBackfillDone = true
        val appContext = context.applicationContext
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val dao = AppDatabase.getInstance(appContext).downloadDao()
                val rows = dao.getAll().filter { File(it.filePath).exists() }
                for (row in rows) {
                    val posterIsRemote = row.poster?.startsWith("http") == true
                    val bannerIsRemote = row.banner?.startsWith("http") == true
                    if (!posterIsRemote && !bannerIsRemote) continue

                    val localPoster = if (posterIsRemote) {
                        downloadArtworkToFile(row.poster, artworkFileFor(appContext, row.id, isPoster = true))
                    } else null
                    val localBanner = if (bannerIsRemote) {
                        downloadArtworkToFile(row.banner, artworkFileFor(appContext, row.id, isPoster = false))
                    } else null
                    if (localPoster == null && localBanner == null) continue

                    dao.insert(
                        row.copy(
                            poster = localPoster ?: row.poster,
                            banner = localBanner ?: row.banner,
                        )
                    )
                    Log.i(TAG, "Backfill artwork OK para descarga ${row.id}: poster=${localPoster != null} banner=${localBanner != null}")
                }
                // Si fallo alguna descarga (sin red), reintentar en la proxima sesion.
                val stillRemote = dao.getAll().any {
                    it.poster?.startsWith("http") == true || it.banner?.startsWith("http") == true
                }
                if (stillRemote) artworkBackfillDone = false
            } catch (e: Exception) {
                Log.w(TAG, "backfillDownloadedArtworkIfNeeded fallo: ${e.message}")
                artworkBackfillDone = false
            }
        }
    }
}
