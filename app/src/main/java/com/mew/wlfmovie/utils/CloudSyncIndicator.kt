package com.mew.wlfmovie.utils

import android.view.View
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * WLFMOVIE V4: Indicador de sync (nube).
 *
 * Muestra un texto pequeño con el tiempo relativo desde la última sincronización.
 * Respeta la visibilidad controlada por el activity (no sobrescribe GONE).
 */
object CloudSyncIndicator {

    private var indicatorView: TextView? = null
    private var updateJob: Job? = null

    fun attach(textView: TextView) {
        indicatorView = textView
        update()
        startAutoUpdate()
    }

    /**
     * Actualiza el texto del indicador.
     * WLFMOVIE: Solo cambia el texto, NO cambia la visibilidad.
     * El activity controla si el indicador debe verse o no (GONE/VISIBLE)
     * según el destino de navegación. Si el activity lo puso GONE,
     * update() no lo vuelve a mostrar.
     */
    fun update() {
        val view = indicatorView ?: return
        val context = view.context

        CoroutineScope(Dispatchers.Main).launch {
            val session = AccountManager.getSession(context)
            if (session == null) {
                // Solo ocultar si no hay sesión, no mostrar si la hay
                // (el activity decide si mostrar según el destino)
                view.visibility = View.GONE
                return@launch
            }

            val lastSync = AccountManager.getLastSync(context)
            view.text = if (lastSync.isNullOrBlank()) {
                "☁ Sin sync"
            } else {
                "☁ ${SyncManager.formatRelativeSync(lastSync)}"
            }
            // NO forzar VISIBLE aquí — el activity lo controla.
        }
    }

    private fun startAutoUpdate() {
        updateJob?.cancel()
        updateJob = CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                delay(60_000)
                update()
            }
        }
    }

    fun detach() {
        updateJob?.cancel()
        updateJob = null
        indicatorView = null
    }
}
