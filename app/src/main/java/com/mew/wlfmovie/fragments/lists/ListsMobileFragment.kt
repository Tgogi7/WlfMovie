package com.mew.wlfmovie.fragments.lists

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.mew.wlfmovie.R
import com.mew.wlfmovie.database.AppDatabase
import com.mew.wlfmovie.databinding.FragmentListsMobileBinding
import com.mew.wlfmovie.models.Movie
import com.mew.wlfmovie.models.TvShow
import com.mew.wlfmovie.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.navigation.findNavController

class ListsMobileFragment : Fragment() {

    private var _binding: FragmentListsMobileBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListsMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvLists.adapter = ListsAdapter()
        binding.rvLists.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
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

            // Watch later desde SharedPreferences
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
                val adapter = binding.rvLists.adapter as? ListsAdapter ?: return@withContext
                adapter.clearSections()
                setupList("Películas favoritas", favMovies.size, favMovies, "movie")
                setupList("Series favoritas", favTvShows.size, favTvShows, "tv")
                setupList(
                    "Pendientes por ver",
                    watchLaterMovies.size + watchLaterTvShows.size,
                    watchLaterMovies + watchLaterTvShows,
                    "mixed"
                )
            }
        }
    }

    private fun setupList(
        titleText: String,
        count: Int,
        items: List<Any>,
        itemType: String
    ) {
        val rv = binding.rvLists
        val adapter = rv.adapter as? ListsAdapter ?: return

        adapter.addSection(ListSection(titleText, count, items, itemType))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

data class ListSection(
    val title: String,
    val count: Int,
    val items: List<Any>,
    val itemType: String
)

class ListsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val sections = mutableListOf<ListSection>()

    fun addSection(section: ListSection) {
        // Buscar si ya existe una sección con ese título
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
            .inflate(R.layout.item_list_section_mobile, parent, false)
        return SectionViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as? SectionViewHolder)?.bind(sections[position])
    }

    override fun getItemCount() = sections.size
}

class SectionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val title: TextView = view.findViewById(R.id.tv_section_title)
    private val count: TextView = view.findViewById(R.id.tv_section_count)
    private val recycler: RecyclerView = view.findViewById(R.id.rv_section_items)

    fun bind(section: ListSection) {
        title.text = section.title
        count.text = section.count.toString()

        recycler.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                itemView.context,
                androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
                false
            )
            adapter = CardAdapter(section.items, section.itemType) { item ->
                // Click en la card → navegar a detalles
                val navController = itemView.findNavController()
                when (item) {
                    is Movie -> {
                        val args = Bundle().apply {
                            putString("id", item.id)
                        }
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
}

class CardAdapter(
    private val items: List<Any>,
    private val itemType: String,
    private val onItemClick: (Any) -> Unit
) : RecyclerView.Adapter<CardAdapter.CardViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_list_card_mobile, parent, false)
        return CardViewHolder(view)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        holder.bind(items[position], onItemClick) { removedItem ->
            // X click → quitar de la lista
            val ctx = holder.itemView.context
            val db = AppDatabase.getInstance(ctx)

            when (removedItem) {
                is Movie -> {
                    val scope = holder.itemView.findViewTreeLifecycleOwner()?.lifecycleScope
                    scope?.launch(Dispatchers.IO) {
                        val dbMovie = db.movieDao().getById(removedItem.id)
                        dbMovie?.let {
                            it.isFavorite = false
                            it.favoritedAtMillis = null
                            db.movieDao().insert(it)
                        }
                    }
                }
                is TvShow -> {
                    val scope = holder.itemView.findViewTreeLifecycleOwner()?.lifecycleScope
                    scope?.launch(Dispatchers.IO) {
                        val dbTv = db.tvShowDao().getById(removedItem.id)
                        dbTv?.let {
                            it.isFavorite = false
                            it.favoritedAtMillis = null
                            db.tvShowDao().insert(it)
                        }
                    }
                }
            }
            // También quitar de watch later si está ahí
            val prefs = ctx.getSharedPreferences("wlfmovie_watch_later", Context.MODE_PRIVATE)
            val movieSet = prefs.getStringSet("movies", emptySet())!!.toMutableSet()
            val tvSet = prefs.getStringSet("tv_shows", emptySet())!!.toMutableSet()
            when (removedItem) {
                is Movie -> {
                    movieSet.remove(removedItem.id)
                    prefs.edit().putStringSet("movies", movieSet).apply()
                }
                is TvShow -> {
                    tvSet.remove(removedItem.id)
                    prefs.edit().putStringSet("tv_shows", tvSet).apply()
                }
            }
            Toast.makeText(ctx, "Quitado de la lista", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount() = items.size

    class CardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val poster: ImageView = view.findViewById(R.id.iv_list_item_poster)
        private val title: TextView = view.findViewById(R.id.tv_list_item_title)
        private val removeBtn: ImageView = view.findViewById(R.id.btn_list_item_remove)

        fun bind(item: Any, onItemClick: (Any) -> Unit, onRemove: (Any) -> Unit) {
            when (item) {
                is Movie -> {
                    Glide.with(poster).load(item.poster).into(poster)
                    title.text = item.title
                    itemView.setOnClickListener { onItemClick(item) }
                    removeBtn.setOnClickListener { onRemove(item) }
                }
                is TvShow -> {
                    Glide.with(poster).load(item.poster).into(poster)
                    title.text = item.title
                    itemView.setOnClickListener { onItemClick(item) }
                    removeBtn.setOnClickListener { onRemove(item) }
                }
            }
        }
    }
}
