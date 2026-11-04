package com.mew.wlfmovie.extractors

import android.util.Log
import com.mew.wlfmovie.models.Video

/**
 * Extractor para YetiPelis / moviemaster.lat
 *
 * YetiPelis devuelve URLs m3u8 DIRECTAS (no iframes). Este extractor simplemente
 * las pasa a ExoPlayer con los headers correctos (Referer).
 *
 * IMPORTANTE: los m3u8 de moviemaster.lat pueden tener segmentos con extensiones
 * raras (.woff2 = MPEG-TS disfrazado, .ts = MPEG-TS directo). ExoPlayer los reproduce
 * sin problema porque usa el Content-Type del response (video/MP2T) y/o los primeros
 * bytes (sync byte 0x47) para detectar el formato, NO la extensión del archivo.
 *
 * VERIFICADO con ffprobe:
 *  - Segmentos .woff2 → H.264 720p + AAC 44.1kHz, 1.4 Mbps (MPEG-TS válido)
 *  - ExoPlayer reproducirá tanto .ts como .woff2 como MPEG-TS.
 *
 * Headers:
 *  - Referer: https://api-encrypted.moviemaster.lat/  (obligatorio, sin él 403)
 *  - User-Agent: Chrome desktop (igual que el resto del scraper)
 */
class YetiExtractor : Extractor() {
    override val name = "YetiPelis"
    override val mainUrl = "https://api-encrypted.moviemaster.lat"

    /**
     * Alias URLs que también mapean a este extractor.
     * El YetiPelisProvider devuelve dominios como:
     *  - "moviemaster.lat" (cuando es HLS directo)
     *  - "fastream.to" (cuando el CDN externo resuelve directamente al m3u8 .ts)
     *  - "vidsrc.xyz", "vidlink.pro", "player.videasy.net" (cuando son iframes externos)
     *
     * Para iframes externos, el YetiPelisProvider no los marca como "YetiPelis"
     * en el providerName — solo HLS directo de moviemaster.lat y fastream.to.
     * Los iframes externos se manejan con los extractores VidsrcNet / VidsrcTo / etc. ya existentes.
     *
     * IMPORTANTE: fastream.to es un CDN externo que devuelve m3u8 con segmentos .ts directos.
     * El YetiExtractor pasa la URL tal cual con header Referer = moviemaster.lat (a veces no
     * hace falta, pero no duele). ExoPlayer reproduce los .ts sin problema.
     */
    override val aliasUrls = listOf(
        "https://api-encrypted.moviemaster.lat",
        "https://moviemaster.lat",
        "https://s40.fastream.to",
        "https://fastream.to",
    )

    /**
     * El extractor principal. Recibe la URL m3u8 y devuelve un Video
     * con source = la URL m3u8 + headers (Referer).
     *
     * Los headers se aplican en ExoPlayer via DefaultHttpDataSource.Factory
     * .setDefaultRequestProperties(headers) — ver PlayerScreen.kt del test app.
     */
    override suspend fun extract(link: String): Video {
        Log.i("YetiExtractor", "Devolviendo m3u8 directo con headers")
        Log.i("YetiExtractor", "URL: ${link.take(120)}...")

        // Limpiar la URL: a veces viene con ?r=1 o parámetros extras
        val cleanUrl = link.substringBefore("&r=1").trim()

        // Headers necesarios para que ExoPlayer reproduzca el m3u8 y sus segmentos
        // Referer: obligatorio, sin él el m3u8 devuelve 403
        // User-Agent: Chrome desktop (igual que el scraper)
        val headers = mapOf(
            "Referer" to MM_BASE + "/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        )

        // MIME type correcto para HLS — ExoPlayer necesita "application/vnd.apple.mpegurl"
        // para usar HlsMediaSource. Si pasamos null, ExoPlayer auto-detecta desde .m3u8.
        // Si pasamos "hls" (como antes), ExoPlayer NO lo reconoce y falla.
        return Video(
            source = cleanUrl,
            headers = headers,
            type = "application/vnd.apple.mpegurl",
        )
    }

    companion object {
        const val MM_BASE = "https://api-encrypted.moviemaster.lat"
    }
}
