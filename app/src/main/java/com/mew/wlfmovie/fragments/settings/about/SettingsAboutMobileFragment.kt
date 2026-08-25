package com.mew.wlfmovie.fragments.settings.about

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.mew.wlfmovie.BuildConfig
import com.mew.wlfmovie.R
import com.mew.wlfmovie.utils.DebugErrorCollector

class SettingsAboutMobileFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_about_mobile, rootKey)

        displaySettingsAbout()
        setupDebugErrors()
    }

    private fun displaySettingsAbout() {
        findPreference<Preference>("p_settings_about_version")?.apply {
            summary = getString(R.string.settings_about_version_name, BuildConfig.VERSION_NAME)
        }
    }

    private fun setupDebugErrors() {
        val debugPref = findPreference<Preference>("p_settings_about_debug_errors") ?: return
        // En release, ocultar. En debug, mostrar.
        if (!BuildConfig.DEBUG) {
            debugPref.isVisible = false
        }

        debugPref.setOnPreferenceClickListener {
            val errors = DebugErrorCollector.getErrors()
            if (errors.isEmpty()) {
                Toast.makeText(requireContext(), "No hay errores registrados", Toast.LENGTH_SHORT).show()
                return@setOnPreferenceClickListener true
            }

            val errorText = DebugErrorCollector.getErrorsAsText()

            // Mostrar diálogo con opción de compartir/copiar
            val message = "${DebugErrorCollector.count()} errores registrados.\n\n" +
                "Primeros 5 errores:\n\n" +
                errors.take(5).joinToString("\n---\n") { entry ->
                    "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date(entry.timestamp))}\n" +
                    "[${entry.tag}] ${entry.message}\n" +
                    if (entry.context.isNotEmpty()) "Context: ${entry.context}\n" else "" +
                    if (entry.stackTrace != null) "Stack: ${entry.stackTrace.take(200)}" else ""
                }

            AlertDialog.Builder(requireContext())
                .setTitle("Errores de debug (${DebugErrorCollector.count()})")
                .setMessage(message)
                .setPositiveButton("Cerrar") { _, _ -> }
                .setNegativeButton("Copiar todo") { _, _ ->
                    val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("WlfMovie Debug Errors", errorText)
                    )
                    Toast.makeText(requireContext(), "Errores copiados al portapapeles", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Compartir") { _, _ ->
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "WlfMovie Debug Errors")
                        putExtra(Intent.EXTRA_TEXT, errorText)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Compartir errores"))
                }
                .show()

            true
        }
    }
}