package com.mew.wlfmovie.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mew.wlfmovie.R
import com.mew.wlfmovie.models.Video

/**
 * WLFMOVIE V6.3.4: Diálogo de selección de SERVIDOR para descarga.
 *
 * Flujo (idea del usuario):
 *  1. El usuario pulsa "Descargar" → se buscan servers (~5s) y se guardan en
 *     la cache de servers de descarga de [com.mew.wlfmovie.download.MovieDownloadManager].
 *  2. Elige idioma (MovieDownloadLanguageDialog).
 *  3. Si ese idioma tiene MÁS de 1 servidor → este diálogo le deja elegir.
 *     Si tiene solo 1 → se descarga directo sin pasar por aquí.
 *  4. Si el server elegido falla (DMCA, stall, etc.), la app marca ese server
 *     como fallido SOLO para esta consulta y vuelve a mostrar este diálogo
 *     con los servers restantes (sin re-buscar, la lista sale de la cache).
 *     Así el usuario nunca se queda sin opciones.
 *
 * Cada fila muestra el nombre del server ("Opción 2 · Latino") y el DOMINIO
 * real del m3u8 resuelto ("cdn.example.com") — el usuario pidió poder
 * distinguir servidores porque el nombre no revela el CDN real.
 */
class MovieDownloadServerDialog private constructor(
    private val context: Context,
    private val languageLabel: String,
    private val servers: List<Video.Server>,
    private val isRetry: Boolean,
    private val onConfirm: (server: Video.Server) -> Unit,
    private val onCancel: () -> Unit,
) {

    private var selectedIndex: Int = -1  // -1 = sin selección (botón Descargar deshabilitado)
    private var dialog: AlertDialog? = null
    private var downloadBtn: TextView? = null

    fun show() {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_download_server, null)

        val tvTitle = view.findViewById<TextView>(R.id.tv_dialog_title)
        val tvSubtitle = view.findViewById<TextView>(R.id.tv_dialog_subtitle)
        val tvHint = view.findViewById<TextView>(R.id.tv_dialog_hint)
        downloadBtn = view.findViewById<TextView>(R.id.btn_download)
        val btnCancel = view.findViewById<TextView>(R.id.btn_cancel)
        val rvServers = view.findViewById<RecyclerView>(R.id.rv_servers)

        // Título distinto si es reintento (un server ya falló) vs primera vez.
        tvTitle.text = if (isRetry) {
            context.getString(R.string.download_server_dialog_title_retry)
        } else {
            context.getString(R.string.download_server_dialog_title)
        }

        tvSubtitle.text = context.getString(
            R.string.download_server_dialog_subtitle,
            languageLabel,
            servers.size,
        )

        // Si solo queda un server tras fallos, pre-seleccionarlo para que el
        // usuario solo tenga que pulsar "Descargar".
        val adapter = ServerAdapter(servers, if (servers.size == 1) 0 else -1) { idx ->
            selectedIndex = idx
            (rvServers.adapter as? ServerAdapter)?.notifyDataSetChanged()
            updateDownloadButtonState()
        }
        if (servers.size == 1) selectedIndex = 0
        rvServers.adapter = adapter
        rvServers.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)

        updateDownloadButtonState()

        btnCancel.setOnClickListener {
            dialog?.dismiss()
            onCancel()
        }
        downloadBtn?.setOnClickListener {
            if (selectedIndex in servers.indices) {
                dialog?.dismiss()
                onConfirm(servers[selectedIndex])
            }
        }

        dialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(true)
            .create()
            .apply {
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setOnCancelListener { onCancel() }
                show()
            }
    }

    private fun updateDownloadButtonState() {
        val btn = downloadBtn ?: return
        if (selectedIndex >= 0) {
            btn.isEnabled = true
            btn.alpha = 1f
            btn.isClickable = true
        } else {
            btn.isEnabled = false
            btn.alpha = 0.4f
            btn.isClickable = false
        }
    }

    // ------------------------------------------------------------------
    // Adapter interno para la lista de servidores
    // ------------------------------------------------------------------

    private class ServerAdapter(
        private val servers: List<Video.Server>,
        private val initialSelected: Int,
        private val onSelect: (Int) -> Unit,
    ) : RecyclerView.Adapter<ServerAdapter.VH>() {

        private var selected: Int = initialSelected

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvName: TextView = itemView.findViewById(R.id.tv_server_name)
            val tvDomain: TextView = itemView.findViewById(R.id.tv_server_domain)
            val ivSelected: ImageView = itemView.findViewById(R.id.iv_selected)
            val rowRoot: ViewGroup = itemView.findViewById(R.id.row_root)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_download_server, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val server = servers[position]
            holder.tvName.text = server.name
            holder.tvDomain.text = hostOf(server.src)
            val isSelected = position == selected
            holder.ivSelected.isActivated = isSelected
            holder.rowRoot.setOnClickListener {
                val oldPos = selected
                selected = holder.bindingAdapterPosition
                if (oldPos >= 0) notifyItemChanged(oldPos)
                notifyItemChanged(selected)
                onSelect(selected)
            }
        }

        override fun getItemCount(): Int = servers.size

        private fun hostOf(url: String): String =
            runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
    }

    companion object {
        /**
         * @param isRetry true cuando se re-ofrece la lista tras un fallo de
         * otro server (título "Ese servidor falló, elige otro").
         */
        fun show(
            context: Context,
            languageLabel: String,
            servers: List<Video.Server>,
            isRetry: Boolean,
            onConfirm: (server: Video.Server) -> Unit,
            onCancel: () -> Unit,
        ): MovieDownloadServerDialog {
            val dialog = MovieDownloadServerDialog(
                context,
                languageLabel,
                servers,
                isRetry,
                onConfirm,
                onCancel,
            )
            dialog.show()
            return dialog
        }
    }
}
