package com.mew.wlfmovie.utils

import android.content.Context
import android.os.Build
import android.util.Log
import com.mew.wlfmovie.BuildConfig
import java.io.PrintWriter
import java.io.StringWriter

/**
 * WLFMOVIE V6.2: Crash handler global.
 *
 * - Se instala desde [StreamFlixApp.onCreate] lo antes posible.
 * - Captura cualquier excepcion no atrapada en cualquier hilo.
 * - Escribe el stack + info de dispositivo en `CrashLogger` con tag "FATAL"
 *   para que persista en disco y pueda abrirse desde Settings.
 * - Marca la entrada como "no leida" para que el [com.mew.wlfmovie.ui.LastCrashDialog]
 *   se muestre al siguiente arranque.
 * - Despues delega en el handler por defecto de Android (para que el sistema
 *   muestre el dialogo "La app se ha cerrado" habitual y el proceso muera).
 *
 * IMPORTANTE: este handler se ejecuta en un contexto en el que el proceso
 * esta a punto de morir. Hacemos lo minimo indispensable (escribir en disco
 * + SharedPreferences) sin depender de hilos de background ni de servicios
 * que puedan ya no existir.
 */
object CrashHandler {

    private const val TAG = "WlfMovie-CrashHandler"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                handleUncaught(thread, throwable, context.applicationContext)
            } catch (meta: Throwable) {
                // Si el propio handler crashea, al menos dejamos rastro en logcat.
                Log.e(TAG, "El propio CrashHandler fallo: $meta")
            }
            // Siempre delegar en el handler anterior para que Android
            // cierre el proceso normalmente y muestre el dialogo del sistema.
            previous?.uncaughtException(thread, throwable)
        }
        installed = true
        Log.i(TAG, "CrashHandler instalado")
    }

    private fun handleUncaught(thread: Thread, throwable: Throwable, appContext: Context) {
        // WLFMOVIE V6.2.2: En release no persistimos nada — el handler aun
        // funciona (captura el throwable para logcat), pero no escribe en
        // disco ni marca el flag "unread". El usuario final no ve popups de
        // crash en builds oficiales.
        if (!BuildConfig.DEBUG) {
            Log.e(TAG, "Uncaught on ${thread.name}: ${throwable.message}", throwable)
            return
        }

        val deviceInfo = buildDeviceInfo()
        val threadInfo = "Thread: ${thread.name} (id=${thread.id})"
        val context = "$deviceInfo | $threadInfo | v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"

        // Stack trace completo
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        CrashLogger.logError(
            tag = "FATAL",
            message = "${throwable.javaClass.simpleName}: ${throwable.message ?: "(sin mensaje)"}",
            throwable = throwable,
            context = context,
        )
        CrashLogger.markFatalUnread()
    }

    private fun buildDeviceInfo(): String {
        return try {
            val manufacturer = Build.MANUFACTURER ?: "?"
            val model = Build.MODEL ?: "?"
            val sdk = Build.VERSION.SDK_INT
            val release = Build.VERSION.RELEASE ?: "?"
            "Device: $manufacturer $model | Android $release (API $sdk)"
        } catch (t: Throwable) {
            "Device: unknown"
        }
    }
}
