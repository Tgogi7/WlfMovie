package com.mew.wlfmovie.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mew.wlfmovie.R

/**
 * Helper para construir y actualizar la notificacion de progreso de descarga.
 *
 * Channel unico "downloads". Notification ID unico por movieId (hashCode), para
 * que cada pelicula tenga su propia notificacion persistente.
 */
object MovieDownloadNotifier {

    const val CHANNEL_ID = "downloads"
    const val EXTRA_MOVIE_ID = "extra_movie_id"
    const val EXTRA_CANCEL = "extra_cancel"

    fun notificationIdFor(movieId: String): Int = movieId.hashCode()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.download_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.download_notification_channel_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    /**
     * Construye la notificacion de descarga en progreso.
     * `contentIntent` puede ser null (no abre nada al tocar).
     * `cancelIntent` se enlaza al boton de cancelar (y a la accion de swipe si soporta).
     */
    fun buildProgressNotification(
        context: Context,
        movieId: String,
        title: String,
        percent: Int,
        downloadedHuman: String,
        totalHuman: String,
        isFinalizing: Boolean = false,
    ): NotificationCompat.Builder {
        ensureChannel(context)

        val cancelIntent = buildCancelIntent(context, movieId)

        // WLFMOVIE V6.3.3: en fase de guardado (concatenación local tras bajar
        // el 100% de los segmentos) el texto dice "Guardando X%" en vez de
        // "Descargando X%" — el usuario entiende que la app está salvando el
        // archivo, no que se reinició la descarga.
        val progressText = if (isFinalizing) {
            context.getString(
                R.string.download_notification_finalizing,
                percent,
                downloadedHuman,
                totalHuman,
            )
        } else {
            context.getString(
                R.string.download_notification_progress,
                percent,
                downloadedHuman,
                totalHuman,
            )
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.wlf_ic_download)
            .setContentTitle(title)
            .setContentText(progressText)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.wlf_ic_download,
                context.getString(R.string.download_notification_cancel),
                cancelIntent
            )
    }

    fun buildCompletedNotification(
        context: Context,
        title: String,
    ): NotificationCompat.Builder {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.wlf_ic_download)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.download_notification_completed, title))
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    fun buildFailedNotification(
        context: Context,
        title: String,
    ): NotificationCompat.Builder {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.wlf_ic_download)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.download_notification_failed, title))
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun buildCancelIntent(context: Context, movieId: String): PendingIntent {
        val intent = Intent(context, MovieDownloadService::class.java).apply {
            action = MovieDownloadService.ACTION_CANCEL
            putExtra(EXTRA_MOVIE_ID, movieId)
            putExtra(EXTRA_CANCEL, true)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        // WLFMOVIE: usar getForegroundService (API 26+) para que al pulsar el boton
        // de la notificacion se arranque el service como foreground (evita
        // IllegalStateException por background service start en Android 8+).
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, movieId.hashCode(), intent, flags)
        } else {
            PendingIntent.getService(context, movieId.hashCode(), intent, flags)
        }
    }

    fun notifyProgress(
        context: Context,
        movieId: String,
        title: String,
        percent: Int,
        downloadedHuman: String,
        totalHuman: String,
        isFinalizing: Boolean = false,
    ) {
        try {
            val notification = buildProgressNotification(
                context,
                movieId,
                title,
                percent,
                downloadedHuman,
                totalHuman,
                isFinalizing,
            ).build()
            NotificationManagerCompat.from(context)
                .notify(notificationIdFor(movieId), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied on Android 13+. Just skip silently:
            // the foreground service keeps running, only the notification icon is hidden.
        }
    }

    fun notifyCompleted(
        context: Context,
        movieId: String,
        title: String,
    ) {
        try {
            val notification = buildCompletedNotification(context, title).build()
            NotificationManagerCompat.from(context)
                .notify(notificationIdFor(movieId), notification)
        } catch (e: SecurityException) {
            // Same as above.
        }
    }

    fun notifyFailed(
        context: Context,
        movieId: String,
        title: String,
    ) {
        try {
            val notification = buildFailedNotification(context, title).build()
            NotificationManagerCompat.from(context)
                .notify(notificationIdFor(movieId), notification)
        } catch (e: SecurityException) {
            // Same as above.
        }
    }

    fun cancel(context: Context, movieId: String) {
        try {
            NotificationManagerCompat.from(context).cancel(notificationIdFor(movieId))
        } catch (e: SecurityException) {
            // ignore
        }
    }
}
