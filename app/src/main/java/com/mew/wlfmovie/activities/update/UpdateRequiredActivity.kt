package com.mew.wlfmovie.activities.update

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.mew.wlfmovie.R
import com.mew.wlfmovie.utils.ApkDownloader
import com.mew.wlfmovie.utils.SimpleMarkdownParser
import kotlinx.coroutines.launch

/**
 * WLFMOVIE: Pantalla de actualización requerida (V3).
 *
 * Bloquea toda la app si la versión instalada es menor que la mínima.
 * Muestra el changelog en markdown + botones de descarga.
 *
 * Funciona tanto en mobile como en TV (botones grandes focusables).
 */
class UpdateRequiredActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANGELOG = "changelog"
        const val EXTRA_APK_URL = "apk_url"
        const val EXTRA_LATEST_VERSION = "latest_version"
    }

    private lateinit var apkDownloader: ApkDownloader
    private var apkUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fondo morado WlfMovie + ocultar system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_update_required)

        apkDownloader = ApkDownloader(this)
        apkUrl = intent.getStringExtra(EXTRA_APK_URL) ?: ""
        val changelog = intent.getStringExtra(EXTRA_CHANGELOG) ?: ""
        val latestVersion = intent.getStringExtra(EXTRA_LATEST_VERSION) ?: ""

        // Título
        findViewById<TextView>(R.id.tv_update_title).text = "Nueva versión disponible"

        // Versión
        findViewById<TextView>(R.id.tv_update_version).text = "Versión $latestVersion"

        // Changelog en markdown
        val tvChangelog = findViewById<TextView>(R.id.tv_update_changelog)
        tvChangelog.text = SimpleMarkdownParser.parse(changelog)

        // Botón Descargar e Instalar
        val btnDownload = findViewById<TextView>(R.id.btn_update_download)
        btnDownload.setOnClickListener {
            startDownload()
        }

        // Botón Descarga externa
        val btnExternal = findViewById<TextView>(R.id.btn_update_external)
        btnExternal.setOnClickListener {
            apkDownloader.openExternalDownload(apkUrl)
        }

        // ProgressBar
        val progressBar = findViewById<ProgressBar>(R.id.pb_update_download)
        val tvProgress = findViewById<TextView>(R.id.tv_update_progress)

        // Observar progreso de descarga
        lifecycleScope.launch {
            apkDownloader.progress.collect { progress ->
                progressBar.progress = progress
                tvProgress.text = "$progress%"
                if (progress > 0 && progress < 100) {
                    progressBar.visibility = View.VISIBLE
                    tvProgress.visibility = View.VISIBLE
                }
            }
        }

        // Observar status
        lifecycleScope.launch {
            apkDownloader.status.collect { status ->
                when (status) {
                    is ApkDownloader.Status.Downloading -> {
                        btnDownload.text = "Descargando..."
                        btnDownload.isEnabled = false
                    }
                    is ApkDownloader.Status.Error -> {
                        btnDownload.text = "Descargar e instalar"
                        btnDownload.isEnabled = true
                        progressBar.visibility = View.GONE
                        tvProgress.visibility = View.GONE
                        Toast.makeText(this@UpdateRequiredActivity, status.message, Toast.LENGTH_LONG).show()
                    }
                    is ApkDownloader.Status.Ready -> {
                        btnDownload.text = "Instalando..."
                    }
                    else -> {}
                }
            }
        }

        // Scroll al inicio
        findViewById<ScrollView>(R.id.sv_update).scrollTo(0, 0)
    }

    private fun startDownload() {
        if (apkUrl.isBlank()) {
            Toast.makeText(this, "URL de descarga no disponible", Toast.LENGTH_SHORT).show()
            return
        }

        // Verificar permiso de instalar apps desconocidas
        if (!canInstallPackages()) {
            Toast.makeText(this, "Activa el permiso de instalar apps desconocidas", Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                intent.data = Uri.parse("package:$packageName")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("WlfMovie-Update", "Error abriendo settings: ${e.message}")
            }
            return
        }

        // Descargar e instalar
        lifecycleScope.launch {
            apkDownloader.downloadAndInstall(apkUrl)
        }
    }

    private fun canInstallPackages(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // No permitir volver — la app está bloqueada
    }
}
