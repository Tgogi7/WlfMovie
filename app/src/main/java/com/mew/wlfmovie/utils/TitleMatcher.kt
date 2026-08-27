package com.mew.wlfmovie.utils

import android.util.Log
import com.mew.wlfmovie.adapters.AppAdapter
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.models.TvShow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * WLFMOVIE V6.3.6: matcher de titulos con VALIDACION DE AÑO y soporte de
 * TITULOS ALTERNATIVOS multi-idioma.
 *
 * Problema que resuelve (reportado por el usuario):
 *  - Descargo "Toy Story 5" y bajo un corto de terror "Roy Story": el matcher
 *    historico solo compara TITULO (cobertura de palabras >= 60%), jamas el año.
 *  - "Noche de Bodas" (2019): los servers ofrecen la de 2024 y "Noche de Bodas 2",
 *    pero nunca la de 2019 — el primer resultado con titulo parecido gana.
 *  - En TMDB-es la pelicula se llama "Noche de Bodas" pero en Mexico se conoce
 *    como "Boda Sangrienta" — los providers latinos la listan con ese nombre y
 *    el matcher es nunca la encuentra.
 *
 * Solucion:
 *  1. [searchTitlesFor]: trae de TMDB los titulos es-MX, es-CO y en (+ el
 *     original) de la pelicula. Orden de busqueda: es (el que ya usabamos) ->
 *     es-MX -> es-CO -> en -> original. Cacheado por movieId.
 *  2. [scoreMatch]: puntua cada resultado del provider. Para PELICULAS:
 *     - Solo items Movie pueden matchear una pelicula (una serie no "es" la
 *       pelicula: antes "Noche de Bodas" podia matchear la SERIE de TV).
 *     - Si conocemos el año objetivo (TMDB) y el del candidato: años distintos
 *       (tolerancia ±1) => RECHAZADO. La de 2024 ya no matchea la de 2019.
 *     - Numeros de secuela distintos ("... 2" vs "...") => RECHAZADO.
 *     - Gana el MEJOR puntaje (no el primero): titulo exacto > prefijo >
 *       cobertura; año exacto suma mas que ±1 o año desconocido.
 *  3. Los flujos (ver online + descargar) intentan el titulo es primero; si
 *     nadie matchea con nombre+año, prueban los titulos alternativos UNO POR
 *     UNO y se quedan con el primero que matchee.
 *
 * Para SERIES/episodios NO se usa (comportamiento historico intacto).
 */
object TitleMatcher {

    private const val TAG = "TitleMatcher"

    /** Titulos de busqueda para una pelicula + el año que TMDB reporta. */
    data class SearchTitles(
        val titles: List<String>,
        val year: Int?,
    )

    // ------------------------------------------------------------------
    // Cache de titulos alternativos por movieId (vive lo que la app)
    // ------------------------------------------------------------------
    private val altTitlesCache = ConcurrentHashMap<String, SearchTitles>()

    /**
     * Devuelve los titulos con los que buscar esta pelicula en los providers:
     * [primario(es), es-MX, es-CO, en, original] — deduplicados, en orden de
     * prioridad. El primario siempre va primero (es el que el usuario ve).
     *
     * Best-effort: si TMDB no responde, devuelve solo el primario (el flujo
     * funciona como antes). Timeout de 4s por idioma, en paralelo.
     */
    suspend fun searchTitlesFor(tmdbId: String, primaryTitle: String): SearchTitles {
        altTitlesCache[tmdbId]?.let { return it }
        val idInt = tmdbId.toIntOrNull()
        if (idInt == null) return SearchTitles(listOf(primaryTitle), null)

        val details = coroutineScope {
            listOf("es-MX", "es-CO", "en").map { lang ->
                async(Dispatchers.IO) {
                    try {
                        withTimeoutOrNull(4_000L) { TMDb3.Movies.details(idInt, language = lang) }
                    } catch (e: Exception) {
                        Log.w(TAG, "TMDB details ($lang) fallo para $tmdbId: ${e.message}")
                        null
                    }
                }
            }.awaitAll()
        }

        val titles = mutableListOf(primaryTitle)
        for (d in details) {
            d?.title?.takeIf { it.isNotBlank() && it !in titles }?.let { titles.add(it) }
        }
        // El titulo original como ultimo recurso (peliculas no-inglesas).
        details.lastOrNull()?.originalTitle
            ?.takeIf { it.isNotBlank() && it !in titles }
            ?.let { titles.add(it) }

        var year: Int? = null
        for (d in details) {
            if (year == null) year = d?.releaseDate?.let { parseYear(it) }
        }

        val result = SearchTitles(titles, year)
        if (altTitlesCache.size > 100) altTitlesCache.clear()
        altTitlesCache[tmdbId] = result
        Log.i(TAG, "searchTitlesFor $tmdbId: $titles (year=$year)")
        return result
    }

    // ------------------------------------------------------------------
    // Normalizacion (identica al matcher historico)
    // ------------------------------------------------------------------

    fun normalize(s: String): String {
        val noAccents = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return noAccents.lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*]"), " ")
            .replace(Regex("(?i)\\btemporada\\s*\\d+\\b"), " ")
            .replace(Regex("(?i)\\bseason\\s*\\d+\\b"), " ")
            .replace(Regex("(?i)\\btemporada\\b"), " ")
            .replace(Regex("(?i)\\bseason\\b"), " ")
            .replace(Regex("(?i)\\bcompleta\\b"), " ")
            .replace(Regex("(?i)\\bcompleto\\b"), " ")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun wordsOf(s: String): Set<String> =
        normalize(s).split(" ").filter { it.length > 2 }.toSet()

    // ------------------------------------------------------------------
    // Años
    // ------------------------------------------------------------------

    /** "2019-06-14" | "2019" -> 2019 */
    fun parseYear(dateStr: String?): Int? {
        if (dateStr.isNullOrBlank()) return null
        val m = Regex("^(\\d{4})").find(dateStr.trim()) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /** Extrae un "(2019)" del titulo crudo del provider (antes de normalizar). */
    fun yearFromTitle(rawTitle: String): Int? {
        val m = Regex("\\((19|20)\\d{2}\\)").find(rawTitle) ?: return null
        return m.value.filter { it.isDigit() }.toIntOrNull()
    }

    /** Año del item: el campo released del provider, o el "(2019)" del titulo. */
    fun yearOf(item: AppAdapter.Item): Int? = when (item) {
        is Movie -> item.released?.get(Calendar.YEAR) ?: yearFromTitle(item.title)
        is TvShow -> item.released?.get(Calendar.YEAR) ?: yearFromTitle(item.title)
        else -> null
    }

    // ------------------------------------------------------------------
    // Secuelas
    // ------------------------------------------------------------------

    private val ROMAN = mapOf(
        "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6, "vii" to 7,
        "viii" to 8, "ix" to 9, "x" to 10, "xi" to 11, "xii" to 12, "xiii" to 13,
        "xiv" to 14, "xv" to 15,
    )

    /**
     * Numeros de secuela del titulo normalizado: arabigos de 1-2 digitos
     * ("toy story 5" -> {5}) y romanos ("rambo iii" -> {3}).
     * "1917" NO cuenta (4 digitos) y "(2019)" tampoco (la normalizacion
     * elimina el contenido entre parentesis).
     */
    fun sequelNumbers(normalizedTitle: String): Set<Int> {
        val out = mutableSetOf<Int>()
        for (token in normalizedTitle.split(" ")) {
            if (token.length in 1..2 && token.all { it.isDigit() }) {
                token.toIntOrNull()?.let { out.add(it) }
            } else {
                ROMAN[token]?.let { out.add(it) }
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Scoring
    // ------------------------------------------------------------------

    /**
     * Puntua un resultado de busqueda contra el objetivo. 0 = NO es match.
     * Mayor = mejor.
     *
     * - El titulo debe pasar el matching historico (exacto / prefijo<=12 /
     *   cobertura >= 0.6). El año SOLO valida: nunca matchea solo.
     * - [moviesOnly] (peliculas): items TvShow rechazados; año distinto al
     *   objetivo (±1 de tolerancia) rechazado; numeros de secuela distintos
     *   rechazados.
     * - Año exacto +30, ±1 +10, desconocido +0 (no penaliza: hay providers
     *   que no exponen el año).
     */
    fun scoreMatch(
        item: AppAdapter.Item,
        target: String,
        targetYear: Int?,
        moviesOnly: Boolean,
    ): Int {
        val title = when (item) {
            is Movie -> item.title
            is TvShow -> item.title
            else -> return 0
        }
        if (moviesOnly && item !is Movie) return 0

        val nItem = normalize(title)
        val nTarget = normalize(target)
        if (nItem.isEmpty() || nTarget.isEmpty()) return 0

        val titleScore: Int = when {
            nItem == nTarget -> 100
            (nItem.startsWith(nTarget) || nTarget.startsWith(nItem)) &&
                abs(nItem.length - nTarget.length) <= 12 -> 80
            else -> {
                val itemWords = wordsOf(title)
                val targetWords = wordsOf(target)
                if (itemWords.isEmpty() || targetWords.isEmpty()) return 0
                val coverage = itemWords.intersect(targetWords).size.toFloat() / targetWords.size
                if (coverage < 0.6f) return 0
                (60f * coverage).toInt()
            }
        }

        // Guard de secuelas (peliculas): "Noche de Bodas 2" NO es "Noche de
        // Bodas", y "Toy Story" (1995) NO es "Toy Story 5".
        if (moviesOnly && sequelNumbers(nItem) != sequelNumbers(nTarget)) return 0

        if (targetYear != null) {
            val candYear = yearOf(item)
            if (candYear != null) {
                val diff = abs(candYear - targetYear)
                return when (diff) {
                    0 -> titleScore + 30
                    1 -> titleScore + 10
                    else -> 0
                }
            }
        }
        return titleScore
    }

    /**
     * El MEJOR match de la lista (no el primero): el de mayor puntaje; a igual
     * puntaje gana el que aparece antes en los resultados del provider.
     */
    fun bestMatch(
        items: List<AppAdapter.Item>,
        target: String,
        targetYear: Int?,
        moviesOnly: Boolean,
    ): AppAdapter.Item? {
        var best: AppAdapter.Item? = null
        var bestScore = 0
        for (item in items) {
            val score = scoreMatch(item, target, targetYear, moviesOnly)
            if (score > bestScore) {
                bestScore = score
                best = item
            }
        }
        return best
    }
}
