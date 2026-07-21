package com.piggie.tv.ui.search

import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.piggie.tv.R
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.*
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.theme.PTVTypography
import com.piggie.tv.ui.player.MediaDetailsActivity
import com.piggie.tv.ui.widgets.MediaCardFactory
import com.piggie.tv.ui.widgets.MediaCardHolder
import com.piggie.tv.util.dim
import com.piggie.tv.util.dimFloat
import com.piggie.tv.util.setTextSizeRes
import com.piggie.tv.util.sp
import kotlin.concurrent.thread

class SearchFragment : Fragment() {
    private val api by lazy { JellyfinNativeApi(requireContext()) }
    private val store by lazy { SecureSessionStore(requireContext()) }
    private lateinit var session: NativeSession
    private lateinit var root: FrameLayout
    private val searchResults = mutableListOf<MediaShelf>()
    private var loading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        session = (activity as? PtvHostActivity)?.session ?: store.read()!!
        root = FrameLayout(requireContext())
        setupView()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root.isFocusable = false
        root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        page.findViewById<View>(inputViewId)?.requestFocus()
    }

    private lateinit var page: LinearLayout
    private var inputViewId: Int = View.NO_ID
    private fun setupView() {
        val context = requireContext()
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
        }
        page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, context.dim(R.dimen.tv_spacing_large))
        }
        scroll.addView(page)
        
        page.addView(label("Search", context.sp(R.dimen.tv_text_size_page_title), R.color.tv_text_primary, true))
        
        val searchRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, context.dim(R.dimen.tv_spacing_medium), 0, 0)
        }
        val input = EditText(context).apply {
            inputViewId = View.generateViewId()
            id = inputViewId
            hint = "Movies, series, actors..."
            setSingleLine()
            textSize = 17f
            setTextColor(context.getColor(R.color.tv_text_primary))
            setHintTextColor(context.getColor(R.color.tv_text_secondary))
            setBackgroundResource(R.drawable.tv_field_bg)
            setPadding(context.dim(R.dimen.tv_spacing_medium), context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_spacing_medium), context.dim(R.dimen.tv_spacing_small))
        }
        val searchBtn = Button(context).apply {
            id = View.generateViewId()
            text = "Search"
            textSize = 15f
            isAllCaps = false
            setTextColor(context.getColor(R.color.tv_text_primary))
            setBackgroundResource(R.drawable.tv_button_primary)
            setOnClickListener {
                val query = input.text.toString().trim()
                if (query.isNotBlank()) performSearch(query)
            }
        }
        input.nextFocusRightId = searchBtn.id
        searchBtn.nextFocusLeftId = input.id
        searchRow.addView(input, LinearLayout.LayoutParams(0, context.dim(R.dimen.tv_nav_button_height) + context.dim(R.dimen.tv_spacing_small), 1f))
        searchRow.addView(searchBtn, LinearLayout.LayoutParams(context.dim(R.dimen.tv_hero_button_width), context.dim(R.dimen.tv_nav_button_height) + context.dim(R.dimen.tv_spacing_small)).apply { marginStart = context.dim(R.dimen.tv_spacing_medium) })
        page.addView(searchRow)

        root.addView(scroll)
    }

    private fun performSearch(query: String) {
        if (loading) return
        loading = true
        
        // Remove existing results
        while (page.childCount > 2) page.removeViewAt(2)
        
        val loadingLabel = label("Searching...", 16f, R.color.tv_text_secondary, margin = 24)
        page.addView(loadingLabel)

        thread(start = true) {
            runCatching {
                api.search(session, query)
            }.onSuccess { items ->
                val grouped = items.groupBy { it.type }
                activity?.runOnUiThread {
                    page.removeView(loadingLabel)
                    if (items.isEmpty()) {
                        page.addView(label("No results found for \"$query\"", 17f, R.color.tv_text_secondary, margin = 32))
                    } else {
                        grouped.forEach { (type, typeItems) ->
                            val title = when(type) {
                                "Movie" -> "Movies"
                                "Series" -> "Series"
                                "MusicArtist" -> "Artists"
                                "MusicAlbum" -> "Albums"
                                "Audio" -> "Songs"
                                "Person" -> "People"
                                else -> type
                            }
                            val presentation = when(type) {
                                "Person", "MusicArtist" -> MediaCardPresentation.SQUARE
                                "Movie", "Series" -> MediaCardPresentation.POSTER
                                else -> MediaCardPresentation.LANDSCAPE
                            }
                            addShelf(page, MediaShelf(title, typeItems, presentation))
                        }
                    }
                    loading = false
                }
            }.onFailure {
                activity?.runOnUiThread {
                    page.removeView(loadingLabel)
                    page.addView(label("Search error: ${it.message}", 16f, R.color.tv_text_secondary, margin = 32))
                    loading = false
                }
            }
        }
    }

    private fun addShelf(parent: LinearLayout, shelf: MediaShelf) {
        val context = requireContext()
        parent.addView(label(shelf.title, context.dimFloat(R.dimen.tv_text_size_section_title), R.color.tv_text_primary, true, margin = 32, isPx = true))
        
        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            adapter = SearchResultAdapter(shelf.items, shelf.presentation, session, api)
            clipChildren = false
            clipToPadding = false
            setPadding(0, context.dim(R.dimen.tv_spacing_small), 0, context.dim(R.dimen.tv_spacing_small))
        }
        
        val h = when (shelf.presentation) {
            MediaCardPresentation.POSTER -> context.dim(R.dimen.tv_poster_height) + context.dim(R.dimen.tv_spacing_large)
            MediaCardPresentation.LANDSCAPE -> context.dim(R.dimen.tv_landscape_height) + context.dim(R.dimen.tv_spacing_large)
            MediaCardPresentation.SQUARE -> context.dim(R.dimen.tv_square_height) + context.dim(R.dimen.tv_spacing_large)
        }
        parent.addView(recycler, LinearLayout.LayoutParams(-1, h))
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false, margin: Int = 0, isPx: Boolean = false): TextView =
        TextView(requireContext()).apply {
            text = value
            if (isPx) setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size) else textSize = size
            setTextColor(requireContext().getColor(color))
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            if (margin > 0) layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = margin }
        }

    private inner class SearchResultAdapter(
        private val items: List<MediaItem>,
        private val presentation: MediaCardPresentation,
        private val session: NativeSession,
        private val api: JellyfinNativeApi
    ) : RecyclerView.Adapter<MediaCardHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaCardHolder {
            return MediaCardHolder(MediaCardFactory.createView(parent, presentation))
        }
        override fun onBindViewHolder(holder: MediaCardHolder, position: Int) {
            val item = items[position]
            MediaCardFactory.bindView(holder, item, presentation, session, api)
            holder.itemView.setOnClickListener { MediaDetailsActivity.start(it.context, item.id) }
        }
        override fun getItemCount(): Int = items.size
    }
}
