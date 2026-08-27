package com.mew.wlfmovie.remoteplay

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * WLFMOVIE V6.4: Generador de playlist m3u8 SINTETICA para compartir una
 * pelicula DESCARGADA al PC por la red local (sin internet).
 *
 * El archivo descargado por HLS es un `.ts` — concatenacion de segmentos:
 * - MPEG-TS clasico (paquetes de 188 bytes empezando con 0x47), o
 * - fMP4/CMAF (init.mp4 + seg_N.m4s concatenados → cajas MP4: ftyp/moov/moof/mdat...)
 *
 * Un navegador de PC NO puede reproducir un `.ts` crudo en un <video>. Pero el
 * reproductor web del PC ya carga hls.js — asi que en vez de convertir el
 * archivo (lento, ocupa espacio extra), generamos un m3u8 que "envuelve" el
 * archivo en segmentos VIRTUALES por rango de bytes (#EXT-X-BYTERANGE):
 *
 * - hls.js pide /stream → recibe este m3u8 → pide /localfile con header
 *   `Range: bytes=X-Y` → el server lee ESOS bytes del disco y nada mas.
 * - Cada segmento virtual se demuxa en el navegador (los .ts llevan PTS/DTS
 *   y los fMP4 llevan tfdt/trun → hls.js conoce los tiempos reales).
 * - Seek, pausa y play funcionan igual que en HLS online.
 *
 * La duracion real (para la barra del PC) se toma del player local de Android
 * (ExoPlayer ya la resolvio al cargar el archivo) y se reparte uniformemente
 * en los #EXTINF; hls.js la corrige solo al demuxar.
 */
object LocalMediaPlaylist {

    private const val TAG = "WlfMovie-LocalPlaylist"

    /** Tamanio del primer segmento TS (chico para que el arranque sea rapido). */
    private const val TS_FIRST_SEGMENT_BYTES = 512L * 1024

    /** Tamanio de los segmentos TS siguientes. */
    private const val TS_SEGMENT_BYTES = 4L * 1024 * 1024

    /** Bytes por paquete MPEG-TS (alinear offsets para que cada segmento
     *  empiece exactamente en un sync byte 0x47). */
    private const val TS_PACKET = 188L

    /** EXTINF (segundos) por segmento si no se conoce la duracion total. */
    private const val DEFAULT_SEGMENT_DURATION_SEC = 6.0

    data class Result(
        val content: String,
        val isFmp4: Boolean,
        val segmentCount: Int,
    )

    /**
     * Construye el m3u8 sintetico.
     *
     * @param file el archivo local (.ts descargado)
     * @param segmentUrl URL absoluta del endpoint que sirve los bytes
     *                   (ej: "http://192.168.1.5:8080/localfile")
     * @param knownDurationMs duracion conocida del video (del player local),
     *                        0 si se desconoce
     * @return el m3u8 listo para servir, o null si el archivo es ilegible
     */
    fun build(file: File, segmentUrl: String, knownDurationMs: Long): Result? {
        if (!file.exists() || file.length() <= 0L) {
            Log.e(TAG, "build: archivo inexistente o vacio: ${file.path}")
            return null
        }

        val isFmp4 = sniffIsFmp4(file)

        return if (isFmp4) {
            buildFmp4(file, segmentUrl, knownDurationMs)
        } else {
            buildTs(file, segmentUrl, knownDurationMs)
        }
    }

    // ================================================================
    // Deteccion de formato
    // ================================================================

    /**
     * true si el archivo empieza con cajas MP4 (fMP4/CMAF: ftyp/moov/...),
     * false si es MPEG-TS clasico (sync byte 0x47 cada 188 bytes).
     */
    private fun sniffIsFmp4(file: File): Boolean {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(8)
                var n = 0
                while (n < header.size) {
                    val r = raf.read(header, n, header.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n < 8) return false

                val boxType = String(header, 4, 4, Charsets.US_ASCII)
                val fmp4 = boxType == "ftyp" || boxType == "styp" ||
                    boxType == "moov" || boxType == "sidx" ||
                    boxType == "free" || boxType == "skip"
                if (fmp4) {
                    Log.i(TAG, "sniffIsFmp4: fMP4 detectado (caja inicial '$boxType')")
                }
                fmp4
            }
        } catch (e: Exception) {
            Log.e(TAG, "sniffIsFmp4: error, asumiendo MPEG-TS", e)
            false
        }
    }

    // ================================================================
    // fMP4: walk de cajas top-level
    // ================================================================

    /**
     * Recorre las cajas MP4 top-level del archivo y devuelve los offsets
     * donde empieza cada caja `moof` (inicio de cada fragmento fMP4).
     * Cada fragmento = moof + su mdat (+ lo que siga hasta el proximo moof).
     *
     * Si el walk encuentra una caja invalida, devuelve lo acumulado hasta
     * ese punto (parcial pero util).
     */
    private fun fmp4FragmentStarts(file: File): List<Long> {
        val starts = ArrayList<Long>()
        try {
            RandomAccessFile(file, "r").use { raf ->
                val fileLen = raf.length()
                var pos = 0L
                val header = ByteArray(8)

                while (pos + 8 <= fileLen) {
                    raf.seek(pos)
                    var n = 0
                    while (n < 8) {
                        val r = raf.read(header, n, 8 - n)
                        if (r < 0) return starts
                        n += r
                    }

                    var size = be32(header, 0)
                    val type = String(header, 4, 4, Charsets.US_ASCII)

                    if (size == 1L) {
                        // Caja de 64 bits: largsize en los siguientes 8 bytes
                        val ext = ByteArray(8)
                        var m = 0
                        while (m < 8) {
                            val r = raf.read(ext, m, 8 - m)
                            if (r < 0) return starts
                            m += r
                        }
                        size = be64(ext, 0)
                    }

                    if (size < 8 || pos + size > fileLen) {
                        Log.w(TAG, "fmp4FragmentStarts: caja invalida en $pos (size=$size, type='$type'), truncando walk")
                        return starts
                    }

                    if (type == "moof") {
                        starts.add(pos)
                    }
                    pos += size
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fmp4FragmentStarts: error", e)
        }
        return starts
    }

    private fun be32(b: ByteArray, off: Int): Long {
        return ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)
    }

    private fun be64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        }
        return v
    }

    // ================================================================
    // Builders
    // ================================================================

    private fun buildFmp4(file: File, segmentUrl: String, knownDurationMs: Long): Result? {
        val fragStarts = fmp4FragmentStarts(file)
        if (fragStarts.isEmpty()) {
            Log.e(TAG, "buildFmp4: no se encontraron fragmentos (moof) — no se puede servir")
            return null
        }

        val fileLen = file.length()

        // Segmento init: desde 0 hasta el primer moof (ftyp + moov + sidx...)
        val initLen = fragStarts[0]

        // Cada segmento: (offset, length) — desde cada moof hasta el proximo
        // moof; el ultimo llega hasta EOF.
        val segs = ArrayList<Pair<Long, Long>>(fragStarts.size)
        for (i in fragStarts.indices) {
            val start = fragStarts[i]
            val endExclusive = if (i + 1 < fragStarts.size) fragStarts[i + 1] else fileLen
            segs.add(start to (endExclusive - start))
        }

        val n = segs.size
        val durationSec = if (knownDurationMs > 0) {
            (knownDurationMs / 1000.0) / n
        } else {
            DEFAULT_SEGMENT_DURATION_SEC
        }

        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:6\n")
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        sb.append("#EXT-X-TARGETDURATION:").append(targetDuration(durationSec)).append('\n')
        if (initLen > 0) {
            sb.append("#EXT-X-MAP:URI=\"").append(segmentUrl)
                .append("\",BYTERANGE=\"").append(initLen).append("@0\"\n")
        }
        for ((offset, length) in segs) {
            sb.append("#EXTINF:").append(String.format(Locale.US, "%.3f", durationSec)).append(",\n")
            sb.append("#EXT-X-BYTERANGE:").append(length).append('@').append(offset).append('\n')
            sb.append(segmentUrl).append('\n')
        }
        sb.append("#EXT-X-ENDLIST\n")

        Log.i(TAG, "buildFmp4: $n fragmentos, init=${initLen}B, archivo=${fileLen}B")
        return Result(sb.toString(), isFmp4 = true, segmentCount = n)
    }

    private fun buildTs(file: File, segmentUrl: String, knownDurationMs: Long): Result? {
        val fileLen = file.length()

        // Segmentos alineados a paquetes de 188 bytes: el primero chico para
        // que el video arranque rapido en el PC, el resto de 4MB.
        val firstLen = alignDown(TS_FIRST_SEGMENT_BYTES)
        val segLen = alignDown(TS_SEGMENT_BYTES)

        val segs = ArrayList<Pair<Long, Long>>()
        var start = 0L
        while (start < fileLen) {
            val size = if (segs.isEmpty()) firstLen else segLen
            val endExclusive = minOf(start + size, fileLen)
            segs.add(start to (endExclusive - start))
            start = endExclusive
        }

        val n = segs.size
        val durationSec = if (knownDurationMs > 0) {
            (knownDurationMs / 1000.0) / n
        } else {
            DEFAULT_SEGMENT_DURATION_SEC
        }

        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:6\n")
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        sb.append("#EXT-X-TARGETDURATION:").append(targetDuration(durationSec)).append('\n')
        for ((offset, length) in segs) {
            sb.append("#EXTINF:").append(String.format(Locale.US, "%.3f", durationSec)).append(",\n")
            sb.append("#EXT-X-BYTERANGE:").append(length).append('@').append(offset).append('\n')
            sb.append(segmentUrl).append('\n')
        }
        sb.append("#EXT-X-ENDLIST\n")

        Log.i(TAG, "buildTs: $n segmentos TS alineados a 188B, archivo=${fileLen}B")
        return Result(sb.toString(), isFmp4 = false, segmentCount = n)
    }

    private fun alignDown(bytes: Long): Long = (bytes / TS_PACKET) * TS_PACKET

    private fun targetDuration(durationSec: Double): Int {
        return maxOf(1, kotlin.math.ceil(durationSec).toInt())
    }
}
