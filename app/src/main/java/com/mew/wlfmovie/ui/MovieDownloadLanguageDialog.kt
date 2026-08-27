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
import com.mew.wlfmovie.download.MovieDownloadManager

/**
 * WLFMOVIE V6.3.1: Diálogo de selección de idioma para descarga.
 *
 * Rediseñado para:
 *  - Usar el layout on-brand `dialog_download_language.xml` (gradiente morado,
 *    icono fucsia, botones grandes con gradientes WlfMovie).
 *  - Mostrar las opciones de idioma como filas custom con radio indicator
 *    visible (no radio buttons nativos, que se ven feos en modo oscuro).
 *  - **Deshabilitar el botón "Descargar" hasta que el usuario elija un idioma**
 *    (resuelve el bug: antes el botón estaba activo sin idioma elegido y la
 *    descarga arrancaba con `selectedLanguage` = primero de la lista por
 *    defecto, sin que el usuario eligiera nada).
 *  - Mostrar el tamaño estimado (o "Calculando...") en vivo, actualizable.
 *
 * El diálogo SIEMPRE muestra el selector, incluso si hay un solo idioma
 * — así el usuario ve claramente qué idioma va a descargar y tiene que
 * confirmarlo.
 */
class MovieDownloadLanguageDialog private constructor(
    private val context: Context,
    private val movieTitle: String,
    private val candidate: MovieDownloadManager.DownloadCandidate,
    private var estimatedSizeBytes: Long?,
    private val onConfirm: (language: String) -> Unit,
    private val onCancel: () -> Unit,
) {

    private var selectedIndex: Int = -1  // -1 = sin selección (botón Descargar deshabilitado)
    private var dialog: AlertDialog? = null
    private var sizeView: TextView? = null
    private var downloadBtn: TextView? = null

    fun show() {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_download_language, null)

        val tvTitle = view.findViewById<TextView>(R.id.tv_dialog_title)
        sizeView = view.findViewById<TextView>(R.id.tv_dialog_size)
        downloadBtn = view.findViewById<TextView>(R.id.btn_download)
        val btnCancel = view.findViewById<TextView>(R.id.btn_cancel)
        val rvLanguages = view.findViewById<RecyclerView>(R.id.rv_languages)

        tvTitle.text = context.getString(R.string.download_language_dialog_title, movieTitle)

        // Adaptador de idiomas.
        // WLFMOVIE V6.3.5: mostrar cuantos servers tiene cada idioma — asi el
        // usuario VE que hay detras de cada opcion antes de elegir ("Latino (7)").
        val languages = candidate.languages
        val labels = languages.map { lang ->
            val count = candidate.serversByLanguage[lang]?.size ?: 0
            "${MovieDownloadManager.languageLabel(context, lang)} ($count)"
        }
        rvLanguages.adapter = LanguageAdapter(labels) { idx ->
            selectedIndex = idx
            (rvLanguages.adapter as? LanguageAdapter)?.notifyDataSetChanged()
            updateDownloadButtonState()
        }
        rvLanguages.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)

        updateSizeView()
        updateDownloadButtonState()

        btnCancel.setOnClickListener {
            dialog?.dismiss()
            onCancel()
        }
        downloadBtn?.setOnClickListener {
            if (selectedIndex in languages.indices) {
                dialog?.dismiss()
                onConfirm(languages[selectedIndex])
            }
        }

        dialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(true)
            .create()
            .apply {
                // Fondo transparente para que el wlf_bg_dialog del layout se vea
                // con sus esquinas redondeadas y sin el marco blanco del AlertDialog.
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

    private fun updateSizeView() {
        val tv = sizeView ?: return
        tv.text = estimatedSizeBytes?.let {
            context.getString(
                R.string.download_language_dialog_estimated_size,
                MovieDownloadManager.humanReadableBytes(it),
            )
        } ?: context.getString(R.string.download_language_dialog_calculating_size)
    }

    /**
     * Llamar desde fuera para actualizar el tamano cuando llegue la respuesta del HEAD.
     * Reescribe el texto del [tv_dialog_size] en vivo.
     */
    fun setEstimatedSize(bytes: Long?) {
        estimatedSizeBytes = bytes
        updateSizeView()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    // ------------------------------------------------------------------
    // Adapter interno para la lista de idiomas
    // ------------------------------------------------------------------

    private class LanguageAdapter(
        private val labels: List<String>,
        private val onSelect: (Int) -> Unit,
    ) : RecyclerView.Adapter<LanguageAdapter.VH>() {

        private var selected: Int = -1

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvLabel: TextView = itemView.findViewById(R.id.tv_language_label)
            val ivSelected: ImageView = itemView.findViewById(R.id.iv_selected)
            val rowRoot: ViewGroup = itemView.findViewById(R.id.row_root)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_download_language, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.tvLabel.text = labels[position]
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

        override fun getItemCount(): Int = labels.size
    }

    companion object {
        fun show(
            context: Context,
            movieTitle: String,
            candidate: MovieDownloadManager.DownloadCandidate,
            estimatedSizeBytes: Long?,
            onConfirm: (language: String) -> Unit,
            onCancel: () -> Unit,
        ): MovieDownloadLanguageDialog {
            val dialog = MovieDownloadLanguageDialog(
                context,
                movieTitle,
                candidate,
                estimatedSizeBytes,
                onConfirm,
                onCancel,
            )
            dialog.show()
            return dialog
        }
    }
}
