package com.mew.wlfmovie.utils

/**
 * WLFMOVIE V6.2: Facade de compatibilidad sobre [CrashLogger].
 *
 * Antes era un colector en memoria, solo debug, con limite de 200 entradas
 * y sin persistencia. Su comportamiento era insuficiente: cuando la app
 * crasheaba, todo el log moria con el proceso y el usuario no tenia forma
 * de saber por que.
 *
 * Ahora delega en [CrashLogger] que es:
 *  - persistente (archivo `filesDir/wlfmovie-errors.log`)
 *  - infinito (sin limite de entradas; solo trunca por tamano a ~8 MB)
 *  - activo en release
 *  - capaz de capturar crashes globales via [CrashHandler]
 *
 * Mantenemos la API publica para no romper llamadas existentes.
 */
object DebugErrorCollector {

    data class ErrorEntry(
        val timestamp: Long,
        val tag: String,
        val message: String,
        val stackTrace: String?,
        val context: String,
    )

    fun logError(tag: String, message: String, throwable: Throwable? = null, context: String = "") {
        CrashLogger.logError(tag = tag, message = message, throwable = throwable, context = context)
    }

    /** Alias con orden antiguo (context antes que throwable). Delegado via named args. */
    fun logError(tag: String, message: String, context: String, throwable: Throwable? = null) {
        CrashLogger.logError(tag = tag, message = message, throwable = throwable, context = context)
    }

    fun getErrors(): List<ErrorEntry> =
        CrashLogger.getErrors().map { ErrorEntry(it.timestamp, it.tag, it.message, it.stackTrace, it.context) }

    fun getErrorsAsText(): String = CrashLogger.getErrorsAsText()

    fun count(): Int = CrashLogger.count()

    fun clear() = CrashLogger.clear()
}
