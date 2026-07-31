package com.mew.wlfmovie.fragments.lists

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.mew.wlfmovie.R
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.databinding.FragmentListsTvBinding
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.models.TvShow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ListsTvFragment : Fragment() {

    private var _binding: FragmentListsTvBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListsTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.vgvLists.adapter = ListsTvAdapter()
        binding.vgvLists.setItemSpacing(40)
        loadLists()
    }

    override fun onResume() {
        super.onResume()
        loadLists()
    }

    private fun loadLists() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val favMovies = database.movieDao().getFavorites().first()
            val favTvShows = database.tvShowDao().getFavorites().first()

            val prefs = requireContext().getSharedPreferences("wlfmovie_watch_later", Context.MODE_PRIVATE)
            val watchLaterMovieIds = prefs.getStringSet("movies", emptySet()) ?: emptySet()
            val watchLaterTvIds = prefs.getStringSet("tv_shows", emptySet()) ?: emptySet()

            val watchLaterMovies = watchLaterMovieIds.mapNotNull { id ->
                database.movieDao().getById(id)
            }
            val watchLaterTvShows = watchLaterTvIds.mapNotNull { id ->
                database.tvShowDao().getById(id)
            }

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                val adapter = binding.vgvLists.adapter as? ListsTvAdapter ?: return@withContext
                adapter.clearSections()
                adapter.addSection(ListTvSection("Películas favoritas", favMovies.size, favMovies, "movie"))
                adapter.addSection(ListTvSection("Series favoritas", favTvShows.size, favTvShows, "tv"))
                adapter.addSection(ListTvSection(
                    "Pendientes por ver",
                    watchLaterMovies.size + watchLaterTvShows.size,
                    watchLaterMovies + watchLaterTvShows,
                    "mixed"
                ))
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

data class ListTvSection(
    val title: String,
    val count: Int,
    val items: List<Any>,
    val itemType: String
)

class ListsTvAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val sections = mutableListOf<ListTvSection>()

    fun addSection(section: ListTvSection) {
        val existing = sections.indexOfFirst { it.title == section.title }
        if (existing >= 0) {
            sections[existing] = section
        } else {
            sections.add(section)
        }
        notifyDataSetChanged()
    }

    fun clearSections() {
        sections.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_list_section_tv, parent, false)
        return SectionTvViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as? SectionTvViewHolder)?.bind(sections[position])
    }

    override fun getItemCount() = sections.size
}

class SectionTvViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val title: TextView = view.findViewById(R.id.tv_section_title)
    private val count: TextView = view.findViewById(R.id.tv_section_count)
    private val recycler: RecyclerView = view.findViewById(R.id.rv_section_items)

    fun bind(section: ListTvSection) {
        title.text = section.title
        count.text = section.count.toString()

        recycler.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                itemView.context,
                androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
                false
            )
            adapter = CardTvAdapter(section.items) { item ->
                val navController = findNavController()
                when (item) {
                    is Movie -> {
                        val args = Bundle().apply { putString("id", item.id) }
                        navController.navigate(R.id.movie, args)
                    }
                    is TvShow -> {
                        val args = Bundle().apply {
                            putString("id", item.id)
                            putString("poster", item.poster)
                            putString("banner", item.banner)
                        }
                        navController.navigate(R.id.tv_show, args)
                    }
                }
            }
        }
    }

    private fun findNavController(): androidx.navigation.NavController {
        var view = itemView
        while (view.parent != null) {
            view = view.parent as View
            if (view is androidx.fragment.app.FragmentContainerView) {
                return androidx.navigation.Navigation.findNavController(itemView)
            }
        }
        return androidx.navigation.Navigation.findNavController(itemView)
    }
}

class CardTvAdapter(
    private val items: List<Any>,
    private val onItemClick: (Any) -> Unit
) : RecyclerView.Adapter<CardTvAdapter.CardTvViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardTvViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_list_card_tv, parent, false)
        return CardTvViewHolder(view)
    }

    override fun onBindViewHolder(holder: CardTvViewHolder, position: Int) {
        holder.bind(items[position], onItemClick)
    }

    override fun getItemCount() = items.size

    class CardTvViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val poster: ImageView = view.findViewById(R.id.iv_list_item_poster)
        private val title: TextView = view.findViewById(R.id.tv_list_item_title)

        fun bind(item: Any, onItemClick: (Any) -> Unit) {
            when (item) {
                is Movie -> {
                    Glide.with(poster).load(item.poster).into(poster)
                    title.text = item.title
                    itemView.setOnClickListener { onItemClick(item) }
                }
                is TvShow -> {
                    Glide.with(poster).load(item.poster).into(poster)
                    title.text = item.title
                    itemView.setOnClickListener { onItemClick(item) }
                }
            }
        }
    }
}
