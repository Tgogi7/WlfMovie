package com.mew.wlfmovie.remoteplay

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.ceil

/**
 * WLFMOVIE V6.4.2 — Generador de playlist m3u8 SINTETICA para compartir una
 * pelicula DESCARGADA al PC por la red local (sin internet).
 *
 * El archivo descargado por HLS es un `.ts` — concatenacion de segmentos:
 * - MPEG-TS clasico (paquetes de 188 bytes empezando con 0x47), o
 * - fMP4/CMAF (init.mp4 + seg_N.m4s concatenados → cajas MP4: ftyp/moov/moof/mdat...)
 *
 * Un navegador de PC NO puede reproducir un `.ts` crudo en un <video>, pero el
 * reproductor web del PC ya carga hls.js — asi que generamos un m3u8 que
 * "envuelve" el archivo local en segmentos virtuales.
 *
 * ---------------------------------------------------------------------------
 * HISTORIA DE BUGS DE ESTE MODULO:
 *
 * V6.4.0 (fallaba) — segmentos por #EXT-X-BYTERANGE sobre /localfile:
 *   solo el PRIMER trozo contiene PAT/PMT (el "mapa" de pistas del MPEG-TS).
 *   hls.js reinicia su demuxer TS en varios escenarios, pierde el mapeo
 *   PID→pista y muere con fragParsingError ("Found no media in fragment N").
 *
 * V6.4.1 (fallaba) — segmentos autocontenidos con PAT/PMT SINTETICO:
 *   el aproach era correcto, pero si el parseo de PAT/PMT fallaba (archivo
 *   con cabeza no estandar: init CMAF mezclado, stride 192/204, basura
 *   inicial) caia en SILENCIO al fallback byterange de la V6.4.0. Y peor:
 *   las duraciones EXTINF del fallback eran 6.0s planos cuando el player
 *   Android no resuelve la duracion de un .ts crudo (siempre: progressive TS
 *   = duracion desconocida) → el TOTAL declarado del playlist quedaba muy
 *   por DEBAJO del contenido real → hls.js disparaba endOfStream() a mitad
 *   de reproduccion ("_streamEnded" ve el buffer pasado del edge declarado)
 *   → el append siguiente explota: "bufferAppendError / audio SourceBuffer
 *   error. MediaSource readyState: ended" → muerte a los 2-3s. EXACTAMENTE
 *   el error reportado en PC con la V6.4.1.
 *
 * V6.4.2 (esta version):
 *   1. DETECCION DE SYNC robusta: se busca la grilla real de paquetes
 *      (stride 188/192/204 + residuo) escaneando ventanas del archivo —
 *      soporta init CMAF mezclado con TS, basura inicial y M2TS. El primer
 *      paquete REAL se detecta con corrida de 4 syncs consecutivos.
 *   2. PROLOGO PAT/PMT VERBATIM: en vez de SINTETIZAR un PMT (que podia
 *      diverger del real), se COPIAN los paquetes PAT/PMT originales del
 *      archivo y se anteponen a cada segmento → fidelidad total (stream
 *      types, descriptors, PCR_PID). PMT multipaquete soportado (hasta 4).
 *   3. DURACIONES ANCLADAS A PCR: primer PCR + muestreo (≤48 anclas) +
 *      ultimo PCR (escaneo de la cola) → tasas por tramo → duraciones
 *      proporcionales al tamaño. El total declarado queda ≈ al REAL del
 *      archivo: nunca corto (endOfStream prematuro) ni absurdamente largo.
 *      Sin PCR y sin duracion conocida → estimacion por bitrate
 *      conservador (1 Mbps: erra por EXCESO, que es inocuo).
 *   4. I/O ACOTADO: ≤ ~15MB de lecturas para indexar CUALQUIER tamaño de
 *      archivo (la V6.4.1 escaneaba 256KB POR SEGMENTO: un archivo de 4GB
 *      podia tardar >10s y hacer timeout del manifest en el PC).
 *   5. Si el sniff dice fMP4 pero el walk de cajas no encuentra moof (mixto
 *      init+TS), se intenta el camino TS antes de rendirse.
 *   6. Comentarios de version en el m3u8: abriendo /stream en el navegador
 *      del PC se ve QUE build lo sirvio (debug remoto sin adb).
 */
object LocalMediaPlaylist {

    private const val TAG = "WlfMovie-LocalPlaylist"

    private const val TS_PACKET = 188
    private val TS_STRIDES = intArrayOf(188, 192, 204)

    /** Paquetes TS por segmento (~4.1 MB): balance requests / precision de seek. */
    private const val TS_PACKETS_PER_SEGMENT = 22000

    /** Primer segmento (~550 KB) para que el video arranque rapido en el PC. */
    private const val TS_PACKETS_FIRST_SEGMENT = 3000

    /** Ventana maxima para buscar un paquete PCR al inicio de cada ancla. */
    private const val PCR_SCAN_LIMIT_BYTES = 256L * 1024
    private const val PCR_SCAN_CHUNK_BYTES = 64 * 1024

    /** Ventana para parsear PAT/PMT al inicio del archivo. */
    private const val PSI_SCAN_BYTES = 1024 * 1024

    /** Ventanas donde buscar la grilla de sync (cabeza del archivo hacia adelante). */
    private const val SYNC_SCAN_WINDOW = 512L * 1024
    private val SYNC_SCAN_BASES = longArrayOf(0L, 1L shl 20, 2L shl 20, 4L shl 20, 8L shl 20)
    private const val SYNC_MIN_RATIO = 0.90
    private const val SYNC_MIN_RATIO_RELAXED = 0.60

    /** Maximo de anclas PCR intermedias (acota el I/O en archivos gigantes). */
    private const val MAX_PCR_ANCHORS = 48

    private const val DEFAULT_SEGMENT_DURATION_SEC = 6.0
    private const val MIN_SANE_SEGMENT_SEC = 0.05
    private const val MAX_SANE_SEGMENT_SEC = 600.0

    /** Sin PCR ni duracion conocida: estimar bitrate bajo → total declarado
     *  LARGO (inocuo). Un total CORTO mata la reproduccion (endOfStream). */
    private const val ASSUMED_BPS_WHEN_UNKNOWN = 1_000_000L

    /** Rango de bitrate "fisico" para validar tramos entre anclas. */
    private const val MIN_SANE_BPS = 50_000.0
    private const val MAX_SANE_BPS = 100_000_000.0

    /** Target de bytes por segmento fMP4 (agrupa fragmentos CMAF vecinos). */
    private const val FMP4_GROUP_TARGET_BYTES = 4L * 1024 * 1024

    // ================================================================
    // Resultado de la indexacion
    // ================================================================

    class Segment(val offset: Long, val length: Long)

    class LocalIndex(
        /** true = fMP4/CMAF, false = MPEG-TS. */
        val isFmp4: Boolean,
        /** Segmentos en bytes del archivo (sin el prologo). */
        val segments: List<Segment>,
        /** Duracion en segundos de cada segmento (mismo orden que [segments]). */
        val durationsSec: List<Double>,
        /** TS: paquetes PAT/PMT originales a anteponer a cada segmento. */
        val tsPrologue: ByteArray?,
        /** fMP4: fin exclusivo del segmento de inicializacion (0 = sin init). */
        val fmp4InitEnd: Long,
        val totalDurationSec: Double,
    )

    /**
     * Parsea el archivo y construye el indice de segmentos.
     * @return null si el archivo no se puede indexar → el llamador debe usar
     *         [buildLegacyByterangeM3u8] como ultimo recurso.
     */
    fun buildIndex(file: File, knownDurationMs: Long): LocalIndex? {
        val fileLen = file.length()
        if (!file.exists() || fileLen <= 0L) {
            Log.e(TAG, "buildIndex: archivo inexistente o vacio: ${file.path}")
            return null
        }
        return try {
            if (sniffIsFmp4(file)) {
                // fMP4 real… o MIXTO (init CMAF + segmentos TS concatenados):
                // si el walk de cajas no encuentra moof, probamos el camino TS.
                buildFmp4Index(file, fileLen, knownDurationMs)
                    ?: buildTsIndex(file, fileLen, knownDurationMs)
            } else {
                buildTsIndex(file, fileLen, knownDurationMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "buildIndex: error", e)
            null
        }
    }

    /**
     * Genera el m3u8 con URLs dedicadas por segmento:
     *   {baseUrl}/localsegment?i=N        (N = 0..segments-1)
     *   {baseUrl}/localsegment?i=init     (segmento de inicializacion fMP4)
     */
    fun buildM3u8(index: LocalIndex, baseUrl: String): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("# WlfMovie V6.4.2 local-segments (autocontenido)\n")
        sb.append("#EXT-X-VERSION:6\n")
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        sb.append("#EXT-X-TARGETDURATION:").append(targetDuration(index.durationsSec)).append('\n')
        if (index.isFmp4 && index.fmp4InitEnd > 0L) {
            sb.append("#EXT-X-MAP:URI=\"").append(baseUrl).append("/localsegment?i=init\"\n")
        }
        for (i in index.segments.indices) {
            sb.append("#EXTINF:").append(String.format(Locale.US, "%.3f", index.durationsSec[i])).append(",\n")
            sb.append(baseUrl).append("/localsegment?i=").append(i).append('\n')
        }
        sb.append("#EXT-X-ENDLIST\n")
        return sb.toString()
    }

    /**
     * ULTIMO RECURSO (archivo no indexable): el m3u8 por rango de bytes de la
     * V6.4.0. La reproduccion no esta garantizada (solo el primer trozo lleva
     * PAT/PMT), pero con duraciones que NUNCA quedan cortas vs el contenido
     * real (ver V6.4.2.3 arriba) al menos no dispara el endOfStream prematuro.
     */
    fun buildLegacyByterangeM3u8(file: File, fileUrl: String, knownDurationMs: Long): String {
        val fileLen = file.length()
        val firstLen = alignDown(512L * 1024)
        val segLen = alignDown(4L * 1024 * 1024)

        val segs = ArrayList<Segment>()
        var start = 0L
        while (start < fileLen) {
            val size = if (segs.isEmpty()) firstLen else segLen
            val endExclusive = minOf(start + size, fileLen)
            segs.add(Segment(start, endExclusive - start))
            start = endExclusive
        }
        val n = segs.size

        // V6.4.2: duraciones a prueba de endOfStream prematuro.
        // - duracion conocida > 0 → repartida uniforme (historico).
        // - sino → estimacion por bitrate conservador (1 Mbps): el total
        //   declarado queda LARGO (la barra de progreso se estira, pero no
        //   mata la reproduccion). Un total CORTO si la mata.
        val durations = if (n > 0 && knownDurationMs > 0) {
            val per = (knownDurationMs / 1000.0) / n
            List(n) { per }
        } else if (n > 0) {
            List(n) { (segs[it].length * 8.0 / ASSUMED_BPS_WHEN_UNKNOWN).coerceIn(1.0, MAX_SANE_SEGMENT_SEC) }
        } else {
            listOf(DEFAULT_SEGMENT_DURATION_SEC)
        }

        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("# WlfMovie V6.4.2 legacy-byterange (fallback — archivo no indexable)\n")
        sb.append("#EXT-X-VERSION:4\n")
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        sb.append("#EXT-X-TARGETDURATION:").append(targetDuration(durations)).append('\n')
        for (i in segs.indices) {
            sb.append("#EXTINF:").append(String.format(Locale.US, "%.3f", durations[i])).append(",\n")
            sb.append("#EXT-X-BYTERANGE:").append(segs[i].length).append('@').append(segs[i].offset).append('\n')
            sb.append(fileUrl).append('\n')
        }
        sb.append("#EXT-X-ENDLIST\n")
        return sb.toString()
    }

    // ================================================================
    // Deteccion de formato
    // ================================================================

    private val FMP4_FIRST_BOXES = setOf("ftyp", "styp", "moov", "moof", "sidx", "free", "skip", "pdin", "uuid")

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
                // sync byte MPEG-TS → definitivamente TS
                if (header[0].toInt() == 0x47) return false
                val boxType = String(header, 4, 4, Charsets.US_ASCII)
                val fmp4 = boxType in FMP4_FIRST_BOXES
                if (fmp4) Log.i(TAG, "sniffIsFmp4: fMP4 detectado (caja inicial '$boxType')")
                fmp4
            }
        } catch (e: Exception) {
            Log.e(TAG, "sniffIsFmp4: error, asumiendo MPEG-TS", e)
            false
        }
    }

    // ================================================================
    // MPEG-TS: deteccion de la grilla de sync (stride + residuo)
    // ================================================================

    /**
     * Grilla de paquetes TS detectada en el archivo.
     *
     * @param stride          188 (clasico), 192 (M2TS/BDAV) o 204 (DVB async).
     * @param residue         residuo mod stride de las posiciones con 0x47.
     * @param firstPacketOffset primer paquete REAL (corrida de 4 syncs) — salta
     *                        cajas fMP4 o basura mezclada al inicio.
     * @param pcrPid          PID que mas PCR lleva en la ventana escaneada (-1 si ninguno).
     */
    private class TsSync(val stride: Int, val residue: Int, val firstPacketOffset: Long, val pcrPid: Int)

    private fun detectTsSync(file: File, fileLen: Long): TsSync? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                var fallback: TsSync? = null
                for (base in SYNC_SCAN_BASES) {
                    if (base + (TS_STRIDES.maxOrNull() ?: TS_PACKET) * 8 > fileLen) break
                    val winLen = minOf(SYNC_SCAN_WINDOW, fileLen - base).toInt()
                    val buf = ByteArray(winLen)
                    raf.seek(base)
                    raf.readFully(buf)

                    // hits[s][r]: bytes 0x47 sobre la grilla (stride s, residuo r)
                    var bestStride = 0
                    var bestRes = 0
                    var bestHits = 0
                    for (s in TS_STRIDES) {
                        val hits = IntArray(s)
                        var p = 0
                        while (p < winLen) {
                            if (buf[p].toInt() == 0x47) hits[p % s]++
                            p++
                        }
                        for (r in 0 until s) {
                            if (hits[r] > bestHits) {
                                bestHits = hits[r]
                                bestStride = s
                                bestRes = r
                            }
                        }
                    }
                    if (bestStride == 0) continue
                    val ratio = bestHits.toDouble() / (winLen.toDouble() / bestStride)
                    if (ratio < SYNC_MIN_RATIO_RELAXED) continue

                    val first = findFirstPacketRun(raf, fileLen, bestStride, bestRes)
                    val pcrPid = detectPcrPid(buf, base, winLen, bestStride, first)
                    val sync = TsSync(bestStride, bestRes, first, pcrPid)
                    if (ratio >= SYNC_MIN_RATIO) {
                        Log.i(
                            TAG,
                            "detectTsSync: stride=$bestStride residuo=$bestRes primerPaquete=$first " +
                                "pcrPid=$pcrPid (ratio=" + String.format(Locale.US, "%.2f", ratio) + ")"
                        )
                        return sync
                    }
                    if (fallback == null) fallback = sync
                }
                fallback
            }
        } catch (e: Exception) {
            Log.e(TAG, "detectTsSync: error", e)
            null
        }
    }

    /** Primer p ≥ 0 de la grilla con 4 paquetes 0x47 consecutivos (salta basura/init). */
    private fun findFirstPacketRun(raf: RandomAccessFile, fileLen: Long, stride: Int, residue: Int): Long {
        return try {
            val runNeeded = 4
            val chunkBytes = 128 * 1024
            var base = 0L
            val buf = ByteArray(chunkBytes)
            while (base < fileLen) {
                val toRead = minOf(chunkBytes.toLong(), fileLen - base).toInt()
                if (toRead <= stride * (runNeeded + 1)) break
                raf.seek(base)
                raf.readFully(buf, 0, toRead)
                var p = residue.toLong()
                while (p < base) p += stride
                var run = 0
                var runStart = -1L
                while (p + TS_PACKET <= base + toRead) {
                    val off = (p - base).toInt()
                    if (buf[off].toInt() == 0x47) {
                        if (run == 0) runStart = p
                        run++
                        if (run >= runNeeded) return runStart
                    } else {
                        run = 0
                    }
                    p += stride
                }
                base += (toRead - stride * runNeeded).toLong()
            }
            residue.toLong()
        } catch (e: Exception) {
            residue.toLong()
        }
    }

    /** PID que mas paquetes con PCR aparece en la ventana (independiente del PMT). */
    private fun detectPcrPid(buf: ByteArray, bufBase: Long, bufLen: Int, stride: Int, firstPacket: Long): Int {
        val counts = HashMap<Int, Int>()
        var p = maxOf(firstPacket, bufBase)
        val end = bufBase + bufLen - TS_PACKET
        while (p < end) {
            val off = (p - bufBase).toInt()
            if (off >= 0 && buf[off].toInt() == 0x47 && hasPcrFlag(buf, off)) {
                val pid = pidOf(buf, off)
                counts[pid] = (counts[pid] ?: 0) + 1
            }
            p += stride
        }
        var bestPid = -1
        var bestCount = 0
        for ((pid, c) in counts) {
            if (c > bestCount) {
                bestCount = c
                bestPid = pid
            }
        }
        return if (bestCount >= 2) bestPid else -1
    }

    private fun hasPcrFlag(b: ByteArray, pos: Int): Boolean {
        val afc = (b[pos + 3].toInt() shr 4) and 0x03
        if (afc != 2 && afc != 3) return false
        val afLen = b[pos + 4].toInt() and 0xFF
        if (afLen < 7 || afLen > 182) return false
        return b[pos + 5].toInt() and 0x10 != 0
    }

    // ================================================================
    // MPEG-TS: PAT/PMT (parseo liviano + prologo VERBATIM)
    // ================================================================

    private class TsProgram(val pmtPid: Int, val pcrPid: Int, val streams: List<Pair<Int, Int>>)

    private class TsPrologue(val bytes: ByteArray, val pcrPid: Int)

    private fun pidOf(buf: ByteArray, pos: Int): Int =
        ((buf[pos + 1].toInt() and 0x1F) shl 8) or (buf[pos + 2].toInt() and 0xFF)

    /** PAT en el paquete [pos] (PID 0) → PID del PMT. */
    private fun parsePatPmtPid(buf: ByteArray, pos: Int): Int? {
        if (buf[pos + 1].toInt() and 0x40 == 0) return null   // sin PUSI
        val pointer = buf[pos + 4].toInt() and 0xFF
        val s = pos + 5 + pointer
        if (s + 8 > pos + TS_PACKET) return null
        if (buf[s].toInt() != 0x00) return null               // table_id = PAT
        val sectionLength = ((buf[s + 1].toInt() and 0x0F) shl 8) or (buf[s + 2].toInt() and 0xFF)
        var p = s + 8
        val end = minOf(s + 3 + sectionLength - 4, pos + TS_PACKET)
        while (p + 4 <= end) {
            val programNumber = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)
            val pid = ((buf[p + 2].toInt() and 0x1F) shl 8) or (buf[p + 3].toInt() and 0xFF)
            if (programNumber != 0) return pid
            p += 4
        }
        return null
    }

    /** PMT en el paquete [pos] → PCR PID + lista de (stream_type, PID). */
    private fun parsePmt(buf: ByteArray, pos: Int, pmtPid: Int): TsProgram? {
        if (buf[pos + 1].toInt() and 0x40 == 0) return null   // sin PUSI
        val pointer = buf[pos + 4].toInt() and 0xFF
        val s = pos + 5 + pointer
        if (s + 12 > pos + TS_PACKET) return null
        if (buf[s].toInt() != 0x02) return null               // table_id = PMT
        val sectionLength = ((buf[s + 1].toInt() and 0x0F) shl 8) or (buf[s + 2].toInt() and 0xFF)
        val pcrPid = ((buf[s + 8].toInt() and 0x1F) shl 8) or (buf[s + 9].toInt() and 0xFF)
        val programInfoLen = ((buf[s + 10].toInt() and 0x0F) shl 8) or (buf[s + 11].toInt() and 0xFF)
        var p = s + 12 + programInfoLen
        val end = minOf(s + 3 + sectionLength - 4, pos + TS_PACKET)
        val streams = ArrayList<Pair<Int, Int>>()
        while (p + 5 <= end) {
            val streamType = buf[p].toInt() and 0xFF
            val esPid = ((buf[p + 1].toInt() and 0x1F) shl 8) or (buf[p + 2].toInt() and 0xFF)
            val esInfoLen = ((buf[p + 3].toInt() and 0x0F) shl 8) or (buf[p + 4].toInt() and 0xFF)
            streams.add(streamType to esPid)
            p += 5 + esInfoLen
        }
        if (streams.isEmpty()) return null
        return TsProgram(pmtPid, pcrPid, streams)
    }

    /**
     * Prologo de cada segmento: paquetes PAT + PMT ORIGINALES del archivo,
     * copiados verbatim (fidelidad total: stream_types, descriptors, PCR_PID,
     * PMTs multipaquete). Cada /localsegment queda autocontenido: el demuxer
     * de hls.js puede reiniciarse mil veces y siempre reencuentra el mapa.
     */
    private fun buildTsPrologue(file: File, sync: TsSync): TsPrologue? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val windowLen = minOf(PSI_SCAN_BYTES.toLong(), raf.length()).toInt()
                if (windowLen < TS_PACKET) return null
                val buf = ByteArray(windowLen)
                raf.seek(0L)
                raf.readFully(buf)

                // 1) PAT → PID del PMT (sobre la grilla detectada)
                var patPos = -1L
                var pmtPid = -1
                var p = sync.firstPacketOffset
                while (p + TS_PACKET <= windowLen && pmtPid < 0) {
                    val off = p.toInt()
                    if (off >= 0 && buf[off].toInt() == 0x47 && pidOf(buf, off) == 0) {
                        val pid = parsePatPmtPid(buf, off)
                        if (pid != null && pid > 0 && pid != 0x1FFF) {
                            pmtPid = pid
                            patPos = p
                        }
                    }
                    p += sync.stride
                }
                if (pmtPid < 0 || patPos < 0) {
                    Log.w(TAG, "buildTsPrologue: PAT no encontrado en la grilla")
                    return null
                }

                // 2) paquetes del PMT (verbatim): primero con PUSI + hasta 3 de
                //    continuacion (secciones PMT largas spannean paquetes)
                val pmtPackets = ArrayList<ByteArray>()
                var program: TsProgram? = null
                var seenPusi = false
                var q = patPos
                var scanned = 0
                while (q + TS_PACKET <= windowLen && pmtPackets.size < 4 && scanned < 512) {
                    val off = q.toInt()
                    if (buf[off].toInt() == 0x47 && pidOf(buf, off) == pmtPid) {
                        val pusi = buf[off + 1].toInt() and 0x40 != 0
                        if (!seenPusi) {
                            if (pusi) {
                                val parsed = parsePmt(buf, off, pmtPid)
                                if (parsed != null) {
                                    seenPusi = true
                                    program = parsed
                                    pmtPackets.add(buf.copyOfRange(off, off + TS_PACKET))
                                }
                            }
                        } else {
                            if (pusi) break   // arranco otra seccion → ya tengo la del PMT
                            pmtPackets.add(buf.copyOfRange(off, off + TS_PACKET))
                        }
                    }
                    q += sync.stride
                    scanned++
                }
                if (!seenPusi || pmtPackets.isEmpty()) {
                    Log.w(TAG, "buildTsPrologue: PMT no encontrado (pid=$pmtPid)")
                    return null
                }

                // 3) PAT verbatim + PMT verbatim
                var prologue = buf.copyOfRange(patPos.toInt(), patPos.toInt() + TS_PACKET)
                for (pkt in pmtPackets) prologue += pkt
                // sanity: todos los paquetes del prologo empiezan con sync
                var i = 0
                while (i < prologue.size) {
                    if (prologue[i].toInt() != 0x47) {
                        Log.e(TAG, "buildTsPrologue: paquete $i del prologo sin sync — abortando")
                        return null
                    }
                    i += TS_PACKET
                }

                val pcrPid = program?.pcrPid?.takeIf { it in 1..0x1FFE } ?: sync.pcrPid
                Log.i(
                    TAG,
                    "buildTsPrologue: ${prologue.size / TS_PACKET} paquetes (${prologue.size}B), " +
                        "pmtPid=$pmtPid, pcrPid=$pcrPid, streams=${program?.streams?.size ?: '?'}"
                )
                TsPrologue(prologue, pcrPid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "buildTsPrologue: error", e)
            null
        }
    }

    // ================================================================
    // MPEG-TS: PCR (reloj del programa) para duraciones reales
    // ================================================================

    /** PCR base (unidades de 90kHz) del paquete en [pos], o null si no tiene. */
    private fun readPcr(data: ByteArray, pos: Int): Long? {
        val afc = (data[pos + 3].toInt() shr 4) and 0x03
        if (afc != 2 && afc != 3) return null                // sin adaptation field
        val afLen = data[pos + 4].toInt() and 0xFF
        if (afLen < 7 || afLen > 182) return null
        if (data[pos + 5].toInt() and 0x10 == 0) return null // PCR flag no seteado
        var pcrBase = 0L
        for (i in 0 until 4) {
            pcrBase = (pcrBase shl 8) or (data[pos + 6 + i].toLong() and 0xFF)
        }
        pcrBase = (pcrBase shl 1) or ((data[pos + 10].toLong() shr 7) and 0x01L)
        return pcrBase
    }

    /** Primer paquete con PCR de [pcrPid] desde [startOffset] (ya alineado a grilla). */
    private fun findPcr(raf: RandomAccessFile, startOffset: Long, pcrPid: Int, stride: Int, maxLen: Long): Anchor? {
        val remaining = raf.length() - startOffset
        if (remaining <= 0) return null
        val window = minOf(maxLen, remaining).toInt()
        if (window < TS_PACKET) return null
        val chunk = ByteArray(PCR_SCAN_CHUNK_BYTES)
        var scanned = 0
        while (scanned < window) {
            val toRead = minOf(chunk.size, window - scanned)
            raf.seek(startOffset + scanned)
            raf.readFully(chunk, 0, toRead)
            val packets = toRead / stride
            for (k in 0 until packets) {
                val off = k * stride
                if (chunk[off].toInt() == 0x47 && pidOf(chunk, off) == pcrPid) {
                    readPcr(chunk, off)?.let { return Anchor(startOffset + scanned + off.toLong(), it) }
                }
            }
            if (packets == 0) break
            scanned += packets * stride
        }
        return null
    }

    /** Ultimo paquete con PCR de [pcrPid] del archivo (ventana de la cola, alineada). */
    private fun findLastPcr(raf: RandomAccessFile, sync: TsSync, usableEnd: Long, pcrPid: Int, maxLen: Long): Anchor? {
        if (usableEnd <= sync.firstPacketOffset + sync.stride) return null
        var windowStart = maxOf(sync.firstPacketOffset, usableEnd - maxLen)
        val rel = windowStart - sync.firstPacketOffset
        windowStart = sync.firstPacketOffset + (rel / sync.stride) * sync.stride
        val windowLen = (usableEnd - windowStart).toInt()
        if (windowLen < sync.stride) return null
        val buf = ByteArray(windowLen)
        raf.seek(windowStart)
        raf.readFully(buf)
        var last: Anchor? = null
        var off = 0
        while (off + TS_PACKET <= windowLen) {
            if (buf[off].toInt() == 0x47 && pidOf(buf, off) == pcrPid) {
                readPcr(buf, off)?.let { last = Anchor(windowStart + off.toLong(), it) }
            }
            off += sync.stride
        }
        return last
    }

    // ================================================================
    // Duraciones (comun TS/fMP4): anclas + tasas por tramo
    // ================================================================

    /** Ancla temporal: byte del archivo → tiempo en unidades (PCR 90kHz / tfdt). */
    private class Anchor(val bytePos: Long, val timeUnits: Long)

    private class Span(val fromByte: Long, val toByte: Long, val bytesPerSec: Double)

    /**
     * Duraciones por segmento:
     * 1. Anclas (PCR/tfdt) ≥ 2 y tramos validos → duracion = bytes / tasa del
     *    tramo (interpolacion; el total queda ≈ al muro temporal REAL).
     * 2. Sino, duracion conocida del player → reparto proporcional por bytes.
     * 3. Sino → bitrate conservador (total declarado LARGO, nunca corto).
     */
    private fun anchoredDurations(
        segments: List<Segment>,
        anchors: List<Anchor>,
        unitsPerSecond: Double,
        knownDurationMs: Long,
    ): List<Double> {
        val n = segments.size
        if (n == 0) return emptyList()
        val known = if (knownDurationMs > 0) knownDurationMs / 1000.0 else 0.0

        // Tramos validos entre anclas consecutivas
        val spans = ArrayList<Span>()
        if (anchors.size >= 2 && unitsPerSecond > 0.0) {
            for (i in 0 until anchors.size - 1) {
                val a = anchors[i]
                val b = anchors[i + 1]
                val spanB = b.bytePos - a.bytePos
                val spanT = (b.timeUnits - a.timeUnits) / unitsPerSecond
                if (spanB > 0 && spanT in 0.001..3600.0) {
                    val bps = spanB / spanT
                    if (bps in MIN_SANE_BPS..MAX_SANE_BPS) {
                        spans.add(Span(a.bytePos, b.bytePos, bps))
                    }
                }
            }
        }

        fun rateAt(byte: Long): Double {
            for (s in spans) {
                if (byte >= s.fromByte && byte < s.toByte) return s.bytesPerSec
            }
            if (spans.isNotEmpty()) {
                return if (byte < spans.first().fromByte) spans.first().bytesPerSec else spans.last().bytesPerSec
            }
            return 0.0
        }

        val result = ArrayList<Double>(n)
        when {
            spans.isNotEmpty() -> {
                // El archivo es la verdad. Si la duracion conocida del player
                // discrepa mucho (descarga truncada, TS sin duracion), gana PCR.
                for (seg in segments) {
                    val rate = rateAt(seg.offset + seg.length / 2)
                    result.add((seg.length / rate).coerceIn(MIN_SANE_SEGMENT_SEC, MAX_SANE_SEGMENT_SEC))
                }
                val anchoredTotal = result.sum()
                if (known > 0.0 && (anchoredTotal > known * 1.5 || anchoredTotal < known * 0.5)) {
                    Log.i(
                        TAG,
                        "anchoredDurations: conocido=${known}s vs archivo=" +
                            String.format(Locale.US, "%.1f", anchoredTotal) + "s — gana el archivo"
                    )
                }
            }
            known > 0.0 -> {
                val totalBytes = segments.sumOf { it.length }.toDouble()
                if (totalBytes > 0.0) {
                    for (seg in segments) {
                        result.add((known * seg.length / totalBytes).coerceIn(MIN_SANE_SEGMENT_SEC, MAX_SANE_SEGMENT_SEC))
                    }
                }
            }
            else -> {
                for (seg in segments) {
                    result.add((seg.length * 8.0 / ASSUMED_BPS_WHEN_UNKNOWN).coerceIn(MIN_SANE_SEGMENT_SEC, MAX_SANE_SEGMENT_SEC))
                }
            }
        }
        while (result.size < n) result.add(DEFAULT_SEGMENT_DURATION_SEC)
        return result
    }

    // ================================================================
    // MPEG-TS: armado del indice
    // ================================================================

    private fun buildTsIndex(file: File, fileLen: Long, knownDurationMs: Long): LocalIndex? {
        val sync = detectTsSync(file, fileLen) ?: run {
            Log.w(TAG, "buildTsIndex: grilla de sync no detectable — fallback byterange")
            return null
        }

        val pro = buildTsPrologue(file, sync) ?: run {
            Log.w(TAG, "buildTsIndex: PAT/PMT no encontrados — fallback byterange")
            return null
        }

        // Segmentos en fronteras de paquete completos (la cola < stride se ignora)
        val usablePackets = ((fileLen - sync.firstPacketOffset) / sync.stride).toInt()
        if (usablePackets <= 0) return null
        val segments = ArrayList<Segment>()
        var pkt = 0
        while (pkt < usablePackets) {
            val count = if (segments.isEmpty()) {
                minOf(TS_PACKETS_FIRST_SEGMENT, usablePackets)
            } else {
                minOf(TS_PACKETS_PER_SEGMENT, usablePackets - pkt)
            }
            val start = sync.firstPacketOffset + pkt.toLong() * sync.stride
            segments.add(Segment(start, count.toLong() * sync.stride))
            pkt += count
        }
        val usableEnd = sync.firstPacketOffset + usablePackets.toLong() * sync.stride

        // Anclas PCR — I/O ACOTADO: primero + muestreo (≤48) + ultimo (cola)
        val anchors = ArrayList<Anchor>()
        val scanPid = if (pro.pcrPid in 1..0x1FFE) pro.pcrPid else sync.pcrPid
        if (scanPid in 1..0x1FFE) {
            try {
                RandomAccessFile(file, "r").use { raf ->
                    findPcr(raf, segments.first().offset, scanPid, sync.stride, PCR_SCAN_LIMIT_BYTES)
                        ?.let { anchors.add(it) }
                    if (segments.size > 2) {
                        val step = ceil(segments.size / MAX_PCR_ANCHORS.toDouble()).toInt().coerceAtLeast(1)
                        var i = step
                        while (i < segments.size - 1) {
                            findPcr(raf, segments[i].offset, scanPid, sync.stride, PCR_SCAN_LIMIT_BYTES)
                                ?.let { anchors.add(it) }
                            i += step
                        }
                    }
                    findLastPcr(raf, sync, usableEnd, scanPid, PCR_SCAN_LIMIT_BYTES)?.let { anchors.add(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "buildTsIndex: error escaneando PCR (duraciones por estimacion): ${e.message}")
            }
        }
        anchors.sortBy { it.bytePos }
        val cleanAnchors = ArrayList<Anchor>(anchors.size)
        for (a in anchors) {
            if (cleanAnchors.isEmpty() || cleanAnchors.last().bytePos != a.bytePos) cleanAnchors.add(a)
        }

        val durations = anchoredDurations(segments, cleanAnchors, 90000.0, knownDurationMs)
        val total = durations.sum()
        Log.i(
            TAG,
            "buildTsIndex: ${segments.size} segmentos autocontenidos, prologo=${pro.bytes.size}B, " +
                "total=" + String.format(Locale.US, "%.1f", total) + "s, anclasPCR=${cleanAnchors.size}"
        )
        return LocalIndex(
            isFmp4 = false,
            segments = segments,
            durationsSec = durations,
            tsPrologue = pro.bytes,
            fmp4InitEnd = 0L,
            totalDurationSec = total,
        )
    }

    // ================================================================
    // fMP4/CMAF
    // ================================================================

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

    private fun boxSize(buf: ByteArray, p: Int): Long = be32(buf, p)

    private fun boxType(buf: ByteArray, p: Int): String = String(buf, p, 4, Charsets.US_ASCII)

    private fun findBox(buf: ByteArray, from: Int, toExclusive: Int, type: String): Int? {
        var p = from
        while (p + 8 <= toExclusive) {
            val s = boxSize(buf, p)
            if (s < 8 || p + s > toExclusive) return null
            if (boxType(buf, p + 4) == type) return p
            p += s.toInt()
        }
        return null
    }

    private class Fmp4InitInfo(val videoTrackId: Long?, val timescale: Double)

    /** moov → track_ID y timescale del track de video (fallback: mvhd). */
    private fun parseFmp4Init(buf: ByteArray): Fmp4InitInfo? {
        val moov = findBox(buf, 0, buf.size, "moov") ?: return null
        val moovEnd = moov + boxSize(buf, moov).toInt()

        var fallbackTimescale = 0.0
        findBox(buf, moov + 8, moovEnd, "mvhd")?.let { mvhd ->
            val v = buf[mvhd + 8].toInt() and 0xFF
            fallbackTimescale = if (v == 1) be32(buf, mvhd + 20).toDouble() else be32(buf, mvhd + 12).toDouble()
        }

        var videoTrackId: Long? = null
        var videoTimescale = 0.0
        var p = moov + 8
        while (p + 8 <= moovEnd) {
            val s = boxSize(buf, p).toInt()
            if (s < 8 || p + s > moovEnd) break
            if (boxType(buf, p + 4) == "trak") {
                val trakEnd = p + s
                val mdia = findBox(buf, p + 8, trakEnd, "mdia")
                if (mdia != null) {
                    val mdiaEnd = mdia + boxSize(buf, mdia).toInt()
                    val isVideo = findBox(buf, mdia + 8, mdiaEnd, "hdlr")
                        ?.let { boxType(buf, it + 16) == "vide" } ?: false
                    var timescale = 0.0
                    findBox(buf, mdia + 8, mdiaEnd, "mdhd")?.let { mdhd ->
                        val v = buf[mdhd + 8].toInt() and 0xFF
                        timescale = if (v == 1) be32(buf, mdhd + 20).toDouble() else be32(buf, mdhd + 12).toDouble()
                    }
                    if (isVideo) {
                        findBox(buf, p + 8, trakEnd, "tkhd")?.let { tkhd ->
                            val v = buf[tkhd + 8].toInt() and 0xFF
                            videoTrackId = if (v == 1) be32(buf, tkhd + 20) else be32(buf, tkhd + 12)
                        }
                        if (timescale > 0.0) videoTimescale = timescale
                    }
                }
            }
            p += s
        }
        val ts = if (videoTimescale > 0.0) videoTimescale else fallbackTimescale
        if (ts <= 0.0) return null
        return Fmp4InitInfo(videoTrackId, ts)
    }

    /** tfdt del traf del track de video (o del primer traf) dentro del moof. */
    private fun readFragmentStartTime(raf: RandomAccessFile, moofOffset: Long, videoTrackId: Long?): Long? {
        return try {
            raf.seek(moofOffset)
            val hdr = ByteArray(8)
            raf.readFully(hdr)
            val moofSize = be32(hdr, 0)
            if (moofSize < 8 || moofSize > 8L * 1024 * 1024) return null
            val buf = ByteArray(moofSize.toInt())
            raf.seek(moofOffset)
            raf.readFully(buf)

            var firstTfdt: Long? = null
            var videoTfdt: Long? = null
            var pos = 8
            while (pos + 8 <= buf.size) {
                val s = boxSize(buf, pos).toInt()
                if (s < 8 || pos + s > buf.size) break
                if (boxType(buf, pos + 4) == "traf") {
                    var p2 = pos + 8
                    var trackId: Long? = null
                    var tfdt: Long? = null
                    while (p2 + 8 <= pos + s) {
                        val s2 = boxSize(buf, p2).toInt()
                        if (s2 < 8 || p2 + s2 > pos + s) break
                        when (boxType(buf, p2 + 4)) {
                            "tfhd" -> trackId = be32(buf, p2 + 12)
                            "tfdt" -> {
                                val version = buf[p2 + 8].toInt() and 0xFF
                                tfdt = if (version == 1) be64(buf, p2 + 12) else be32(buf, p2 + 12)
                            }
                        }
                        p2 += s2
                    }
                    if (tfdt != null) {
                        if (firstTfdt == null) firstTfdt = tfdt
                        if (videoTrackId != null && trackId == videoTrackId) {
                            videoTfdt = tfdt
                            break
                        }
                    }
                }
                pos += s
            }
            videoTfdt ?: firstTfdt
        } catch (e: Exception) {
            null
        }
    }

    private fun buildFmp4Index(file: File, fileLen: Long, knownDurationMs: Long): LocalIndex? {
        // Walk de cajas top-level: offsets de cada moof (fragmento) y styp (marcas)
        val moofStarts = ArrayList<Long>()
        val boundaries = ArrayList<Long>()   // moof | styp, ordenadas
        var firstMoof = -1L
        try {
            RandomAccessFile(file, "r").use { raf ->
                var pos = 0L
                val hdr = ByteArray(8)
                while (pos + 8 <= fileLen) {
                    raf.seek(pos)
                    raf.readFully(hdr)
                    var size = be32(hdr, 0)
                    if (size == 1L) {
                        val ext = ByteArray(8)
                        raf.readFully(ext)
                        size = be64(ext, 0)
                    }
                    if (size < 8 || pos + size > fileLen) {
                        Log.w(TAG, "buildFmp4Index: caja invalida en $pos — truncando walk")
                        break
                    }
                    when (String(hdr, 4, 4, Charsets.US_ASCII)) {
                        "moof" -> {
                            if (firstMoof < 0) firstMoof = pos
                            moofStarts.add(pos)
                            boundaries.add(pos)
                        }
                        "styp" -> boundaries.add(pos)
                    }
                    pos += size
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "buildFmp4Index: error en walk de cajas", e)
        }

        if (moofStarts.isEmpty() || firstMoof <= 0L) {
            Log.w(TAG, "buildFmp4Index: sin fragmentos o sin init — probar TS / fallback byterange")
            return null
        }

        // init = [0, primera frontera) — recorta un styp previo al primer moof
        val initEnd = minOf(boundaries.first(), firstMoof)

        // Agrupar fragmentos en segmentos de ~4MB (cada grupo arranca en un moof)
        fun fragEndOf(k: Int): Long =
            if (k + 1 < moofStarts.size) {
                boundaries.firstOrNull { it > moofStarts[k] } ?: moofStarts[k + 1]
            } else {
                boundaries.firstOrNull { it > moofStarts[k] } ?: fileLen
            }

        val segments = ArrayList<Segment>()
        val groupFirstIdx = ArrayList<Int>()
        var i = 0
        while (i < moofStarts.size) {
            var bytes = 0L
            var j = i
            while (j < moofStarts.size) {
                bytes += fragEndOf(j) - moofStarts[j]
                if (bytes >= FMP4_GROUP_TARGET_BYTES) break
                if (j + 1 < moofStarts.size) j++ else break
            }
            val start = moofStarts[i]
            val end = fragEndOf(j)
            segments.add(Segment(start, end - start))
            groupFirstIdx.add(i)
            i = j + 1
        }
        if (segments.isEmpty()) return null

        // Duraciones por tfdt (timescale del track de video) → anclas
        val anchors = ArrayList<Anchor>()
        var timescale = 0.0
        try {
            val initLen = initEnd.toInt()
            if (initLen in 1..(16 * 1024 * 1024)) {
                RandomAccessFile(file, "r").use { raf ->
                    val initBuf = ByteArray(initLen)
                    raf.seek(0L)
                    raf.readFully(initBuf)
                    val initInfo = parseFmp4Init(initBuf)
                    if (initInfo != null) {
                        timescale = initInfo.timescale
                        for (g in segments.indices) {
                            val tfdt = readFragmentStartTime(raf, moofStarts[groupFirstIdx[g]], initInfo.videoTrackId)
                            if (tfdt != null) {
                                anchors.add(Anchor(segments[g].offset, tfdt))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "buildFmp4Index: error parseando init/tfdt (duraciones estimadas): ${e.message}")
        }

        val durations = anchoredDurations(
            segments, anchors,
            if (timescale > 0.0) timescale else 90000.0,
            knownDurationMs,
        )
        val total = durations.sum()
        Log.i(
            TAG,
            "buildFmp4Index: ${moofStarts.size} fragmentos → ${segments.size} segmentos, init=${initEnd}B, " +
                "timescale=${timescale.toInt()}, total=" + String.format(Locale.US, "%.1f", total) + "s"
        )
        return LocalIndex(
            isFmp4 = true,
            segments = segments,
            durationsSec = durations,
            tsPrologue = null,
            fmp4InitEnd = initEnd,
            totalDurationSec = total,
        )
    }

    // ================================================================
    // Utilidades
    // ================================================================

    private fun alignDown(bytes: Long): Long = (bytes / TS_PACKET) * TS_PACKET

    private fun targetDuration(durations: List<Double>): Int {
        val max = durations.maxOrNull() ?: DEFAULT_SEGMENT_DURATION_SEC
        return maxOf(1, ceil(max).toInt())
    }
}
