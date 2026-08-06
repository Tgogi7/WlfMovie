package com.mew.wlfmovie.remoteplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mew.wlfmovie.R

/**
 * Foreground Service para Remote Play.
 *
 * Solo se encarga de:
 * 1. Mantener la notificación foreground (para que Android no mate el proceso)
 * 2. Mantener un wakelock (para que la CPU siga cuando se apaga la pantalla)
 *
 * El server HTTP+WebSocket lo maneja RemotePlayController directamente.
 * El Service está "vivo" mientras el cast esté activo, y se detiene cuando
 * el user hace stop.
 */
class RemotePlayService : Service() {

    companion object {
        private const val TAG = "WlfMovie-RemoteService"
        private const val NOTIFICATION_ID = 5505
        private const val CHANNEL_ID = "wlfmovie_remote_play"

        const val ACTION_START = "com.mew.wlfmovie.remoteplay.START"
        const val ACTION_STOP = "com.mew.wlfmovie.remoteplay.STOP"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                startForegroundWithNotification()
                acquireWakeLock()
            }
            ACTION_STOP -> {
                releaseWakeLock()
                @Suppress("DEPRECATION")
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    // ===== Notification + Foreground =====

    private fun startForegroundWithNotification() {
        createNotificationChannel()
        val notification = buildNotification("Reproduciendo en PC")
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String): Notification {
        // Intent para abrir la app al tocar la notificación
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else null

        // Intent para detener el cast desde la notificación
        val stopIntent = Intent(this, RemotePlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WlfMovie — Reproduciendo en PC")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_cast_to_pc)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_cast_to_pc, "Detener", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reproducción en PC",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificación del servidor de Remote Play"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    // ===== WakeLock =====

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WlfMovie::RemotePlayServer"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L) // 10 horas máximo
        }
        Log.i(TAG, "acquireWakeLock: wakelock adquirido")
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            Log.i(TAG, "releaseWakeLock: wakelock liberado")
        } catch (e: Exception) {
            Log.e(TAG, "releaseWakeLock: error", e)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        releaseWakeLock()
        super.onDestroy()
    }
}
