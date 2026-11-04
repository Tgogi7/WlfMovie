package com.mew.wlfmovie.utils

import com.mew.wlfmovie.models.Video

/**
 * Singleton puente para pasar servers de YetiPelis pre-resueltos al PlayerViewModel.
 *
 * WLFMOVIE YETI v8 — DOS modos de uso:
 *
 * 1. MODO PRE-CARGADO (v6/v7): el botón YP espera en la pantalla de detalles
 *    a que searchServers() devuelva todos los servers, y luego navega al
 *    reproductor. Desventaja: el usuario ve un Toast "Buscando..." durante
 *    20+s sin feedback visual.
 *
 * 2. MODO FLAG (v8): el botón YP navega INMEDIATAMENTE al reproductor (como
 *    el botón "Reproducir" normal) y setea [ypRequested] = true. El
 *    PlayerViewModel.getServers() detecta el flag y en vez de usar el
 *    provider actual (TMDb, Gnula, etc.) llama directamente a
 *    YetiPelisProvider.searchServers(). El usuario ve el spinner morado-
 *    fucsia del reproductor mientras busca — mismo UX que el botón normal.
 *
 * El flag se limpia después de consumirlo (one-shot).
 *
 * Flujo del MODO FLAG:
 * 1. MovieViewHolder/EpisodeViewHolder toca botón YP
 * 2. Setea YpDirectPlay.ypRequested = true (sin esperar nada)
 * 3. Navega al PlayerFragment normalmente
 * 4. PlayerViewModel.getServers() lee consumeYpRequest() → true
 * 5. Si true: llama a YetiPelisProvider.searchServers() y emite servers
 * 6. Si false: usa el provider actual (comportamiento normal)
 *
 * [pendingServers] sigue existiendo para compatibilidad con el modo
 * pre-cargado (en caso de que se use en algún sitio).
 */
object YpDirectPlay {

    /** Flag "el usuario quiere YP para esta reproducción". One-shot. */
    @Volatile
    private var ypRequested: Boolean = false

    /** WLFMOVIE YETI v9: Guarda si el último intento fue YP o normal.
     *  Lo usa el overlay "sin servidores" para mostrar el botón correcto
     *  ("Intentar Opción 2" o "Intentar normal"). NO es one-shot — se mantiene
     *  hasta la próxima llamada a requestYp() o clearYpRequest(). */
    @Volatile
    private var lastAttemptWasYp: Boolean = false

    /** Servers pre-cargado (modo v6/v7). One-shot. */
    @Volatile
    private var pending: List<Video.Server>? = null

    fun requestYp() {
        ypRequested = true
        lastAttemptWasYp = true
    }

    /** WLFMOVIE YETI v9: Marca que el último intento fue normal (no YP). */
    fun markNormalAttempt() {
        lastAttemptWasYp = false
    }

    /** WLFMOVIE YETI v9: Devuelve true si el último intento fue YP. */
    fun wasLastAttemptYp(): Boolean = lastAttemptWasYp

    // WLFMOVIE YETI v9: Guardar/cargar el modo usado por video ID.
    // Cuando el usuario reproduce un video con YP, se guarda. La próxima vez
    // que entre (ej: desde Continuar Viendo), se usa el mismo modo.
    private val ypModePrefs by lazy {
        try {
            com.mew.wlfmovie.StreamFlixApp.instance.getSharedPreferences("yp_mode_per_video", android.content.Context.MODE_PRIVATE)
        } catch (_: Exception) { null }
    }

    /** Guarda que este video fue reproducido con YP (o normal). */
    fun saveModeForVideo(videoId: String, isYp: Boolean) {
        try {
            ypModePrefs?.edit()?.putBoolean("yp_$videoId", isYp)?.apply()
        } catch (_: Exception) {}
    }

    /** Devuelve true si este video fue reproducido con YP la última vez. */
    fun wasLastModeYpForVideo(videoId: String): Boolean {
        return try {
            ypModePrefs?.getBoolean("yp_$videoId", false) ?: false
        } catch (_: Exception) { false }
    }

    /**
     * Devuelve true si el flag YP está activo Y lo limpia (one-shot).
     * Si true, el PlayerViewModel debe llamar a YetiPelisProvider.
     */
    fun consumeYpRequest(): Boolean {
        val r = ypRequested
        ypRequested = false
        return r
    }

    /**
     * Guarda servers pendientes (modo pre-cargado) para que el
     * PlayerViewModel los consuma.
     */
    fun setPendingServers(servers: List<Video.Server>) {
        pending = servers
    }

    /**
     * Devuelve los servers pendientes Y los limpia (one-shot).
     * Retorna null si no hay nada pendiente.
     */
    fun consumePendingServers(): List<Video.Server>? {
        val servers = pending
        pending = null
        return servers
    }
}
