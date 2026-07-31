package com.mew.wlfmovie.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WLFMOVIE: Descargador de APK con progreso.
 *
 * Descarga el APK desde la URL del JSON y lo instala.
 * Expone el progreso via StateFlow para que la UI lo muestre.
 */
class ApkDownloader(private val context: Context) {

    companion object {
        private const val TAG = "WlfMovie-ApkDownloader"
    }

    private val _progress = MutableStateFlow(0) // 0-100
    val progress: StateFlow<Int> = _progress

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    sealed class Status {
        data object Idle : Status()
        data object Downloading : Status()
        data class Error(val message: String) : Status()
        data class Ready(val file: File) : Status()
    }

    /**
     * Descarga el APK desde la URL. Actualiza el progreso via StateFlow.
     * Al terminar, llama a installApk().
     */
    suspend fun downloadAndInstall(url: String) = withContext(Dispatchers.IO) {
        try {
            _status.value = Status.Downloading
            _progress.value = 0

            // WLFMOVIE: OkHttp sigue redirects por defecto, pero lo aseguramos.
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "WlfMovie-Update")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                _status.value = Status.Error("Error HTTP ${response.code}")
                return@withContext
            }

            val contentLength = response.body?.contentLength() ?: -1L
            Log.i(TAG, "Descargando APK: $contentLength bytes desde $url")

            // WLFMOVIE: Usar externalCacheDir para mejor compatibilidad con FileProvider.
            val apkFile = File(context.externalCacheDir ?: context.cacheDir, "wlfmovie_update.apk")

            // Si ya existe un archivo viejo, borrarlo
            if (apkFile.exists()) {
                apkFile.delete()
            }

            response.body?.byteStream()?.use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (contentLength > 0) {
                            val percent = ((totalRead * 100) / contentLength).toInt()
                            _progress.value = percent.coerceIn(0, 100)
                        }
                    }
                    output.flush()
                }
            }

            // WLFMOVIE: Verificar que el archivo se descargó correctamente.
            val fileSize = apkFile.length()
            Log.i(TAG, "APK descargado: $fileSize bytes en ${apkFile.absolutePath}")

            if (fileSize < 1000) {
                _status.value = Status.Error("El archivo descargado es demasiado pequeño ($fileSize bytes)")
                apkFile.delete()
                return@withContext
            }

            if (contentLength > 0 && fileSize != contentLength) {
                Log.w(TAG, "Tamaño no coincide: esperado=$contentLength, actual=$fileSize")
                // Continuamos de todos modos — a veces el Content-Length es incorrecto
            }

            _progress.value = 100
            _status.value = Status.Ready(apkFile)

            // Instalar
            installApk(apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error descargando APK: ${e.message}", e)
            _status.value = Status.Error(e.message ?: "Error desconocido")
        }
    }

    /**
     * Abre el instalador de Android con el APK descargado.
     */
    private fun installApk(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            Log.i(TAG, "Abriendo instalador con URI: $uri")

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error instalando APK: ${e.message}", e)
            _status.value = Status.Error("Error al instalar: ${e.message}")
        }
    }

    /**
     * Abre la URL en el navegador (descarga externa).
     */
    fun openExternalDownload(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo navegador: ${e.message}")
        }
    }
}
