package com.mew.wlfmovie.fragments.settings

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.mew.wlfmovie.BuildConfig
import com.mew.wlfmovie.R
import com.mew.wlfmovie.databinding.FragmentSettingsMobileBinding
import com.mew.wlfmovie.utils.AccountManager
import com.mew.wlfmovie.utils.SyncManager
import com.mew.wlfmovie.utils.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsMobileFragment : Fragment() {

    private var _binding: FragmentSettingsMobileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Glide.with(this).load(R.drawable.ic_wlfmovie_logo).into(binding.ivSettingsLogo)
        binding.tvSettingsVersion.text = "Versión ${BuildConfig.VERSION_NAME}"

        binding.btnSettingsBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnSettingsLogin.setOnClickListener {
            showAuthDialog(isLogin = true)
        }

        binding.btnSettingsSync.setOnClickListener {
            performSync()
        }

        binding.btnSettingsLogout.setOnClickListener {
            AccountManager.clearSession(requireContext())
            updateUI()
            Toast.makeText(requireContext(), "Sesión cerrada", Toast.LENGTH_SHORT).show()
        }

        binding.btnSettingsTerms.setOnClickListener {
            showTermsDialog()
        }

        updateUI()
    }

    private fun updateUI() {
        val session = AccountManager.getSession(requireContext())
        if (session != null) {
            binding.tvSettingsUsername.text = session.username
            binding.btnSettingsLogin.visibility = View.GONE
            binding.btnSettingsSync.visibility = View.VISIBLE
            binding.btnSettingsLogout.visibility = View.VISIBLE

            val lastSync = AccountManager.getLastSync(requireContext())
            if (lastSync != null) {
                binding.tvSettingsLastSync.visibility = View.VISIBLE
                binding.tvSettingsLastSync.text = "Última sync: ${com.mew.wlfmovie.utils.SyncManager.formatLastSync(lastSync)}"
            } else {
                binding.tvSettingsLastSync.visibility = View.GONE
            }
        } else {
            binding.tvSettingsUsername.text = "Sin usuario"
            binding.btnSettingsLogin.visibility = View.VISIBLE
            binding.btnSettingsSync.visibility = View.GONE
            binding.btnSettingsLogout.visibility = View.GONE
            binding.tvSettingsLastSync.visibility = View.GONE
        }
    }

    private fun showAuthDialog(isLogin: Boolean) {
        val dialog = Dialog(requireContext())
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_auth, null)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_auth_title)
        val etEmail = dialogView.findViewById<EditText>(R.id.et_auth_email)
        val etUsername = dialogView.findViewById<EditText>(R.id.et_auth_username)
        val etPassword = dialogView.findViewById<EditText>(R.id.et_auth_password)
        val btnAction = dialogView.findViewById<TextView>(R.id.btn_auth_action)
        val tvError = dialogView.findViewById<TextView>(R.id.tv_auth_error)
        val tvToggle = dialogView.findViewById<TextView>(R.id.tv_auth_toggle)

        var currentIsLogin = isLogin
        fun updateDialogMode(loginMode: Boolean) {
            currentIsLogin = loginMode
            if (loginMode) {
                tvTitle.text = "Iniciar sesión"
                btnAction.text = "Iniciar sesión"
                etUsername.visibility = View.GONE
                tvToggle.text = "¿No tienes cuenta? Regístrate"
            } else {
                tvTitle.text = "Registrarse"
                btnAction.text = "Registrarse"
                etUsername.visibility = View.VISIBLE
                tvToggle.text = "¿Ya tienes cuenta? Inicia sesión"
            }
            tvError.visibility = View.GONE
        }
        updateDialogMode(isLogin)

        tvToggle.setOnClickListener {
            updateDialogMode(!currentIsLogin)
        }

        btnAction.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val username = etUsername.text.toString().trim()

            tvError.visibility = View.GONE

            if (email.isBlank() || password.isBlank()) {
                tvError.text = "Completa todos los campos"
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            if (!currentIsLogin && username.isBlank()) {
                tvError.text = "Elige un nombre de usuario"
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            if (!AccountManager.isValidEmail(email)) {
                tvError.text = "Email no válido"
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            if (password.length < 6) {
                tvError.text = "La contraseña debe tener al menos 6 caracteres"
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            btnAction.text = "Procesando..."
            btnAction.isEnabled = false

            lifecycleScope.launch {
                val result = if (currentIsLogin) {
                    SupabaseClient.login(email, password)
                } else {
                    SupabaseClient.register(email, password, username)
                }

                when (result) {
                    is SupabaseClient.AuthResult.Success -> {
                        val user = result.user
                        AccountManager.saveSession(requireContext(), user.id, user.email, user.username)
                        if (currentIsLogin) {
                            SyncManager.download(requireContext(), clearFirst = true)
                        } else {
                            SyncManager.upload(requireContext())
                        }
                        withContext(Dispatchers.Main) {
                            dialog.dismiss()
                            updateUI()
                            Toast.makeText(requireContext(), "Bienvenido, ${user.username}!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    is SupabaseClient.AuthResult.Error -> {
                        withContext(Dispatchers.Main) {
                            tvError.text = result.message
                            tvError.visibility = View.VISIBLE
                            btnAction.text = if (currentIsLogin) "Iniciar sesión" else "Registrarse"
                            btnAction.isEnabled = true
                        }
                    }
                }
            }
        }

        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun performSync() {
        binding.pbSettingsLoading.visibility = View.VISIBLE
        binding.btnSettingsSync.isEnabled = false
        binding.btnSettingsSync.text = "Sincronizando..."

        lifecycleScope.launch {
            val uploadOk = SyncManager.upload(requireContext())
            val downloadOk = SyncManager.download(requireContext(), clearFirst = false)

            withContext(Dispatchers.Main) {
                binding.pbSettingsLoading.visibility = View.GONE
                binding.btnSettingsSync.isEnabled = true
                binding.btnSettingsSync.text = "Sincronizar ahora"

                if (uploadOk && downloadOk) {
                    Toast.makeText(requireContext(), "Sincronización completada", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Error en la sincronización", Toast.LENGTH_SHORT).show()
                }
                updateUI()
            }
        }
    }

    private fun showTermsDialog() {
        val dialog = Dialog(requireContext())
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.wlf_dialog_list, null)

        dialogView.findViewById<TextView>(R.id.tv_dialog_title).text = "Términos y condiciones"

        val container = dialogView.findViewById<android.widget.LinearLayout>(R.id.ll_dialog_items)
        val termsText = TextView(requireContext()).apply {
            text = TERMS_AND_CONDITIONS
            textSize = 13f
            setTextColor(0xFFB8B8D1.toInt())
            setPadding(16, 24, 16, 24)
            setLineSpacing(4f, 1f)
        }
        container.addView(termsText)

        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TERMS_AND_CONDITIONS = """WlfMovie - Términos y Condiciones

Última actualización: 31 de julio de 2026

1. ACEPTACIÓN DE TÉRMINOS
Al usar WlfMovie, aceptas estos términos. Si no estás de acuerdo, no uses la app.

2. NATURALEZA DEL SERVICIO
WlfMovie es una aplicación de catálogo que utiliza la API de TMDB (The Movie Database) para mostrar información sobre películas y series. La app NO aloja, almacena ni distribuye ningún contenido audiovisual.

3. CONTENIDO DE TERCEROS
Los enlaces de streaming provienen de proveedores externos independientes. WlfMovie no tiene control sobre la disponibilidad, calidad o legalidad del contenido de estos proveedores. El usuario es responsable de verificar que el uso de dicho contenido cumpla con las leyes de su país.

4. SIN GARANTÍA
WlfMovie se proporciona "tal cual", sin garantía de ningún tipo. No garantizamos que la app funcione sin interrupciones o sea libre de errores.

5. DATOS DE USUARIO
- Los datos de sesión (email, username) se almacenan en Supabase.
- Las contraseñas se hashean con SHA-256 antes de enviarse.
- Los datos de sincronización (favoritos, pendientes) se encriptan con AES-256.
- No almacenamos información personal adicional.

6. USO EDUCATIVO
Esta aplicación está diseñada con fines educativos y de aprendizaje. El desarrollador no se responsabiliza del uso que se le dé.

7. PROPIEDAD INTELECTUAL
- El catálogo es proporcionado por TMDB.
- Los logos y marcas pertenecen a sus respectivos propietarios.
- WlfMovie no reclama derechos sobre el contenido mostrado.

8. LIMITACIÓN DE RESPONSABILIDAD
El desarrollador de WlfMovie no será responsable de ningún daño directo, indirecto, incidental o consecuente que pueda surgir del uso de la aplicación.

9. CAMBIOS
Nos reservamos el derecho de actualizar estos términos en cualquier momento.

10. CONTACTO
Para consultas sobre estos términos, contacta a través del repositorio de GitHub."""
    }
}
