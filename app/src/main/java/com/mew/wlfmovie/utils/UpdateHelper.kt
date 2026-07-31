package com.mew.wlfmovie.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import com.mew.wlfmovie.activities.update.UpdateRequiredActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WLFMOVIE V3: Helper para verificar actualizaciones al arranque.
 *
 * Llamar desde MainMobileActivity.onCreate() y MainTvActivity.onCreate().
 * Si la versión instalada es menor que la mínima del JSON, bloquea la app
 * abriendo UpdateRequiredActivity.
 */
object UpdateHelper {

    private const val TAG = "WlfMovie-UpdateHelper"

    /**
     * Verifica si hay una actualización requerida.
     * Si la hay, abre UpdateRequiredActivity y devuelve true.
     * Si no hay o hay error, devuelve false (la app continúa normal).
     */
    suspend fun checkAndBlockIfRequired(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Verificando actualizaciones...")
            val updateInfo = UpdateChecker.check()

            if (updateInfo == null) {
                Log.i(TAG, "No se pudo verificar (sin conexión o error). Continuando.")
                return@withContext false
            }

            if (updateInfo.isUpdateRequired) {
                Log.w(TAG, "Actualización requerida! Bloqueando app.")
                val intent = Intent(context, UpdateRequiredActivity::class.java).apply {
                    putExtra(UpdateRequiredActivity.EXTRA_CHANGELOG, updateInfo.changelog)
                    putExtra(UpdateRequiredActivity.EXTRA_APK_URL, updateInfo.apkUrl)
                    putExtra(UpdateRequiredActivity.EXTRA_LATEST_VERSION, updateInfo.latestVersion)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                }
                context.startActivity(intent)
                return@withContext true
            }

            Log.i(TAG, "App actualizada. Continuando normal.")
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Error verificando update: ${e.message}")
            return@withContext false
        }
    }
}
