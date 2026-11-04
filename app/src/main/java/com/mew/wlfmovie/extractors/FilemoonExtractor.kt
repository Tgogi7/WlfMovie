package com.mew.wlfmovie.extractors

import android.util.Base64
import android.util.Log
import com.mew.wlfmovie.models.Video
import com.mew.wlfmovie.utils.DnsResolver
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

open class FilemoonExtractor : Extractor() {

    override val name = "Filemoon"
    override val mainUrl = "https://filemoon.site"
    override val aliasUrls = listOf("https://bf0skv.org","https://bysejikuar.com","https://moflix-stream.link","https://bysezoxexe.com","https://bysebuho.com","https://filemoon.sx","https://bysekoze.com","https://bysesayeveum.com", "https://bysezejataos.com","https://byselapuix.com")

    private var deviceId = UUID.randomUUID().toString().replace("-", "")

    override suspend fun extract(link: String): Video {
        val service = Service.build(mainUrl)
        // Regex to match /e/ or /d/ and ID
        val matcher = Regex("""/(e|d)/([a-zA-Z0-9]+)""").find(link)
            ?: throw Exception("Could not extract video ID or type")

        val linkType = matcher.groupValues[1]
        val videoId = matcher.groupValues[2]

        val currentDomain = Regex("""(https?://[^/]+)""").find(link)?.groupValues?.get(1)
            ?: throw Exception("Could not extract Base URL")

        Log.i("StreamFlixES", "[Filemoon] Extraction started for: $link (Domain: $currentDomain, Type: $linkType)")

        // 1. Details
        val apiPrefix = if (linkType == "e") "embed/" else ""
        val detailsUrl = "$currentDomain/api/videos/$videoId/${apiPrefix}details"
        val settingsUrl = "$currentDomain/api/videos/$videoId/${apiPrefix}settings"

        val headersInitial = mapOf(
            "Referer" to link,
            "Origin" to currentDomain,
            "User-Agent" to Service.DEFAULT_USER_AGENT,
            "Accept" to "application/json, text/plain, */*"
        )

        Log.i("StreamFlixES", "[Filemoon] Details Request: $detailsUrl")
        val details = service.getDetails(detailsUrl, headersInitial)
        val embedFrameUrl = details.embed_frame_url ?: throw Exception("embed_frame_url not found")

        val playbackDomain = Regex("""(https?://[^/]+)""").find(embedFrameUrl)?.groupValues?.get(1)
            ?: currentDomain
        Log.i("StreamFlixES", "[Filemoon] Playback Domain detected: $playbackDomain")

        val settings = try {
            service.getSettings(settingsUrl, headersInitial)
        } catch (e: Exception) {
            Log.w("StreamFlixES", "[Filemoon] Settings fetch failed: ${e.message}")
            null
        }
        val captchaRequired = settings?.captcha_required == true
        Log.i("StreamFlixES", "[Filemoon] captcha_required: $captchaRequired")

        // 2. Challenge
        val challengeUrl = "$playbackDomain/api/videos/access/challenge"
        Log.i("StreamFlixES", "[Filemoon] Challenge Request: $challengeUrl")
        val challenge = service.getChallenge(challengeUrl, mapOf(
            "Referer" to embedFrameUrl,
            "Origin" to playbackDomain,
            "User-Agent" to Service.DEFAULT_USER_AGENT,
            "Content-Length" to "0"
        ))

        val challengeId = challenge.challenge_id ?: throw Exception("No challenge_id")
        val nonce = challenge.nonce ?: throw Exception("No nonce")

        // Always generate a new viewerId
        var viewerId = UUID.randomUUID().toString().replace("-", "")
        Log.i("StreamFlixES", "[Filemoon] Challenge Data: ID=$challengeId, Viewer=$viewerId")

        // 3. Attestation
        val attestation = generateAttestation(nonce)
        val attestUrl = "$playbackDomain/api/videos/access/attest"
        Log.i("StreamFlixES", "[Filemoon] Attest Request: $attestUrl")

        val attestPayload: Map<String, kotlin.Any> = mapOf(
            "viewer_id" to viewerId,
            "device_id" to deviceId,
            "challenge_id" to challengeId,
            "nonce" to nonce,
            "signature" to attestation.signature,
            "public_key" to attestation.publicKey,
            "client" to mapOf(
                "user_agent" to Service.DEFAULT_USER_AGENT,
                "architecture" to "x86",
                "bitness" to "64",
                "platform" to "Windows",
                "platform_version" to "10.0.0",
                "pixel_ratio" to 1.0,
                "screen_width" to 1920,
                "screen_height" to 1080,
                "languages" to listOf("en-US")
            ),
            "storage" to mapOf(
                "cookie" to viewerId,
                "local_storage" to viewerId,
                "indexed_db" to "$viewerId:$deviceId",
                "cache_storage" to "$viewerId:$deviceId"
            ),
            "attributes" to mapOf("entropy" to "high")
        )

        val attestResponse = service.attest(attestUrl, attestPayload, mapOf(
            "Referer" to embedFrameUrl,
            "Origin" to playbackDomain,
            "User-Agent" to Service.DEFAULT_USER_AGENT,
            "Content-Type" to "application/json"
        ))

        val token = attestResponse.token ?: throw Exception("No attest token")
        viewerId = attestResponse.viewer_id ?: viewerId
        deviceId = attestResponse.device_id ?: deviceId
        val confidence = attestResponse.confidence ?: throw Exception("No confidence in response")

        Log.i("StreamFlixES", "[Filemoon] Attest Token obtained (Confidence: $confidence)")

        val fingerprint: Map<String, kotlin.Any> = mapOf(
            "token" to token,
            "viewer_id" to viewerId,
            "device_id" to deviceId,
            "confidence" to confidence
        )

        val playbackHeaders = mutableMapOf(
            "Referer" to embedFrameUrl,
            "Origin" to playbackDomain,
            "X-Embed-Parent" to link,
            "User-Agent" to Service.DEFAULT_USER_AGENT,
            "Content-Type" to "application/json"
        )

        // 4. Captcha / PoW (if required by video settings)
        if (captchaRequired) {
            Log.i("StreamFlixES", "[Filemoon] Solving Proof of Work captcha...")
            val captchaReqUrl = "$playbackDomain/api/videos/$videoId/embed/captcha"
            val capRes = service.getCaptcha(captchaReqUrl, mapOf("fingerprint" to fingerprint), playbackHeaders)
            val powNonce = capRes.pow_nonce ?: throw Exception("No pow_nonce in captcha response")
            val powDifficulty = capRes.pow_difficulty ?: 16
            val powToken = capRes.pow_token ?: throw Exception("No pow_token in captcha response")

            val solution = solvePow(powNonce, powDifficulty)
            Log.i("StreamFlixES", "[Filemoon] PoW solved: $solution. Verifying...")

            val verifyUrl = "$playbackDomain/api/videos/$videoId/embed/captcha/verify"
            val verifyRes = service.verifyCaptcha(
                verifyUrl,
                mapOf(
                    "pow_token" to powToken,
                    "solution" to solution,
                    "fingerprint" to fingerprint
                ),
                playbackHeaders
            )
            val captchaToken = verifyRes.token ?: throw Exception("Captcha token missing in verify response")
            Log.i("StreamFlixES", "[Filemoon] Captcha Token verified successfully!")
            playbackHeaders["X-Captcha-Token"] = captchaToken
        }

        // 5. Playback
        val playbackUrl = "$playbackDomain/api/videos/$videoId/embed/playback"
        Log.i("StreamFlixES", "[Filemoon] Playback Request: $playbackUrl")
        val playbackPayload: Map<String, kotlin.Any> = mapOf("fingerprint" to fingerprint)

        val playbackResponse = service.getPlayback(playbackUrl, playbackPayload, playbackHeaders)
        val playbackData = playbackResponse.playback ?: throw Exception("No playback data")

        // Decrypt
        Log.i("StreamFlixES", "[Filemoon] Decrypting data...")
        val decryptedJson = decryptPlayback(playbackData)

        val jsonObject = JSONObject(decryptedJson)
        val sources = jsonObject.optJSONArray("sources")
            ?: throw Exception("No sources found")
        val sourceUrl = sources.getJSONObject(0).getString("url")

        Log.i("StreamFlixES", "[Filemoon] SOURCE FOUND: $sourceUrl")

        return Video(
            source = sourceUrl,
            headers = mapOf(
                "Referer" to embedFrameUrl,
                "User-Agent" to Service.DEFAULT_USER_AGENT,
                "Origin" to playbackDomain
            )
        )
    }

    private fun generateAttestation(nonce: String): Attestation {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = keyPairGenerator.generateKeyPair()
        val privateKey = keyPair.private
        val publicKey = keyPair.public as ECPublicKey

        // JWK coordinates
        val x = Base64.encodeToString(publicKey.w.affineX.toByteArray().stripLeadingZero(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val y = Base64.encodeToString(publicKey.w.affineY.toByteArray().stripLeadingZero(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        val signatureObj = Signature.getInstance("SHA256withECDSA")
        signatureObj.initSign(privateKey)
        signatureObj.update(nonce.toByteArray())
        val derSignature = signatureObj.sign()

        // Convert DER to Raw (r + s)
        val rawSignature = derToRawSignature(derSignature)
        val encodedSignature = Base64.encodeToString(rawSignature, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        val jwk = mapOf(
            "crv" to "P-256",
            "ext" to true,
            "key_ops" to listOf("verify"),
            "kty" to "EC",
            "x" to x,
            "y" to y
        )

        return Attestation(encodedSignature, jwk)
    }

    private fun derToRawSignature(der: ByteArray): ByteArray {
        // Simple DER parser for ECDSA signature (SEQUENCE { r INTEGER, s INTEGER })
        var offset = 2
        val rLen = der[offset + 1].toInt()
        val r = der.copyOfRange(offset + 2, offset + 2 + rLen).stripLeadingZero()
        offset += 2 + rLen
        val sLen = der[offset + 1].toInt()
        val s = der.copyOfRange(offset + 2, offset + 2 + sLen).stripLeadingZero()

        val raw = ByteArray(64)
        System.arraycopy(r, 0, raw, 32 - r.size, r.size)
        System.arraycopy(s, 0, raw, 64 - s.size, s.size)
        return raw
    }

    private fun ByteArray.stripLeadingZero(): ByteArray {
        return if (this.isNotEmpty() && this[0] == 0.toByte()) {
            this.copyOfRange(1, this.size)
        } else {
            this
        }
    }

    data class Attestation(val signature: String, val publicKey: Map<String, kotlin.Any>)

    private fun decryptPlayback(data: PlaybackData): String {
        val iv = Base64.decode(data.iv, Base64.URL_SAFE)
        val payload = Base64.decode(data.payload, Base64.URL_SAFE)

        val keyParts = data.key_parts
        val v = data.version?.toIntOrNull()
        val (p1, p2) = if (v != null && v in 1..20 && keyParts.size >= 30) {
            val idx1 = v - 1
            val idx2 = (31 - v) - 1
            Pair(
                Base64.decode(keyParts[idx1], Base64.URL_SAFE),
                Base64.decode(keyParts[idx2], Base64.URL_SAFE)
            )
        } else if (keyParts.size >= 2) {
            Pair(
                Base64.decode(keyParts[0], Base64.URL_SAFE),
                Base64.decode(keyParts[1], Base64.URL_SAFE)
            )
        } else {
            throw Exception("Not enough key parts for decryption")
        }

        val key = ByteArray(p1.size + p2.size)
        System.arraycopy(p1, 0, key, 0, p1.size)
        System.arraycopy(p2, 0, key, p1.size, p2.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        val secretKey = SecretKeySpec(key, "AES")

        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val decryptedBytes = cipher.doFinal(payload)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    open class Any(hostUrl: String) : FilemoonExtractor() {
        override val mainUrl = hostUrl
    }

    private interface Service {
        @GET
        suspend fun getDetails(@Url url: String, @HeaderMap headers: Map<String, String>): DetailsResponse

        @GET
        suspend fun getSettings(@Url url: String, @HeaderMap headers: Map<String, String>): SettingsResponse

        @POST
        suspend fun getChallenge(@Url url: String, @HeaderMap headers: Map<String, String>): ChallengeResponse

        @JvmSuppressWildcards
        @POST
        suspend fun attest(@Url url: String, @Body body: Map<String, kotlin.Any>, @HeaderMap headers: Map<String, String>): AttestResponse

        @JvmSuppressWildcards
        @POST
        suspend fun getCaptcha(@Url url: String, @Body body: Map<String, kotlin.Any>, @HeaderMap headers: Map<String, String>): CaptchaResponse

        @JvmSuppressWildcards
        @POST
        suspend fun verifyCaptcha(@Url url: String, @Body body: Map<String, kotlin.Any>, @HeaderMap headers: Map<String, String>): VerifyCaptchaResponse

        @JvmSuppressWildcards
        @POST
        suspend fun getPlayback(@Url url: String, @Body body: Map<String, kotlin.Any>, @HeaderMap headers: Map<String, String>): PlaybackResponse

        companion object {
            const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"

            fun build(baseUrl: String): Service {
                val cookieJar = object : okhttp3.CookieJar {
                    private val cookieStore = HashMap<String, List<okhttp3.Cookie>>()
                    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                        cookieStore[url.host] = cookies
                    }
                    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                        return cookieStore[url.host] ?: ArrayList()
                    }
                }

                val client = OkHttpClient.Builder()
                    .cookieJar(cookieJar)
                    .dns(DnsResolver.doh).build()

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(Service::class.java)
            }
        }
    }

    data class DetailsResponse(val embed_frame_url: String?)
    data class SettingsResponse(val captcha_required: Boolean? = false, val embed_domain_allowed: String? = null)
    data class ChallengeResponse(val challenge_id: String?, val nonce: String?, val viewer_hint: String?)
    data class AttestResponse(val token: String?, val viewer_id: String?, val device_id: String?, val confidence: Double?)
    data class CaptchaResponse(val pow_nonce: String?, val pow_difficulty: Int?, val pow_token: String?)
    data class VerifyCaptchaResponse(val status: String?, val token: String?)
    data class PlaybackResponse(val playback: PlaybackData?)
    data class PlaybackData(
        val iv: String,
        val payload: String,
        val key_parts: List<String> = emptyList(),
        val version: String? = null
    )

    companion object {
        private const val BE = 512
        private const val LT = BE - 1
        private const val DR = 2
        private const val LR = -1640531535 // 2654435761.toInt()
        private const val HR = -2048144777 // 2246822519L.toInt()

        private fun rotl(t: Int, e: Int): Int = (t shl e) or (t ushr (32 - e))

        private fun ye(t: IntArray) {
            t[0] = t[0] + t[1]
            t[3] = rotl(t[3] xor t[0], 16)
            t[2] = t[2] + t[3]
            t[1] = rotl(t[1] xor t[2], 12)
            t[0] = t[0] + t[1]
            t[3] = rotl(t[3] xor t[0], 8)
            t[2] = t[2] + t[3]
            t[1] = rotl(t[1] xor t[2], 7)
        }

        private fun gr(t: ByteArray): IntArray {
            val e = intArrayOf(1779033703, -1150833019 /* 3144134277.toInt() */, 1013904242, -1521486534 /* 2773480762.toInt() */)
            for (i in t.indices) {
                val b = t[i].toInt() and 0xFF
                e[0] = e[0] + b
                e[0] = rotl(e[0], 7)
                ye(e)
            }
            for (i in 0 until 8) ye(e)
            val r = IntArray(BE)
            for (i in 0 until BE) {
                ye(e)
                r[i] = e[0] xor e[2]
            }
            for (i in 0 until DR) {
                for (s in 0 until BE) {
                    val a = r[s] and LT
                    var c = r[s] + r[a]
                    c = rotl(c, 13)
                    c = c xor (r[(s + 1) and LT] * LR)
                    r[s] = c
                    e[0] = e[0] xor c
                    ye(e)
                }
            }
            val n = IntArray(8)
            val o = BE / 8
            for (i in 0 until 8) {
                ye(e)
                var s = e[0]
                val a = i * o
                for (c in 0 until o) {
                    val d = r[a + c]
                    s = s + d
                    s = rotl(s, 5)
                    s = s xor (d * HR)
                }
                n[i] = s xor e[2]
            }
            return n
        }

        private fun wr(t: IntArray): Int {
            var e = 0
            for (i in t.indices) {
                val n = t[i]
                if (n == 0) {
                    e += 32
                    continue
                }
                return e + Integer.numberOfLeadingZeros(n)
            }
            return e
        }

        fun solvePow(nonce: String, difficulty: Int): String {
            if (difficulty <= 0) return "0"
            val prefix = "$nonce:"
            var s = 0
            while (true) {
                val input = (prefix + s).toByteArray(Charsets.ISO_8859_1)
                val d = gr(input)
                if (wr(d) >= difficulty) {
                    return s.toString()
                }
                s++
            }
        }
    }
}
