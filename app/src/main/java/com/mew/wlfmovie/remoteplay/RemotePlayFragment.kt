package com.mew.wlfmovie.remoteplay

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.mew.wlfmovie.R
import com.mew.wlfmovie.models.Video
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pantalla "Reproduciendo en navegador" — se abre cuando el PC conecta.
 *
 * - Muestra título, subtítulo, tiempos, barra de progreso (no interactiva)
 * - Botones play/pause + stop con iconos
 * - Back → stop + volver a detalles (NO al player)
 * - Si el PC se desconecta → vuelve al diálogo de espera en el player
 *
 * El sync de posición cada 10s lo maneja RemotePlayController (no este Fragment),
 * así que sigue funcionando aunque este Fragment se destruya.
 */
class RemotePlayFragment : Fragment() {

    companion object {
        private const val TAG = "WlfMovie-RemoteFrag"
    }

    private var stateJob: Job? = null
    private var positionJob: Job? = null
    private var videoType: Video.Type? = null
    private var backPressedOnce = false
    private var backPressedToast: android.widget.Toast? = null

    @SuppressLint("InflateParams")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_remote_play, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Obtener el videoType desde RemotePlayState (lo setea el Controller al iniciar)
        videoType = RemotePlayState.currentVideoType

        setupViews(view)
        observeState()
        observePosition()

        // Llenar datos iniciales
        updateUi()

        // Intercept back button — doble back para salir (evitar back accidental)
        // Primer back: muestra toast "Presioná salir otra vez para detener el cast"
        // Segundo back (dentro de 2s): detiene el cast y vuelve a detalles
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (backPressedOnce) {
                    backPressedToast?.cancel()
                    stopAndGoToDetails()
                } else {
                    backPressedOnce = true
                    backPressedToast = android.widget.Toast.makeText(
                        requireContext(),
                        "Presioná salir otra vez para detener el cast",
                        android.widget.Toast.LENGTH_SHORT
                    )
                    backPressedToast?.show()
                    viewLifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(2000)
                        backPressedOnce = false
                    }
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    }

    private fun setupViews(view: View) {
        val btnPlayPause = view.findViewById<ImageButton>(R.id.btn_remote_play_pause)
        val btnStop = view.findViewById<ImageButton>(R.id.btn_remote_stop)

        btnPlayPause.setOnClickListener {
            if (RemotePlayState.remoteIsPlaying.value) {
                RemotePlayController.sendPause()
            } else {
                RemotePlayController.sendPlay()
            }
        }

        btnStop.setOnClickListener {
            stopAndGoToDetails()
        }
    }

    /**
     * Observa el estado de conexión. Si vuelve a WAITING (PC se desconectó),
     * cierra este Fragment y vuelve al player (que mostrará el diálogo de espera).
     */
    private fun observeState() {
        stateJob = lifecycleScope.launch {
            // Dar un pequeño delay para evitar race conditions con onDestroyView del player
            kotlinx.coroutines.delay(100)
            RemotePlayState.connectionState.collect { state ->
                Log.i(TAG, "state=$state")
                when (state) {
                    RemotePlayState.ConnectionState.WAITING -> {
                        // El PC se desconectó — volver al player
                        goBackToPlayer()
                    }
                    RemotePlayState.ConnectionState.IDLE,
                    RemotePlayState.ConnectionState.STOPPING -> {
                        // El cast se detuvo — volver a detalles
                        goToDetails()
                    }
                    else -> {
                        // CONNECTED, PLAYING — actualizar UI
                        updateUi()
                    }
                }
            }
        }
    }

    /**
     * Observa la posición Y el estado isPlaying para actualizar la barra de progreso
     * y el icono del botón play/pause en tiempo real.
     */
    private fun observePosition() {
        positionJob = lifecycleScope.launch {
            var lastPosition = -1L
            var lastIsPlaying = false
            // Observar isPlaying para que el botón cambie instantáneamente cuando
            // el user toca play/pause (sin esperar al próximo reporte de posición).
            launch {
                RemotePlayState.remoteIsPlaying.collect { isPlaying ->
                    if (isPlaying == lastIsPlaying) return@collect
                    lastIsPlaying = isPlaying
                    updatePlayPauseIcon()
                }
            }
            RemotePlayState.remotePosition.collect { position ->
                if (position == lastPosition) return@collect
                lastPosition = position
                updateProgress(position)
            }
        }
    }

    /**
     * Actualiza solo el icono del botón play/pause.
     */
    private fun updatePlayPauseIcon() {
        val view = view ?: return
        val btnPlayPause = view.findViewById<ImageButton>(R.id.btn_remote_play_pause) ?: return
        val isPlaying = RemotePlayState.remoteIsPlaying.value
        btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.wlf_ic_pause else R.drawable.wlf_ic_play
        )
    }

    private fun updateUi() {
        val view = view ?: return

        val tvTitle = view.findViewById<TextView>(R.id.tv_remote_title)
        val tvSubtitle = view.findViewById<TextView>(R.id.tv_remote_subtitle)
        val btnPlayPause = view.findViewById<ImageButton>(R.id.btn_remote_play_pause)

        val vt = videoType
        when (vt) {
            is Video.Type.Movie -> {
                tvTitle.text = vt.title
                tvSubtitle.visibility = View.GONE
            }
            is Video.Type.Episode -> {
                tvTitle.text = vt.tvShow.title
                val sub = "S${vt.season.number} E${vt.number}" +
                    (vt.title?.let { " · $it" } ?: "")
                tvSubtitle.text = sub
                tvSubtitle.visibility = View.VISIBLE
            }
            null -> {
                tvTitle.text = "Reproduciendo en navegador"
                tvSubtitle.visibility = View.GONE
            }
        }

        val isPlaying = RemotePlayState.remoteIsPlaying.value
        btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.wlf_ic_pause else R.drawable.wlf_ic_play
        )

        // Actualizar progreso inicial
        updateProgress(RemotePlayState.remotePosition.value)
    }

    private fun updateProgress(position: Long) {
        val view = view ?: return
        val tvCurrentTime = view.findViewById<TextView>(R.id.tv_remote_current_time)
        val tvDuration = view.findViewById<TextView>(R.id.tv_remote_duration)
        val pbProgress = view.findViewById<ProgressBar>(R.id.pb_remote_progress)
        val btnPlayPause = view.findViewById<ImageButton>(R.id.btn_remote_play_pause)

        val duration = RemotePlayState.remoteDuration.value
        tvCurrentTime.text = formatMillis(position)
        tvDuration.text = formatMillis(duration)
        if (duration > 0) {
            val pct = ((position.toFloat() / duration.toFloat()) * 1000).toInt().coerceIn(0, 1000)
            pbProgress.progress = pct
        }

        val isPlaying = RemotePlayState.remoteIsPlaying.value
        btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.wlf_ic_pause else R.drawable.wlf_ic_play
        )
    }

    /**
     * Detiene el cast y navega a la pantalla de detalles.
     * Limpia el player del backstack para que back en detalles vaya al home.
     */
    private fun stopAndGoToDetails() {
        // Cancelar jobs antes de navegar
        stateJob?.cancel()
        positionJob?.cancel()
        // Detener el cast (el Controller guarda la última posición)
        lifecycleScope.launch(Dispatchers.IO) {
            RemotePlayController.stop(requireContext())
            withContext(Dispatchers.Main) {
                goToDetails()
            }
        }
    }

    /**
     * Vuelve al player (cuando el PC se desconecta y vuelve a WAITING).
     */
    private fun goBackToPlayer() {
        stateJob?.cancel()
        positionJob?.cancel()
        try {
            findNavController().popBackStack()
        } catch (e: Exception) {
            Log.e(TAG, "goBackToPlayer: error", e)
        }
    }

    /**
     * Navega a la pantalla de detalles (Movie o TvShow).
     * Limpia el player Y este Fragment del backstack.
     */
    private fun goToDetails() {
        try {
            val vt = videoType ?: return
            val navController = findNavController()

            // Limpiar el player y este fragment del backstack
            val navOptions = androidx.navigation.NavOptions.Builder()
                .setPopUpTo(com.mew.wlfmovie.R.id.player, true)
                .build()

            when (vt) {
                is Video.Type.Movie -> {
                    val bundle = Bundle().apply {
                        putString("id", vt.id)
                    }
                    navController.navigate(R.id.movie, bundle, navOptions)
                }
                is Video.Type.Episode -> {
                    val bundle = Bundle().apply {
                        putString("id", vt.tvShow.id)
                        putString("poster", vt.tvShow.poster)
                        putString("banner", vt.tvShow.banner)
                    }
                    navController.navigate(R.id.tv_show, bundle, navOptions)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "goToDetails: error", e)
            try { findNavController().navigateUp() } catch (_: Exception) {}
        }
    }

    private fun formatMillis(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", m, s)
        }
    }

    override fun onDestroyView() {
        stateJob?.cancel()
        positionJob?.cancel()
        super.onDestroyView()
    }
}
