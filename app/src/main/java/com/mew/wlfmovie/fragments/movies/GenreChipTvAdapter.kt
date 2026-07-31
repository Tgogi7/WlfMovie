package com.mew.wlfmovie.fragments.movies

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mew.wlfmovie.R
import com.mew.wlfmovie.models.Genre

/**
 * WLFMOVIE: Adapter para chips de géneros en TV.
 *
 * Igual que GenreChipAdapter del mobile pero:
 * - Usa wlf_item_genre_chip_tv.xml (más grande, con foco morado-fucsia).
 * - Los items son focusables para D-pad.
 * - El callback recibe el Genre clickeado.
 *
 * Reutilizable desde MoviesTvFragment y TvShowsTvFragment.
 */
class GenreChipTvAdapter(
    private val genres: List<Genre>,
    private val onGenreClick: (Genre) -> Unit
) : RecyclerView.Adapter<GenreChipTvAdapter.ChipViewHolder>() {

    private var selectedId: String? = null

    fun setSelected(id: String?) {
        selectedId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.wlf_item_genre_chip_tv, parent, false)
        return ChipViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
        holder.bind(genres[position], selectedId, onGenreClick)
    }

    override fun getItemCount() = genres.size

    class ChipViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.tv_genre_chip)

        fun bind(genre: Genre, selectedId: String?, onClick: (Genre) -> Unit) {
            text.text = genre.name
            text.isSelected = genre.id == selectedId
            text.setOnClickListener { onClick(genre) }
            text.setOnFocusChangeListener { _, hasFocus ->
                // WLFMOVIE: al recibir foco, agrandar ligeramente el texto
                text.alpha = if (hasFocus) 1f else 0.85f
            }
        }
    }
}
