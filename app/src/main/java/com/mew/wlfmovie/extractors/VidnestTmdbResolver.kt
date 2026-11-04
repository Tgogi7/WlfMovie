package com.mew.wlfmovie.extractors

import android.util.Log
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.Cipher
import java.security.SecureRandom
import java.security.MessageDigest
import android.util.Base64
import androidx.media3.common.MimeTypes
import com.mew.wlfmovie.models.Video
import java.io.ByteArrayOutputStream
import java.net.URI
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * VIDNEST_TMDB_SOURCE_V1
 *
 * Resolver for the separate Vidnest TMDb source at vidnest.fun /
 * new.vidnest.fun.
 *
 * This is intentionally NOT part of VidnestExtractor, which remains the
 * extractor for the unrelated vidnest.io video host.
 */
object VidnestTmdbResolver {

    fun extract(
        link: String,
    ): Video? =
        resolveInternal(link)

    private val modernVidnestClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun resolveInternal(
        link: String,
    ): Video? {
        val uri = runCatching {
            URI(link)
        }.getOrNull() ?: return null

        val parts = uri.path
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }

        /*
         * VIDNEST_TMDB_MOVIE_TV_V1
         *
         * Current public Vidnest routes:
         *
         * movie/{tmdbId}
         * tv/{tmdbId}/{season}/{episode}
         */
        val contentType =
            parts.getOrNull(0)
                ?: return null

        if (
            contentType != "movie" &&
            contentType != "tv"
        ) {
            return null
        }

        val tmdbId =
            parts.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?: return null

        val season =
            if (contentType == "tv") {
                parts.getOrNull(2)
                    ?.takeIf { it.isNotBlank() }
                    ?: return null
            } else {
                null
            }

        val episode =
            if (contentType == "tv") {
                parts.getOrNull(3)
                    ?.takeIf { it.isNotBlank() }
                    ?: return null
            } else {
                null
            }

        val base =
            "https://new.vidnest.fun"

        val candidates =
            if (contentType == "movie") {
                listOf(
                "Catflix" to "$base/yflix/movie/$tmdbId",
                "Filxer" to "$base/rogflix/movie/$tmdbId",

                // Currently sometimes upstream 502, but keep it.
                "Prime" to "$base/rpmvid/movie/$tmdbId",

                "Gama" to "$base/vidzee/movie/$tmdbId",
                "Zeta" to "$base/nextgencloudfabric/movie/$tmdbId",
                "Alfa" to "$base/onehd/movie/$tmdbId",
                "Ophim" to "$base/klikxxi/movie/$tmdbId",
                "Beta" to "$base/flixhq/movie/$tmdbId",

                // Currently sometimes upstream 502, but keep them.
                "Lamda" to "$base/allmovies/movie/$tmdbId",
                "Hexa" to "$base/vidlink/movie/$tmdbId",
                )
            } else {
                val seasonNo =
                    season ?: return null

                val episodeNo =
                    episode ?: return null

                /*
                 * Current vidnest.fun TV bundle, 2026-10-10.
                 *
                 * Most backends:
                 *   <backend>/tv/TMDB/SEASON/EPISODE
                 *
                 * Sigma is special because its configured root is
                 * /hollymoviehd without the /tv suffix.
                 */
                listOf(
                    "Catflix" to
                        "$base/yflix/tv/$tmdbId/$seasonNo/$episodeNo",

                    "Prime" to
                        "$base/rpmvid/tv/$tmdbId/$seasonNo/$episodeNo",

                    "Filxer" to
                        "$base/rogflix/tv/$tmdbId/$seasonNo/$episodeNo",

                    "Alfa" to
                        "$base/onehd/tv/$tmdbId/$seasonNo/$episodeNo",

                    "Beta" to
                        "$base/flixhq/tv/$tmdbId/$seasonNo/$episodeNo",

                    "Zeta" to
                        "$base/nextgencloudfabric/tv/$tmdbId/$seasonNo/$episodeNo",

                    "Ophim" to
                        "$base/klikxxi/tv/$tmdbId/$seasonNo/$episodeNo",

                    "Sigma" to
                        "$base/hollymoviehd/tv/$tmdbId/$seasonNo/$episodeNo",

                    "Gama" to
                        "$base/vidzee/tv/$tmdbId/$seasonNo/$episodeNo",

                    "Lamda" to
                        "$base/allmovies/tv/$tmdbId/$seasonNo/$episodeNo",

                    "Hexa" to
                        "$base/vidlink/tv/$tmdbId/$seasonNo/$episodeNo",

                    /*
                     * Vidnest exposes Delta through allmovies as well.
                     * Keep it last because it mainly selects a different
                     * language from the same backend response.
                     */
                    "Delta" to
                        "$base/allmovies/tv/$tmdbId/$seasonNo/$episodeNo",
                )
            }

        for ((serverName, url) in candidates) {
            val video = runCatching {
                val request = Request.Builder()
                    .url(withVidnestNonce(url))
                    .header(
                        "User-Agent",
                        Constants.VIDNEST_USER_AGENT,
                    )
                    .header(
                        "Accept",
                        "application/json,text/plain,*/*",
                    )
                    .header(
                        "Origin",
                        "https://vidnest.fun",
                    )
                    .header(
                        "Referer",
                        "https://vidnest.fun/",
                    )
                    .build()

                modernVidnestClient
                    .newCall(request)
                    .execute()
                    .use { response ->

                        Log.d(
                            Constants.VIDNEST_TAG,
                            "$serverName HTTP ${response.code}",
                        )

                        if (!response.isSuccessful) {
                            return@use null
                        }

                        val raw =
                            response.body
                                ?.string()
                                .orEmpty()

                        if (raw.isBlank()) {
                            return@use null
                        }

                        val responseJson =
                            JSONObject(raw)

                        val decoded =
                            decodeVidnestResponse(
                                responseJson
                            )
                                ?: return@use null

                        parseVidnestVideo(
                            serverName = serverName,
                            json = decoded,
                        )
                    }
            }.onFailure { error ->
                Log.w(
                    Constants.VIDNEST_TAG,
                    "$serverName failed: ${error.message}",
                )
            }.getOrNull()

            if (video != null) {
                Log.d(
                    Constants.VIDNEST_TAG,
                    "$serverName selected",
                )

                return video
            }
        }

        Log.d(
            Constants.VIDNEST_TAG,
            "Modern API exhausted; using legacy extractor",
        )

        return null
    }

    /*
     * VIDNEST_V2_TRANSPORT
     *
     * Current vidnest.fun frontend appends a random 16-byte Base64URL
     * nonce as `_n` to every new.vidnest.fun request.
     *
     * Successful responses may be wrapped as:
     *
     * {
     *   "v": 2,
     *   "d": "...",
     *   "n": "..."
     * }
     *
     * d = Base64URL(12-byte IV + AES-GCM ciphertext/tag)
     * key = SHA-256(nonce + "|" + frontend secret)
     */
    private fun withVidnestNonce(
        url: String,
    ): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)

        val nonce =
            Base64.encodeToString(
                bytes,
                Base64.URL_SAFE or
                    Base64.NO_WRAP or
                    Base64.NO_PADDING,
            )

        val separator =
            if (url.contains("?")) "&" else "?"

        return "$url${separator}_n=$nonce"
    }

    private fun decodeVidnestResponse(
        json: JSONObject,
    ): JSONObject? {
        /*
         * Current encrypted v2 envelope.
         */
        if (
            json.optInt("v") == 2 &&
            json.has("d") &&
            json.has("n")
        ) {
            val encrypted =
                json.optString("d")
                    .takeIf { it.isNotBlank() }
                    ?: return null

            val nonce =
                json.optString("n")
                    .takeIf { it.isNotBlank() }
                    ?: return null

            return decryptVidnestV2(
                encrypted = encrypted,
                nonce = nonce,
            )
        }

        /*
         * Plain JSON responses are still valid.
         */
        if (!json.optBoolean("encrypted")) {
            return json
        }

        /*
         * Legacy PR42 response format.
         */
        val encrypted =
            json.optString("data")
                .takeIf { it.isNotBlank() }
                ?: return null

        val decoded =
            decodeVidnestBase64(encrypted)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null

        return runCatching {
            JSONObject(decoded)
        }.getOrNull()
    }

    private fun decryptVidnestV2(
        encrypted: String,
        nonce: String,
    ): JSONObject? =
        runCatching {
            val keyMaterial =
                "$nonce|${Constants.VIDNEST_V2_SECRET}"
                    .toByteArray(Charsets.UTF_8)

            val key =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(keyMaterial)

            val encryptedBytes =
                decodeVidnestBase64Url(encrypted)

            if (encryptedBytes.size <= 12) {
                return@runCatching null
            }

            val iv =
                encryptedBytes.copyOfRange(
                    0,
                    12,
                )

            val ciphertext =
                encryptedBytes.copyOfRange(
                    12,
                    encryptedBytes.size,
                )

            val cipher =
                Cipher.getInstance(
                    "AES/GCM/NoPadding"
                )

            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(
                    key,
                    "AES",
                ),
                GCMParameterSpec(
                    128,
                    iv,
                ),
            )

            val plaintext =
                cipher
                    .doFinal(ciphertext)
                    .toString(Charsets.UTF_8)
                    .trim()

            Log.d(
                Constants.VIDNEST_TAG,
                "v2 response decrypted",
            )

            JSONObject(plaintext)
        }.onFailure { error ->
            Log.w(
                Constants.VIDNEST_TAG,
                "v2 decrypt failed: ${error.message}",
            )
        }.getOrNull()

    private fun decodeVidnestBase64Url(
        input: String,
    ): ByteArray {
        val padding =
            "=".repeat(
                (4 - input.length % 4) % 4
            )

        return Base64.decode(
            input + padding,
            Base64.URL_SAFE or
                Base64.NO_WRAP,
        )
    }

    private fun decodeVidnestBase64(
        input: String,
    ): String? {
        val alphabet =
            "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/="

        val lookup =
            IntArray(256) { -1 }

        alphabet.forEachIndexed { index, char ->
            if (char.code < lookup.size) {
                lookup[char.code] = index
            }
        }

        return runCatching {
            val output =
                ByteArrayOutputStream()

            var position = 0

            while (position < input.length) {
                val block =
                    input.substring(
                        position,
                        minOf(
                            position + 4,
                            input.length,
                        ),
                    )
                        .padEnd(
                            4,
                            '=',
                        )

                val values =
                    IntArray(4) { index ->
                        val char =
                            block[index]

                        if (char == '=') {
                            64
                        } else {
                            lookup
                                .getOrElse(
                                    char.code
                                ) { -1 }
                                .takeIf {
                                    it >= 0
                                }
                                ?: 64
                        }
                    }

                output.write(
                    (
                        (values[0] shl 2) or
                            (values[1] shr 4)
                        ) and 0xff
                )

                if (values[2] != 64) {
                    output.write(
                        (
                            ((values[1] and 15) shl 4) or
                                (values[2] shr 2)
                            ) and 0xff
                    )
                }

                if (values[3] != 64) {
                    output.write(
                        (
                            ((values[2] and 3) shl 6) or
                                values[3]
                            ) and 0xff
                    )
                }

                position += 4
            }

            output
                .toByteArray()
                .toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun parseVidnestVideo(
        serverName: String,
        json: JSONObject,
    ): Video? {

        /*
         * Schema 1:
         *
         * Catflix / Filxer / Alfa / Zeta
         *
         * {
         *   "url": "...",
         *   "headers": {...}
         * }
         */
        json.optString("url")
            .takeIf {
                it.isNotBlank()
            }
            ?.let { url ->

                val subtitles =
                    parseVidnestSubtitles(
                        json
                    )

                return buildVidnestVideo(
                    serverName = serverName,
                    url = url,
                    headers =
                        parseVidnestHeaders(
                            json.optJSONObject(
                                "headers"
                            )
                        ),
                    subtitles = subtitles,
                    hls =
                        json.optBoolean(
                            "hls"
                        ) ||
                            looksLikeHls(url),
                )
            }

        /*
         * Schema 2:
         *
         * Gama / Beta
         *
         * {
         *   "streams": [...]
         * }
         */
        json.optJSONArray("streams")
            ?.let { streams ->

                if (streams.length() > 0) {
                    var selected:
                        JSONObject? = null

                    /*
                     * Sigma's current TV endpoint exposes LS-25 and GS-25.
                     * The official Vidnest frontend explicitly selects GS-25.
                     */
                    if (
                        serverName.equals(
                            "Sigma",
                            ignoreCase = true,
                        )
                    ) {
                        for (
                            index in
                            0 until streams.length()
                        ) {
                            val item =
                                streams.optJSONObject(index)
                                    ?: continue

                            if (
                                item.optString("language")
                                    .equals(
                                        "GS-25",
                                        ignoreCase = true,
                                    )
                            ) {
                                selected = item
                                break
                            }
                        }
                    }

                    /*
                     * Prefer explicit English for all other stream arrays.
                     */
                    if (selected == null) {
                    for (
                        index in
                        0 until streams.length()
                    ) {
                        val item =
                            streams.optJSONObject(
                                index
                            )
                                ?: continue

                        val language =
                            item.optString(
                                "language"
                            )

                        if (
                            language.equals(
                                "English",
                                ignoreCase = true,
                            ) ||
                            language.equals(
                                "EN",
                                ignoreCase = true,
                            )
                        ) {
                            selected = item
                            break
                        }
                    }

                    }

                    /*
                     * Original is next best choice.
                     */
                    if (selected == null) {
                        for (
                            index in
                            0 until streams.length()
                        ) {
                            val item =
                                streams.optJSONObject(
                                    index
                                )
                                    ?: continue

                            if (
                                item
                                    .optString(
                                        "language"
                                    )
                                    .equals(
                                        "Original",
                                        ignoreCase = true,
                                    )
                            ) {
                                selected = item
                                break
                            }
                        }
                    }

                    if (selected == null) {
                        selected =
                            streams.optJSONObject(0)
                    }

                    val url =
                        selected
                            ?.optString("url")
                            ?.takeIf {
                                it.isNotBlank()
                            }

                    if (url != null) {
                        val type =
                            selected
                                ?.optString(
                                    "type"
                                )
                                .orEmpty()

                        return buildVidnestVideo(
                            serverName = serverName,
                            url = url,
                            headers =
                                parseVidnestHeaders(
                                    selected
                                        ?.optJSONObject(
                                            "headers"
                                        )
                                ),
                            subtitles =
                                parseVidnestSubtitles(
                                    json
                                ),
                            hls =
                                type.equals(
                                    "hls",
                                    ignoreCase = true,
                                ) ||
                                    looksLikeHls(
                                        url
                                    ),
                        )
                    }
                }
            }

        /*
         * Schema 3:
         *
         * Ophim
         *
         * {
         *   "sources": [...]
         * }
         */
        json.optJSONArray("sources")
            ?.let { sources ->

                if (sources.length() > 0) {
                    val source =
                        sources
                            .optJSONObject(0)

                    val url =
                        source
                            ?.optString("url")
                            ?.takeIf {
                                it.isNotBlank()
                            }

                    if (url != null) {
                        val type =
                            source.optString(
                                "type"
                            )

                        return buildVidnestVideo(
                            serverName = serverName,
                            url = url,
                            headers =
                                emptyMap(),
                            subtitles =
                                parseVidnestSubtitles(
                                    json
                                ),
                            hls =
                                type.equals(
                                    "hls",
                                    ignoreCase = true,
                                ) ||
                                    looksLikeHls(
                                        url
                                    ),
                        )
                    }
                }
            }

        /*
         * Schema 4:
         *
         * Zeta may expose several URLs.
         */
        json.optJSONArray("all_urls")
            ?.let { urls ->
                for (
                    index in
                    0 until urls.length()
                ) {
                    val url =
                        urls
                            .optString(index)
                            .takeIf {
                                it.isNotBlank()
                            }
                            ?: continue

                    return buildVidnestVideo(
                        serverName = serverName,
                        url = url,
                        headers =
                            parseVidnestHeaders(
                                json.optJSONObject(
                                    "headers"
                                )
                            ),
                        subtitles =
                            parseVidnestSubtitles(
                                json
                            ),
                        hls = true,
                    )
                }
            }

        return null
    }

    private fun buildVidnestVideo(
        serverName: String,
        url: String,
        headers: Map<String, String>,
        subtitles: List<Video.Subtitle>,
        hls: Boolean,
    ): Video {
        Log.d(
            Constants.VIDNEST_TAG,
            "$serverName stream=${url.take(180)}",
        )

        val mimeType =
            when {
                hls ->
                    MimeTypes.APPLICATION_M3U8

                url
                    .substringBefore("?")
                    .endsWith(
                        ".mkv",
                        ignoreCase = true,
                    ) ->
                    MimeTypes.VIDEO_MATROSKA

                else ->
                    MimeTypes.VIDEO_MP4
            }

        return Video(
            source = url,
            headers = headers,
            subtitles = subtitles,
            type = mimeType,
            useServerSubtitleSetting = true,
        )
    }

    private fun parseVidnestHeaders(
        json: JSONObject?,
    ): Map<String, String> {
        if (json == null) {
            return emptyMap()
        }

        val result =
            linkedMapOf<String, String>()

        val keys =
            json.keys()

        while (keys.hasNext()) {
            val key =
                keys.next()

            val value =
                json
                    .optString(key)
                    .trim()

            if (value.isNotBlank()) {
                result[key] =
                    value
            }
        }

        return result
    }

    private fun parseVidnestSubtitles(
        json: JSONObject,
    ): List<Video.Subtitle> {
        val result =
            mutableListOf<Video.Subtitle>()

        val subtitles =
            json.optJSONArray(
                "subtitles"
            )
                ?: return result

        for (
            index in
            0 until subtitles.length()
        ) {
            val item =
                subtitles
                    .optJSONObject(index)
                    ?: continue

            val url =
                item
                    .optString("url")
                    .takeIf {
                        it.isNotBlank()
                    }
                    ?: continue

            val label =
                item
                    .optString(
                        "lang",
                        "Subtitle",
                    )

            result +=
                Video.Subtitle(
                    label = label,
                    file = url,
                )
        }

        return result
    }

    private fun looksLikeHls(
        url: String,
    ): Boolean {
        val clean =
            url.substringBefore("?")
                .lowercase()

        return clean.endsWith(
            ".m3u8"
        ) ||
            clean.endsWith(
                ".txt"
            ) ||
            clean.contains(
                "/hls/"
            ) ||
            clean.contains(
                "/hls2/"
            ) ||
            clean.contains(
                "/hls3/"
            )
    }

    private object Constants {
        const val VIDNEST_V2_SECRET =
            "M0th3rFuk3rG4yB4st4rd_wSnX7qV9kL2pR8yQm4zN1Fg"

        const val VIDNEST_TAG =
            "VidnestModern"

        const val VIDNEST_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/140.0.0.0 Safari/537.36"
    }

}
