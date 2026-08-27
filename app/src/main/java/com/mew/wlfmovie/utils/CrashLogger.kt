package com.mew.wlfmovie.utils

import android.content.Context
import android.os.Build
import android.util.Log
import com.mew.wlfmovie.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WLFMOVIE V6.2: Logger persistente de errores y crashes.
 *
 * Reemplaza a `DebugErrorCollector` (que era solo debug + en memoria).
 *
 * - DEBUG-ONLY desde V6.2.3. La inicializacion (`CrashLogger.init()`)
 *   se llama desde `StreamFlixApp.onCreate()` solo si `BuildConfig.DEBUG`.
 *   Ademas, todas las funciones publicas (`logError`, `markFatalUnread`,
 *   `hasUnreadCrash`) hacen early return en release. En release no se crea
 *   el archivo en disco, no se escribe nada, no hay popups.
 * - Persistente en DEBUG: escribe en `filesDir/wlfmovie-errors.log` que
 *   sobrevive reinicios de la app y del proceso.
 * - Infinito: sin limite de entradas. Solo se trunca por tamano (8 MB),
 *   rotando las entradas mas viejas cuando llega al tope.
 * - El catch handler global (`CrashHandler`) escribe aqui cada crash fatal,
 *   marcando la entrada con tag "FATAL" para que el LastCrashDialog la
 *   detecte al siguiente arranque.
 * - Accesible desde Settings > "Errores recopilados" (mobile y TV), pero
 *   solo visible en debug (el entry se oculta con `isVisible = false` en
 *   release). Botones: Cerrar / Copiar todo / Borrar.
 *
 * Formato de cada entrada en disco (lineas separadas por '\n'):
 *
 *   === <ISO timestamp> | <tag> | <context> ===
 *   <message>
 *   <stack trace completo, multiple lineas>
 *   ---
 *
 * El archivo nunca se encripta ni se comprime. Pensado para que el usuario
 * pueda copiarlo integro y pegarlo en un issue / mensaje de soporte.
 */
object CrashLogger {

    private const val TAG = "WlfMovie-CrashLogger"

    /**
     * Tamano maximo del archivo de log. Cuando se llega al tope, se trunca
     * desde el principio (entradas mas viejas) dejando los ultimos ~6 MB.
     */
    private const val MAX_FILE_BYTES = 8L * 1024L * 1024L

    /**
     * Tras superar el tope, dejamos este tamano aproximado.
     */
    private const val TRUNCATED_TARGET_BYTES = 6L * 1024L * 1024L

    private const val LOG_FILE_NAME = "wlfmovie-errors.log"
    private const val UNREAD_PREFS_KEY = "wlfmovie_last_crash_unread"

    private lateinit var appContext: Context

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val ioLock = Any()

    /**
     * Debe llamarse una vez desde `StreamFlixApp.onCreate()` antes de
     * cualquier otra inicializacion que pudiera crashear.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        // Ensure log file exists so subsequent append calls don't recreate it
        // on the crash path (which would risk losing context).
        try {
            logFile().apply { if (!exists()) createNewFile() }
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudo crear el archivo de log", t)
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    data class ErrorEntry(
        val timestamp: Long,
        val tag: String,
        val message: String,
        val stackTrace: String?,
        val context: String,
    )

    /**
     * Registra un error. Funciona en release. No crashea si el archivo
     * no se puede escribir (mejor esfuerzo: si falla, al menos lo manda
     * a Log.e para que aparezca en logcat).
     *
     * Firma UNICA (sin overload alternativo con orden cambiado de params)
     * para evitar ambiguedad de resolucion cuando se llama con named args.
     * Los llamadores que quieran pasar contexto sin throwable deben usar
     * named args: `logError(tag=..., message=..., context=...)`.
     *
     * WLFMOVIE V6.2.2: En RELEASE no se persiste nada — solo se escribe
     * a logcat. El usuario no quiere ver popups de errores ni recopilarlos
     * en builds oficiales; eso queda solo para debug.
     */
    fun logError(tag: String, message: String, throwable: Throwable? = null, context: String = "") {
        // Siempre mandamos a logcat (incluso en release, util para adb logcat).
        Log.e(tag, message, throwable)

        // Persistencia en disco: solo en debug.
        if (!BuildConfig.DEBUG) return

        val ts = System.currentTimeMillis()
        val stack = throwable?.let { stackToString(it) }
        val entry = formatEntry(ts, tag, message, context, stack)

        synchronized(ioLock) {
            try {
                appendToFile(entry)
            } catch (t: Throwable) {
                Log.e(TAG, "No se pudo escribir en crash log: $t")
            }
        }
    }

    /**
     * Marca el ultimo error fatal como "no leido aun" para que el
     * LastCrashDialog lo muestre en el siguiente arranque.
     * No-op en release (no queremos popups de crash en builds oficiales).
     */
    fun markFatalUnread() {
        if (!BuildConfig.DEBUG) return
        prefs().edit().putBoolean(UNREAD_PREFS_KEY, true).apply()
    }

    /**
     * True si hay un crash fatal registrado que el usuario aun no ha visto.
     * Siempre false en release — el LastCrashDialog no debe mostrarse.
     */
    fun hasUnreadCrash(): Boolean {
        if (!BuildConfig.DEBUG) return false
        return prefs().getBoolean(UNREAD_PREFS_KEY, false)
    }

    /**
     * Llamado por LastCrashDialog cuando el usuario cierra el dialogo.
     */
    fun markCrashRead() {
        prefs().edit().putBoolean(UNREAD_PREFS_KEY, false).apply()
    }

    /**
     * Devuelve el ultimo crash fatal registrado (entrada con tag "FATAL"),
     * o null si no hay ninguno. Solo busca hacia atras desde el final del
     * archivo; si no encuentra un FATAL en las ultimas 256 entradas, retorna null.
     */
    fun getLastFatalCrash(): ErrorEntry? {
        val entries = readEntriesFromEnd(maxEntries = 256)
        return entries.firstOrNull { it.tag == "FATAL" }
    }

    /**
     * Devuelve todas las entradas registradas (mas reciente primero).
     */
    fun getErrors(): List<ErrorEntry> = readEntriesFromEnd(maxEntries = Int.MAX_VALUE).reversed()

    /**
     * Devuelve el log completo como texto (mas viejo arriba, mas reciente
     * abajo) — perfecto para copiar / compartir.
     */
    fun getErrorsAsText(): String {
        val file = logFile()
        if (!file.exists() || file.length() == 0L) {
            return "No hay errores registrados."
        }
        return try {
            val raw = file.readText()
            buildString {
                append("=== WlfMovie Error Log ===\n")
                append("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
                append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})\n")
                append("Log file: ${file.absolutePath}\n")
                append("File size: ${file.length()} bytes\n")
                append("===\n\n")
                append(raw)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudo leer el log completo", t)
            "Error al leer el log: $t"
        }
    }

    /**
     * Numero de entradas en el log.
     */
    fun count(): Int {
        val file = logFile()
        if (!file.exists() || file.length() == 0L) return 0
        return try {
            file.readLines().count { it.startsWith("===") }
        } catch (t: Throwable) {
            0
        }
    }

    /**
     * Borra el log completo. Llamado desde el boton "Borrar" del UI.
     */
    fun clear() {
        synchronized(ioLock) {
            try {
                logFile().writeText("")
                prefs().edit().putBoolean(UNREAD_PREFS_KEY, false).apply()
                Log.i(TAG, "Log borrado")
            } catch (t: Throwable) {
                Log.e(TAG, "No se pudo borrar el log", t)
            }
        }
    }

    // ------------------------------------------------------------------
    // Internal: file IO
    // ------------------------------------------------------------------

    private fun logFile(): File = File(appContext.filesDir, LOG_FILE_NAME)

    private fun prefs() = appContext.getSharedPreferences("wlfmovie_crash", Context.MODE_PRIVATE)

    private fun appendToFile(entry: String) {
        val file = logFile()
        file.parentFile?.mkdirs()
        // Check rotation before appending.
        if (file.exists() && file.length() > MAX_FILE_BYTES) {
            rotate(file)
        }
        file.appendText(entry)
    }

    /**
     * Trunca el archivo desde el principio: lee todo, se queda con los
     * ultimos ~6 MB y reescribe. Hace lo posible por ser eficiente en
     * memoria (stream + buffer).
     */
    private fun rotate(file: File) {
        try {
            val total = file.length()
            if (total <= TRUNCATED_TARGET_BYTES) return
            val skipBytes = total - TRUNCATED_TARGET_BYTES
            // Avanzamos al siguiente salto de linea para no cortar a mitad
            // de una entrada.
            file.inputStream().use { input ->
                val skipped = input.skip(skipBytes)
                if (skipped < skipBytes) return
                // Saltamos hasta el primer '\n' que abre la siguiente entrada.
                while (true) {
                    val b = input.read()
                    if (b == -1) return
                    if (b.toChar() == '=') {
                        // Posible inicio de entrada "===" — leemos los 2
                        // siguientes chars para confirmar.
                        val mark2 = ByteArray(2)
                        val r1 = input.read(mark2, 0, 1)
                        val r2 = input.read(mark2, 1, 1)
                        if (r1 == 1 && r2 == 1 &&
                            mark2[0].toChar() == '=' &&
                            mark2[1].toChar() == '='
                        ) {
                            // Confirmado: copiamos el resto.
                            val rest = input.readBytes()
                            file.outputStream().use { out ->
                                out.write("===".toByteArray())
                                out.write(mark2)
                                out.write(rest)
                            }
                            return
                        } else {
                            // Falso positivo — no hacemos nada.
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Fallo la rotacion del log, dejando como esta", t)
        }
    }

    private fun readEntriesFromEnd(maxEntries: Int): List<ErrorEntry> {
        val file = logFile()
        if (!file.exists() || file.length() == 0L) return emptyList()
        val lines = try {
            file.readLines()
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudo leer el log", t)
            return emptyList()
        }

        val entries = mutableListOf<ErrorEntry>()
        var i = lines.size - 1
        while (i >= 0 && entries.size < maxEntries) {
            // Cada entrada empieza con "=== <ts> | <tag> | <context> ==="
            val header = lines[i]
            if (!header.startsWith("=== ") || !header.endsWith(" ===")) {
                i -= 1
                continue
            }
            // Recoger las lineas hacia abajo hasta encontrar la proxima
            // entrada (o el fin).
            val bodyStart = i + 1
            val bodyEnd = (i + 1 until lines.size).firstOrNull { j ->
                lines[j].startsWith("=== ") && lines[j].endsWith(" ===")
            } ?: lines.size

            val body = lines.subList(bodyStart, bodyEnd).joinToString("\n")
            val parsed = parseEntry(header, body)
            if (parsed != null) entries.add(parsed)
            i -= 1
        }
        return entries
    }

    /**
     * Header: "=== 2026-08-26 12:34:56.789 | FATAL | some context ==="
     * Body (lo que sigue hasta la siguiente entrada):
     *   <message>
     *   <stack trace multiple lines>
     *   --- (separador final)
     */
    private fun parseEntry(header: String, body: String): ErrorEntry? {
        val inner = header.removePrefix("=== ").removeSuffix(" ===")
        val parts = inner.split(" | ", limit = 3)
        if (parts.size < 2) return null
        val tsStr = parts[0]
        val tag = parts[1]
        val ctx = if (parts.size >= 3) parts[2] else ""

        val timestamp = runCatching {
            dateFormat.parse(tsStr)?.time ?: 0L
        }.getOrDefault(0L)

        // Body termina con "---" (separador). Quitarlo.
        val trimmed = body.trimEnd().removeSuffix("---").trimEnd()
        // Si hay stack trace, esta despues de la primera linea.
        val nl = trimmed.indexOf('\n')
        val message: String
        val stack: String?
        if (nl < 0) {
            message = trimmed
            stack = null
        } else {
            message = trimmed.substring(0, nl).trim()
            stack = trimmed.substring(nl + 1).trim().ifEmpty { null }
        }

        return ErrorEntry(
            timestamp = timestamp,
            tag = tag,
            message = message,
            stackTrace = stack,
            context = ctx,
        )
    }

    private fun formatEntry(
        ts: Long,
        tag: String,
        message: String,
        context: String,
        stack: String?,
    ): String {
        val sb = StringBuilder()
        sb.append("=== ").append(dateFormat.format(Date(ts)))
        sb.append(" | ").append(tag)
        if (context.isNotEmpty()) {
            sb.append(" | ").append(context)
        }
        sb.append(" ===\n")
        sb.append(message).append('\n')
        if (stack != null) {
            sb.append(stack).append('\n')
        }
        sb.append("---\n")
        return sb.toString()
    }

    private fun stackToString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
