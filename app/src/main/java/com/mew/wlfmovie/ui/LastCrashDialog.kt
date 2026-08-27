package com.mew.wlfmovie.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mew.wlfmovie.R
import com.mew.wlfmovie.utils.CrashLogger

/**
 * WLFMOVIE V6.2: Dialogo que se muestra al abrir la app despues de un crash.
 *
 * Si [CrashLogger.hasUnreadCrash] retorna true, llamamos a [show] desde
 * `MainMobileActivity.onCreate()` y `MainTvActivity.onCreate()`.
 *
 * Botones:
 *  - "Copiar" (neutral): copia el stack trace completo del crash al portapapeles.
 *  - "Ver todos" (positive): ejecuta [onViewAll] (normalmente abre el dialogo
 *    completo del recopilador de errores).
 *  - "Cerrar" (negative): marca el crash como leido y cierra.
 *
 * El dialogo NO bloquea la app: el usuario puede cancelar con back y seguir
 * usando la app. Si lo cancela, el crash se marca como leido de todas formas
 * para no pincharle en cada arranque.
 */
class LastCrashDialog private constructor(
    private val context: Context,
    private val onViewAll: () -> Unit,
    private val onDismiss: () -> Unit,
) {

    private var dialog: android.app.Dialog? = null

    fun show() {
        val crash = CrashLogger.getLastFatalCrash()
        if (crash == null) {
            // No hay crash fatal — solo marca como leido y sal.
            CrashLogger.markCrashRead()
            onDismiss()
            return
        }

        val body = buildString {
            append(context.getString(R.string.last_crash_dialog_intro))
            append("\n\n")
            append("• ${crash.message.ifEmpty { "(sin mensaje)" }}\n")
            append("• Tag: ${crash.tag}\n")
            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(crash.timestamp))
            append("• Hora: $time\n")
            if (crash.context.isNotEmpty()) {
                append("• Contexto: ${crash.context}\n")
            }
            if (!crash.stackTrace.isNullOrBlank()) {
                append("\nStack trace (primeras 800 chars):\n")
                append(crash.stackTrace.take(800))
            }
        }

        dialog = android.app.AlertDialog.Builder(context)
            .setTitle(R.string.last_crash_dialog_title)
            .setMessage(body)
            .setPositiveButton(R.string.last_crash_dialog_view_all) { _, _ ->
                onViewAll()
                CrashLogger.markCrashRead()
            }
            .setNegativeButton(R.string.last_crash_dialog_close) { _, _ ->
                CrashLogger.markCrashRead()
                onDismiss()
            }
            .setNeutralButton(R.string.last_crash_dialog_copy) { _, _ ->
                val fullText = buildString {
                    append("=== WlfMovie Last Crash ===\n")
                    append("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(crash.timestamp))}\n")
                    append("Tag: ${crash.tag}\n")
                    append("Message: ${crash.message}\n")
                    if (crash.context.isNotEmpty()) append("Context: ${crash.context}\n")
                    append("\nStack:\n")
                    append(crash.stackTrace ?: "(no stack trace)")
                }
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("WlfMovie Crash", fullText))
                Toast.makeText(context, R.string.last_crash_dialog_copied, Toast.LENGTH_SHORT).show()
                // No marcamos como leido aqui — el usuario puede seguir copiando.
                // Se marca cuando cierra o abre ver-todos.
            }
            .setOnCancelListener {
                CrashLogger.markCrashRead()
                onDismiss()
            }
            .show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    companion object {
        fun show(
            context: Context,
            onViewAll: () -> Unit,
            onDismiss: () -> Unit = {},
        ): LastCrashDialog {
            val d = LastCrashDialog(context, onViewAll, onDismiss)
            d.show()
            return d
        }
    }
}
