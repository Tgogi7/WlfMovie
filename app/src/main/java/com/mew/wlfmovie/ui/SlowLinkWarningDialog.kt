package com.mew.wlfmovie.ui

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mew.wlfmovie.R

/**
 * Dialogo "Slow link" que aparece cuando una pelicula no tiene enlace rapido (MP4 directo)
 * y solo esta disponible por HLS (que estara en una proxima actualizacion).
 *
 * Botones:
 *  - "Cerrar": solo cierra el dialogo.
 *  - "Ver online": ejecuta [onWatchOnline] (que normalmente lanza el reproductor online).
 */
class SlowLinkWarningDialog private constructor(
    private val context: Context,
    private val onWatchOnline: () -> Unit,
    private val onClose: () -> Unit,
) {

    private var dialog: android.app.Dialog? = null

    fun show() {
        dialog = android.app.AlertDialog.Builder(context)
            .setTitle(R.string.slow_link_warning_title)
            .setMessage(R.string.slow_link_warning_body)
            .setPositiveButton(R.string.slow_link_warning_watch_online) { _, _ ->
                onWatchOnline()
            }
            .setNegativeButton(R.string.slow_link_warning_close) { _, _ ->
                onClose()
            }
            .setOnCancelListener { onClose() }
            .show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    companion object {
        fun show(
            context: Context,
            onWatchOnline: () -> Unit,
            onClose: () -> Unit = {},
        ): SlowLinkWarningDialog {
            val dialog = SlowLinkWarningDialog(context, onWatchOnline, onClose)
            dialog.show()
            return dialog
        }
    }
}
