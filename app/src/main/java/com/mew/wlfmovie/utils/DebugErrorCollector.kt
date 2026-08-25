package com.mew.wlfmovie.utils

import android.util.Log
import com.mew.wlfmovie.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * WLFMOVIE V6.1: Recopilador de errores para builds de debug.
 *
 * Captura errores que ocurren en segundo plano (providers, extractores, sync, etc.)
 * y los guarda en memoria con timestamp, stack trace y contexto.
 *
 * Solo activo en BuildConfig.DEBUG — en release no hace nada.
 *
 * Accesible desde Settings > About > "Errores de debug" (solo visible en debug).
 */
object DebugErrorCollector {

    private const val TAG = "WlfMovie-DebugErrors"
    private const val MAX_ERRORS = 200

    data class ErrorEntry(
        val timestamp: Long,
        val tag: String,
        val message: String,
        val stackTrace: String?,
        val context: String,
    )

    private val errors = ConcurrentLinkedDeque<ErrorEntry>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Registra un error. Solo funciona en debug.
     */
    fun logError(tag: String, message: String, throwable: Throwable? = null, context: String = "") {
        if (!BuildConfig.DEBUG) return

        val entry = ErrorEntry(
            timestamp = System.currentTimeMillis(),
            tag = tag,
            message = message,
            stackTrace = throwable?.stackTraceToString()?.take(2000),
            context = context,
        )
        errors.addFirst(entry)

        // Mantener solo los últimos MAX_ERRORS
        while (errors.size > MAX_ERRORS) {
            errors.pollLast()
        }

        Log.e(tag, message, throwable)
    }

    /**
     * Registra un error con contexto adicional (ej: qué provider, qué película).
     */
    fun logError(tag: String, message: String, context: String, throwable: Throwable? = null) {
        logError(tag, message, throwable, context)
    }

    /**
     * Devuelve todos los errores registrados (más reciente primero).
     */
    fun getErrors(): List<ErrorEntry> = errors.toList()

    /**
     * Devuelve los errores como texto formateado (para compartir/descargar).
     */
    fun getErrorsAsText(): String {
        if (errors.isEmpty()) return "No hay errores registrados."

        val sb = StringBuilder()
        sb.append("=== WlfMovie Debug Error Log ===\n")
        sb.append("Total: ${errors.size} errores\n")
        sb.append("Build: ${BuildConfig.VERSION_NAME}\n\n")

        for (entry in errors) {
            sb.append("--- ${dateFormat.format(Date(entry.timestamp))} ---\n")
            sb.append("Tag: ${entry.tag}\n")
            sb.append("Message: ${entry.message}\n")
            if (entry.context.isNotEmpty()) {
                sb.append("Context: ${entry.context}\n")
            }
            if (entry.stackTrace != null) {
                sb.append("Stack:\n${entry.stackTrace.take(500)}\n")
            }
            sb.append("\n")
        }

        return sb.toString()
    }

    /**
     * Limpia todos los errores.
     */
    fun clear() {
        errors.clear()
        Log.i(TAG, "Errores limpiados")
    }

    /**
     * Devuelve el número de errores registrados.
     */
    fun count(): Int = errors.size
}
