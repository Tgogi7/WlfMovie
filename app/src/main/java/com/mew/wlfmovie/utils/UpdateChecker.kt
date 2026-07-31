package com.mew.wlfmovie.utils

import android.util.Log
import com.mew.wlfmovie.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/**
 * WLFMOVIE: Sistema de actualizaciones V3.
 *
 * Descarga update.json desde GitHub y compara la versión instalada
 * con la versión mínima requerida. Si la instalada es menor, bloquea la app.
 *
 * El JSON tiene este formato:
 * {
 *   "latestVersion": "2.100",
 *   "minVersion": "2.100",
 *   "apkUrl": "https://...",
 *   "changelog": "## Cambios..."
 * }
 *
 * Las versiones son strings numéricos comparables como BigDecimal:
 * "2.000" < "2.001" < "2.100" < "3.000"
 */
object UpdateChecker {

    private const val TAG = "WlfMovie-Update"
    private const val JSON_URL = "https://raw.githubusercontent.com/Tgogi7/WlfMovie/main/update.json"

    data class UpdateInfo(
        val latestVersion: String,
        val minVersion: String,
        val apkUrl: String,
        val changelog: String,
        val isUpdateRequired: Boolean
    )

    /**
     * Descarga el JSON y compara versiones.
     * Returns null si no se pudo descargar (error de red, etc).
     */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(JSON_URL)
                .header("Cache-Control", "no-cache")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Error descargando JSON: ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val latestVersion = json.optString("latestVersion", "0")
            val minVersion = json.optString("minVersion", "0")
            val apkUrl = json.optString("apkUrl", "")
            val changelog = json.optString("changelog", "")

            val installedVersion = BuildConfig.VERSION_NAME
            val isUpdateRequired = compareVersions(installedVersion, minVersion) < 0

            Log.i(TAG, "Instalada: $installedVersion, Min: $minVersion, Requiere update: $isUpdateRequired")

            UpdateInfo(
                latestVersion = latestVersion,
                minVersion = minVersion,
                apkUrl = apkUrl,
                changelog = changelog,
                isUpdateRequired = isUpdateRequired
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error checking update: ${e.message}")
            null
        }
    }

    /**
     * Compara dos versiones string numéricas.
     * Returns: negativo si v1 < v2, 0 si iguales, positivo si v1 > v2.
     * Ej: "2.000" < "2.001" → negativo
     */
    private fun compareVersions(v1: String, v2: String): Int {
        return try {
            BigDecimal(v1).compareTo(BigDecimal(v2))
        } catch (e: Exception) {
            // Fallback: comparar como strings
            v1.compareTo(v2)
        }
    }
}
