package com.mew.wlfmovie.extractors

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mew.wlfmovie.models.Video
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * WLFMOVIE V6: Extractor VOE para Gnula (separado del VoeExtractor original).
 *
 * Resuelve el gate ALTCHA PoW manualmente en Kotlin puro (sin WebView).
 *
 * Flujo:
 *  1. GET https://voe.sx/e/CODE → recibe JS redirect al dominio rotativo actual.
 *  2. GET https://<rotating-domain>/e/CODE → recibe HTML del gate con ALTCHA.
 *  3. GET https://<rotating-domain>/<challenge-path> → recibe JSON del challenge
 *  4. Resolver PoW: iterar counter, PBKDF2-HMAC-SHA256(nonce||uint32_BE(counter), salt, cost, keyLength)
 *     hasta que el derivedKey empiece con keyPrefix (hex).
 *  5. Construir payload JSON: {challenge:{parameters,signature}, solution:{counter,derivedKey,time}}
 *  6. base64(JSON(payload)) → enviar como campo "altcha" en el POST al form action.
 *  7. El POST responde con la página real del video, que contiene el <script type="application/json">
 *     con la data encriptada.
 *  8. Aplicar decryptF7() para obtener la URL del video.
 */
class VoeExtractorGnula : Extractor() {

    override val name = "VOE HD"
    override val mainUrl = "https://voe.sx"
    override val aliasUrls = listOf(
        "https://jilliandescribecompany.com",
        "https://mikaylaarealike.com",
        "https://christopheruntilpoint.com",
        "https://walterprettytheir.com",
        "https://crystaltreatmenteast.com",
        "https://lauradaydo.com",
        "https://lancewhosedifficult.com",
        "https://dianaavoidthey.com",
        "https://jefferycontrolmodel.com",
        "https://charlestoughrace.com",
        "https://richardquestionbuilding.com",
        "https://jessicayeahcatch.com",
        "https://juliewomanwish.com",
        "https://rebeccapracticeloss.com",
    )

    companion object {
        private const val TAG = "WlfMovie-VoeGnula"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    override suspend fun extract(link: String): Video {
        return solveViaAltchaPow(link)
    }

    private suspend fun solveViaAltchaPow(embedUrl: String): Video {
        Log.i(TAG, "Resolviendo ALTCHA PoW via HTTP...")

        val cookieJar = InMemoryCookieJar()
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)
            .build()

        // 1. Seguir redirects JS manuales
        var currentUrl = embedUrl
        var gateHtml: String? = null
        var gateUrl: String = currentUrl
        for (hop in 0 until 5) {
            val html = httpGetWithClient(client, currentUrl)
            Log.i(TAG, "hop $hop: $currentUrl (${html.length} chars)")

            // Caso A: gate ALTCHA presente
            if (html.contains("altcha-widget") || html.contains("Confirm you")) {
                gateHtml = html
                gateUrl = currentUrl
                break
            }

            // Caso B: ya es la página final del video
            val hasAppJson = Regex(
                """<script[^>]+type="application/json"[^>]*>([^<]{200,})</script>""",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(html)
            val hasGateForm = html.contains("<form") && (html.contains("_token") || html.contains("altcha"))
            if (hasAppJson && !hasGateForm) {
                Log.i(TAG, "Página final sin gate ALTCHA — extrayendo directo")
                return extractFromVideoPage(html)
            }

            // Buscar JS redirect
            val jsRedirect = Regex("""window\.location\.href\s*=\s*'(https://[^']+)'""").find(html)?.groupValues?.get(1)
            if (jsRedirect != null && jsRedirect != currentUrl) {
                currentUrl = jsRedirect
                continue
            }

            // Último intento
            return extractFromVideoPage(html)
        }

        if (gateHtml == null) {
            throw Exception("VOE HD: no se encontró el gate ALTCHA")
        }

        // 2. Extraer del HTML del gate
        val csrfToken = Regex("""name="_token"\s+value="([^"]+)"""").find(gateHtml)?.groupValues?.get(1)
            ?: throw Exception("VOE HD: _token no encontrado en gate")
        val actionUrl = Regex("""<form[^>]+action="([^"]+)"""").find(gateHtml)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
            ?: gateUrl
        val challengeUrl = Regex("""<altcha-widget[^>]+challenge="([^"]+)"""").find(gateHtml)?.groupValues?.get(1)
            ?: throw Exception("VOE HD: challenge URL no encontrada en gate")

        // 3. GET la URL del challenge
        val challengeJsonStr = httpGetWithClient(client, challengeUrl)
        val challenge = JsonParser.parseString(challengeJsonStr).asJsonObject
        val params = challenge.getAsJsonObject("parameters")
        val signature = challenge.get("signature").asString

        val algorithm = params.get("algorithm").asString
        if (algorithm != "PBKDF2/SHA-256") {
            throw Exception("VOE HD: algoritmo ALTCHA no soportado: $algorithm")
        }
        val cost = params.get("cost").asInt
        val keyLength = params.get("keyLength")?.takeIf { !it.isJsonNull }?.asInt ?: 32
        val keyPrefix = params.get("keyPrefix").asString
        val nonce = params.get("nonce").asString
        val salt = params.get("salt").asString

        Log.i(TAG, "ALTCHA params: cost=$cost keyPrefix=$keyPrefix keyLen=$keyLength")

        // 4. Resolver el PoW
        val startMs = System.currentTimeMillis()
        val (counter, derivedKeyHex) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            solvePbkdf2Pow(nonce, salt, keyPrefix, cost, keyLength)
        }
        val tookMs = (System.currentTimeMillis() - startMs).toDouble()
        Log.i(TAG, "PoW resuelto: counter=$counter time=${tookMs.toInt()}ms")

        // 5. Construir el payload
        val payloadObj = JsonObject().apply {
            add("challenge", JsonObject().apply {
                add("parameters", params)
                addProperty("signature", signature)
            })
            add("solution", JsonObject().apply {
                addProperty("counter", counter)
                addProperty("derivedKey", derivedKeyHex)
                addProperty("time", tookMs)
            })
        }
        val payloadJson = payloadObj.toString()
        val payloadB64 = android.util.Base64.encodeToString(payloadJson.toByteArray(), android.util.Base64.NO_WRAP)

        // 6. POST al form action
        val formBody = okhttp3.FormBody.Builder()
            .add("_token", csrfToken)
            .add("altcha", payloadB64)
            .build()

        val finalHtml = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val gateHost = runCatching { gateUrl.toHttpUrlOrNull()?.host }.getOrNull() ?: ""
            val postReq = okhttp3.Request.Builder()
                .url(actionUrl)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Referer", gateUrl)
                .header("Origin", "https://$gateHost")
                .post(formBody)
                .build()
            val postResp = client.newCall(postReq).execute()
            val respBody = postResp.body?.string() ?: ""
            respBody
        }

        if (finalHtml.isEmpty()) {
            throw Exception("VOE HD: respuesta vacía del POST")
        }

        if (finalHtml.contains("altcha-widget") || finalHtml.contains("Confirm you")) {
            throw Exception("VOE HD: el gate rechazó el payload ALTCHA")
        }

        // 7. Extraer la URL del video
        return extractFromVideoPage(finalHtml)
    }

    private fun extractFromVideoPage(html: String): Video {
        // Buscar el script type="application/json"
        val scriptMatch = Regex(
            """<script[^>]+type="application/json"[^>]*>([^<]+)</script>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)

        val rawScriptContent: String = if (scriptMatch != null) {
            scriptMatch.groupValues[1].trim()
        } else {
            // Fallback: buscar scripts inline largos con patrones de VOE
            val inlineScripts = Jsoup.parse(html).select("script")
            var found: String? = null
            for (el in inlineScripts) {
                val text = el.data().trim()
                if (text.length > 200 && (text.contains("@$") || text.contains("^^") || text.contains("~@"))) {
                    found = text
                    break
                }
            }
            found ?: throw Exception("VOE HD: no se encontró script con data encriptada")
        }

        // Unwrap: si el contenido es un JSON array ["..."], extraer el elemento [0]
        val encodedString: String = unwrapVoeScriptContent(rawScriptContent)

        // Desencriptar
        val decryptedContent: JsonObject = try {
            decryptF7(encodedString)
        } catch (e: Exception) {
            try {
                JsonParser.parseString(encodedString).asJsonObject
            } catch (e2: Exception) {
                throw Exception("VOE HD: no se pudo desencriptar ni parsear la data: ${e2.message}")
            }
        }

        val videoUrl = decryptedContent.get("source")?.asString.orEmpty()
        if (videoUrl.isEmpty()) {
            val keys = decryptedContent.keySet().joinToString(", ")
            throw Exception("VOE HD: 'source' vacío en JSON desencriptado. Keys: $keys")
        }

        // Subtítulos
        val subtitles = mutableListOf<Video.Subtitle>()
        val captionsElement = decryptedContent.get("captions")
        if (captionsElement != null && captionsElement.isJsonArray) {
            val captionsArray = captionsElement.asJsonArray
            for (i in 0 until captionsArray.size()) {
                try {
                    val caption = captionsArray.get(i).asJsonObject
                    val label = caption.get("label")?.asString ?: continue
                    val file = caption.get("file")?.asString ?: continue
                    subtitles.add(Video.Subtitle(label = label, file = file))
                } catch (_: Exception) { }
            }
        }

        Log.i(TAG, "Extraído: ${videoUrl.take(80)}...")
        return Video(source = videoUrl, subtitles = subtitles)
    }

    private fun unwrapVoeScriptContent(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed

        // Caso: JSON array ["..."]
        if (trimmed.startsWith("[")) {
            return try {
                val arr = JsonParser.parseString(trimmed).asJsonArray
                if (arr.size() > 0) arr.get(0).asString else trimmed
            } catch (_: Exception) {
                val m = Regex("""^["'\[]\s*["']([^"']+)["']\s*["'\]]?$""").find(trimmed)
                m?.groupValues?.getOrNull(1) ?: trimmed
            }
        }

        // Caso: JSON string "..."
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2) {
            return try {
                JsonParser.parseString(trimmed).asString
            } catch (_: Exception) {
                trimmed.substring(1, trimmed.length - 1)
            }
        }

        // Caso: string crudo
        return trimmed
    }

    /**
     * Desencriptación VOE (rot13 + replace patterns + remove underscores + base64 + charShift + reverse + base64)
     */
    private fun decryptF7(p8: String): JsonObject {
        val vF = rot13(p8)
        val vF2 = replacePatterns(vF)
        val vF3 = vF2.replace("_", "")
        val vF4 = String(android.util.Base64.decode(vF3, android.util.Base64.DEFAULT), Charsets.UTF_8)
        val vF5 = charShift(vF4, 3)
        val vF6 = vF5.reversed()
        val vAtob = String(android.util.Base64.decode(vF6, android.util.Base64.DEFAULT), Charsets.UTF_8)
        return JsonParser.parseString(vAtob).asJsonObject
    }

    private fun rot13(input: String): String = input.map { c ->
        when (c) {
            in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
            in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
            else -> c
        }
    }.joinToString("")

    private fun replacePatterns(input: String): String {
        val patterns = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
        return patterns.fold(input) { result, pattern ->
            result.replace(Regex(Regex.escape(pattern)), "_")
        }
    }

    private fun charShift(input: String, shift: Int) =
        input.map { (it.code - shift).toChar() }.joinToString("")

    /**
     * Resuelve el PoW PBKDF2/SHA-256 de ALTCHA.
     */
    private fun solvePbkdf2Pow(
        nonceHex: String,
        saltHex: String,
        keyPrefixHex: String,
        cost: Int,
        keyLength: Int
    ): Pair<Int, String> {
        val nonceBytes = hexToBytes(nonceHex)
        val saltBytes = hexToBytes(saltHex)
        val keyPrefixBytes = hexToBytes(keyPrefixHex)
        val prefixLen = keyPrefixBytes.size

        var counter = 0
        val maxIter = 10_000_000
        while (counter < maxIter) {
            val password = ByteArray(nonceBytes.size + 4)
            System.arraycopy(nonceBytes, 0, password, 0, nonceBytes.size)
            password[nonceBytes.size]     = (counter ushr 24).toByte()
            password[nonceBytes.size + 1] = (counter ushr 16).toByte()
            password[nonceBytes.size + 2] = (counter ushr 8).toByte()
            password[nonceBytes.size + 3] = counter.toByte()

            val derivedKey = pbkdf2HmacSha256(password, saltBytes, cost, keyLength)
            if (derivedKey.size >= prefixLen && derivedKey.copyOfRange(0, prefixLen).contentEquals(keyPrefixBytes)) {
                return Pair(counter, bytesToHex(derivedKey))
            }
            counter++
        }
        throw Exception("VOE HD: PoW no resuelto después de $maxIter iteraciones")
    }

    private fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val blocks = (dkLen + hLen - 1) / hLen
        val result = ByteArray(dkLen)
        for (blockIndex in 1..blocks) {
            val saltWithBlock = ByteArray(salt.size + 4)
            System.arraycopy(salt, 0, saltWithBlock, 0, salt.size)
            saltWithBlock[salt.size]     = (blockIndex ushr 24).toByte()
            saltWithBlock[salt.size + 1] = (blockIndex ushr 16).toByte()
            saltWithBlock[salt.size + 2] = (blockIndex ushr 8).toByte()
            saltWithBlock[salt.size + 3] = blockIndex.toByte()

            var u = mac.doFinal(saltWithBlock)
            val t = u.copyOf()
            for (j in 1 until iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) {
                    t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
            }
            val offset = (blockIndex - 1) * hLen
            val len = minOf(hLen, dkLen - offset)
            System.arraycopy(t, 0, result, offset, len)
        }
        return result
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.lowercase().replace(Regex("[^0-9a-f]"), "")
        val padded = if (clean.length % 2 == 0) clean else "0$clean"
        val out = ByteArray(padded.length / 2)
        for (i in padded.indices step 2) {
            out[i / 2] = ((Character.digit(padded[i], 16) shl 4) or Character.digit(padded[i + 1], 16)).toByte()
        }
        return out
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private suspend fun httpGetWithClient(client: okhttp3.OkHttpClient, url: String): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: throw Exception("VOE HD: respuesta vacía de $url")
            if (!resp.isSuccessful) {
                throw Exception("VOE HD: HTTP ${resp.code} de $url")
            }
            body
        }
    }
}

/**
 * CookieJar simple que mantiene cookies en memoria por host.
 */
private class InMemoryCookieJar : okhttp3.CookieJar {
    private val cookiesByHost = mutableMapOf<String, MutableList<okhttp3.Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        val now = System.currentTimeMillis()
        val existing = cookiesByHost[url.host].orEmpty().toMutableList()
        for (newCookie in cookies) {
            existing.removeAll { it.name == newCookie.name && it.path == newCookie.path }
            if (newCookie.expiresAt > now) {
                existing.add(newCookie)
            }
        }
        cookiesByHost[url.host] = existing
    }

    @Synchronized
    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
        val now = System.currentTimeMillis()
        return cookiesByHost[url.host].orEmpty().filter { it.expiresAt > now }
    }
}
