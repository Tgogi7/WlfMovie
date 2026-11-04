package com.mew.wlfmovie.utils

import com.mew.wlfmovie.models.Video

/**
 * Singleton puente para pasar servers de YetiPelis pre-resueltos al PlayerViewModel.
 *
 * Flujo cuando el usuario toca "Reproducir con YP" en la pagina de detalles:
 * 1. MovieViewHolder llama a YetiPelisProvider.searchServers()
 * 2. Guarda el resultado aqui: YpDirectPlay.pendingServers = servers
 * 3. Navega al PlayerFragment normalmente (con videoType)
 * 4. PlayerViewModel.getServers() verifica YpDirectPlay.consumePendingServers()
 * 5. Si hay servers pendientes, los emite directamente (sin llamar al provider)
 * 6. El player auto-selecciona el primero y reproduce con DefaultHttpDataSource
 *    (porque el toggle yetiDirectPlayer esta ON y el server empieza con "YP ")
 *
 * consumePendingServers() limpia el campo despues de leerlo (one-shot).
 */
object YpDirectPlay {

    @Volatile
    private var pending: List<Video.Server>? = null

    /**
     * Guarda servers pendientes para que el PlayerViewModel los consuma.
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
