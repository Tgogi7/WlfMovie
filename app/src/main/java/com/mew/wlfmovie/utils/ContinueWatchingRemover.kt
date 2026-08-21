package com.mew.wlfmovie.utils

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.mew.wlfmovie.R
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.models.Episode
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.ui.UserDataNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * WLFMOVIE Update 5: Diálogo para quitar una peli/serie de "continue watching".
 *
 * - Muestra un diálogo bonito con el tema de la app (fondo oscuro, acentos fucsia)
 * - Pregunta: "¿Quitar [nombre] de continuar viendo?"
 * - Si el user acepta:
 *   - Para películas: resetea watchHistory, isWatched=false, saca de continue watching
 *   - Para series: resetea TODOS los episodios (watchHistory=null, isWatched=false),
 *     saca el ep de continue watching, pone tvShow.isWatching=false
 *   - Sube los cambios a la nube con SyncManager.autoUpload
 */
object ContinueWatchingRemover {

    private const val TAG = "WlfMovie-CWRemover"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Muestra el diálogo para quitar una PELÍCULA de continue watching.
     */
    fun showForMovie(context: Context, movie: Movie) {
        showMovieDialog(context, movie)
    }

    /**
     * Muestra el diálogo para quitar una SERIE (episodio) de continue watching.
     */
    fun showForEpisode(context: Context, episode: Episode) {
        showEpisodeDialog(context, episode)
    }

    private fun showMovieDialog(context: Context, movie: Movie) {
        val message = "¿Quitar \"${movie.title}\" de continuar viendo?\n\n" +
            "Se reseteará el progreso y la película volverá a estar como no vista."

        showDialog(context, "Quitar película", message) {
            scope.launch {
                removeMovie(context, movie)
            }
        }
    }

    private fun showEpisodeDialog(context: Context, episode: Episode) {
        val tvShowTitle = episode.tvShow?.title ?: "la serie"
        val message = "¿Quitar \"${tvShowTitle}\" de continuar viendo?\n\n" +
            "Se reseteará el progreso de TODOS los episodios y la serie volverá a estar como no vista."

        showDialog(context, "Quitar serie", message) {
            scope.launch {
                removeEpisode(context, episode)
            }
        }
    }

    private fun showDialog(
        context: Context,
        title: String,
        message: String,
        onConfirm: () -> Unit
    ) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_remove_continue_watching, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_title)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tv_dialog_message)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_cancel)
        val btnRemove = dialogView.findViewById<TextView>(R.id.btn_remove)

        tvTitle.text = title
        tvMessage.text = message

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnRemove.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }

        dialog.show()
    }

    /**
     * Resetea una película: watchHistory=null, isWatched=false, saca de continue watching.
     */
    private suspend fun removeMovie(context: Context, movie: Movie) {
        try {
            val database = AppDatabase.getInstance(context)
            val provider = UserPreferences.currentProvider ?: return

            val dbMovie = database.movieDao().getById(movie.id) ?: return
            dbMovie.isWatched = false
            dbMovie.watchedDate = null
            dbMovie.watchHistory = null
            database.movieDao().update(dbMovie)

            UserDataCache.removeMovieFromContinueWatching(context, provider, movie.id)
            UserDataNotifier.notifyChanged()
            SyncManager.autoUpload(context)

            android.util.Log.i(TAG, "removeMovie: ${movie.title} reseteada y quitada de continue watching")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "removeMovie: error", e)
        }
    }

    /**
     * Resetea TODOS los episodios de una serie + tvShow.isWatching=false.
     */
    private suspend fun removeEpisode(context: Context, episode: Episode) {
        try {
            val database = AppDatabase.getInstance(context)
            val provider = UserPreferences.currentProvider ?: return
            val tvShowId = episode.tvShow?.id ?: return

            // Resetear TODOS los episodios de la serie
            val allEpisodes = database.episodeDao().getByTvShowId(tvShowId)
            for (ep in allEpisodes) {
                if (ep.watchHistory != null || ep.isWatched) {
                    ep.watchHistory = null
                    ep.isWatched = false
                    ep.watchedDate = null
                    database.episodeDao().update(ep)
                }
            }

            // Sacar el ep actual de continue watching (y cualquier otro de la serie que estuviera)
            for (ep in allEpisodes) {
                UserDataCache.removeEpisodeFromContinueWatching(context, provider, ep.id)
            }

            // Marcar tvShow.isWatching = false
            val tvShow = database.tvShowDao().getById(tvShowId)
            if (tvShow != null) {
                val tvShowCopy = tvShow.copy().apply {
                    merge(tvShow)
                    isWatching = false
                }
                database.tvShowDao().save(tvShowCopy)
            }

            UserDataNotifier.notifyChanged()
            SyncManager.autoUpload(context)

            android.util.Log.i(TAG, "removeEpisode: ${episode.tvShow?.title} reseteada (todos los eps) y quitada de continue watching")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "removeEpisode: error", e)
        }
    }
}
