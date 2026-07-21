package com.piggie.tv.ui.music

import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.piggie.tv.R
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.MediaShelf
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.playback.MusicPlaybackManager
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.util.dim
import com.piggie.tv.util.dimFloat
import com.piggie.tv.util.setTextSizeRes
import kotlin.concurrent.thread

class MusicFragment : Fragment() {
    private val api by lazy { JellyfinNativeApi(requireContext()) }
    private val store by lazy { SecureSessionStore(requireContext()) }
    private lateinit var session: NativeSession
    private lateinit var root: FrameLayout
    private lateinit var page: LinearLayout
    private var loading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        session = (activity as? PtvHostActivity)?.session ?: store.read()!!
        root = FrameLayout(requireContext())
        setupView()
        loadMusic()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.isFocusable = false
        page.isFocusable = false
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

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
        root.addView(scroll)
    }

    private fun loadMusic() {
        if (loading) return
        loading = true

        val context = requireContext()
        val loadingLabel = label("Loading Music...", 18f, R.color.tv_text_secondary, margin = 20, leftPadding = context.dim(R.dimen.tv_screen_margin_horizontal))
        page.addView(loadingLabel)

        thread(start = true) {
            runCatching {
                api.loadMusicHomeIncrementally(session) { shelf ->
                    activity?.runOnUiThread {
                        loadingLabel.visibility = View.GONE
                        addShelf(page, shelf)
                    }
                }

                val artists = api.loadArtists(session, limit = 12)
                if (artists.isNotEmpty()) {
                    activity?.runOnUiThread {
                        addShelf(page, MediaShelf("Artists", artists, MediaCardPresentation.SQUARE))
                    }
                }

                activity?.runOnUiThread {
                    loadingLabel.visibility = View.GONE
                    addCategoriesGrid()
                }
            }.onSuccess {
                activity?.runOnUiThread { loading = false }
            }
        }
    }

    private fun addCategoriesGrid() {
        val context = requireContext()
        page.addView(label("Categories", context.dimFloat(R.dimen.tv_text_size_section_title), R.color.tv_text_primary, true, margin = 32, leftPadding = context.dim(R.dimen.tv_screen_margin_horizontal), isPx = true))
        val grid = GridLayoutManager(context, 5)
        val recycler = RecyclerView(context).apply {
            layoutManager = grid
            adapter = CategoryAdapter(listOf("Artists", "Albums", "Songs", "Playlists", "Genres"))
            setPadding(context.dim(R.dimen.tv_screen_margin_horizontal), context.dim(R.dimen.tv_spacing_medium), context.dim(R.dimen.tv_screen_margin_horizontal), context.dim(R.dimen.tv_spacing_medium))
        }
        page.addView(recycler)
    }

    private fun addShelf(parent: LinearLayout, shelf: MediaShelf) {
        val context = requireContext()
        parent.addView(label(shelf.title, context.dimFloat(R.dimen.tv_text_size_section_title), R.color.tv_text_primary, true, margin = 24, leftPadding = context.dim(R.dimen.tv_screen_margin_horizontal), isPx = true))
        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            adapter = MusicCardAdapter(shelf.items, shelf.presentation, session, api)
            clipChildren = false
            clipToPadding = false
            setPadding(context.dim(R.dimen.tv_screen_margin_horizontal), context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_screen_margin_horizontal), context.dim(R.dimen.tv_spacing_small))
        }
        val h = when (shelf.presentation) {
            MediaCardPresentation.SQUARE -> context.dim(R.dimen.tv_square_height) + context.dim(R.dimen.tv_spacing_large) + context.dim(R.dimen.tv_spacing_small)
            MediaCardPresentation.LANDSCAPE -> context.dim(R.dimen.tv_landscape_height) + context.dim(R.dimen.tv_spacing_large) + context.dim(R.dimen.tv_spacing_small)
            else -> context.dim(R.dimen.tv_poster_height) + context.dim(R.dimen.tv_spacing_large) + context.dim(R.dimen.tv_spacing_small)
        }
        parent.addView(recycler, LinearLayout.LayoutParams(-1, h))
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false, margin: Int = 0, leftPadding: Int = 0, isPx: Boolean = false): TextView =
        TextView(requireContext()).apply {
            text = value
            if (isPx) setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size) else textSize = size
            setTextColor(requireContext().getColor(color))
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            setPadding(leftPadding, 0, 0, 0)
            if (margin > 0) layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = margin }
        }

    private class MusicCardAdapter(
        private val items: List<MediaItem>,
        private val presentation: MediaCardPresentation,
        private val session: NativeSession,
        private val api: JellyfinNativeApi
    ) : RecyclerView.Adapter<MusicCardAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            val size = when (presentation) {
                MediaCardPresentation.SQUARE -> context.dim(R.dimen.tv_square_width) to context.dim(R.dimen.tv_square_height)
                MediaCardPresentation.LANDSCAPE -> context.dim(R.dimen.tv_landscape_width) to context.dim(R.dimen.tv_landscape_height)
                else -> context.dim(R.dimen.tv_square_width) to context.dim(R.dimen.tv_square_height)
            }
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundResource(R.drawable.tv_media_card_background)
                setPadding(context.dim(R.dimen.tv_card_padding), context.dim(R.dimen.tv_card_padding), context.dim(R.dimen.tv_card_padding), context.dim(R.dimen.tv_card_padding))
                setOnFocusChangeListener { view, focused ->
                    view.animate().scaleX(if (focused) 1.05f else 1f).scaleY(if (focused) 1.05f else 1f).setDuration(120).start()
                }
            }
            val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val title = TextView(context).apply {
                setTextColor(context.getColor(R.color.tv_text_primary))
                setTextSizeRes(R.dimen.tv_text_size_card_title)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_spacing_small), 0)
            }
            val subtitle = TextView(context).apply {
                setTextColor(context.getColor(R.color.tv_text_secondary))
                setTextSizeRes(R.dimen.tv_text_size_metadata)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(context.dim(R.dimen.tv_spacing_small), 0, context.dim(R.dimen.tv_spacing_small), 0)
            }
            card.addView(image, LinearLayout.LayoutParams(size.first, size.second))
            card.addView(title, LinearLayout.LayoutParams(size.first, -2))
            card.addView(subtitle, LinearLayout.LayoutParams(size.first, -2))
            return Holder(card, image, title, subtitle)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.subtitle.text = when(item.type) {
                "MusicArtist" -> "Artist"
                "MusicAlbum" -> item.albumArtist ?: "Album"
                "Audio" -> item.artists.firstOrNull() ?: item.album ?: "Song"
                else -> item.type
            }
            
            holder.card.setOnClickListener {
                when(item.type) {
                    "Audio" -> MusicPlaybackManager.play(holder.card.context, session, listOf(item))
                    else -> MusicDetailsActivity.start(holder.card.context, item.id)
                }
            }
            
            holder.image.load(api.imageUrl(session, item, presentation)) {
                crossfade(true)
                placeholder(ColorDrawable(0xFF1A0A33.toInt()))
                if (item.type == "MusicArtist") transformations(CircleCropTransformation())
            }
        }
        override fun getItemCount(): Int = items.size
        class Holder(val card: View, val image: ImageView, val title: TextView, val subtitle: TextView) : RecyclerView.ViewHolder(card)
    }

    private class CategoryAdapter(private val categories: List<String>) : RecyclerView.Adapter<CategoryAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            val button = Button(context).apply {
                isAllCaps = false
                setTextSizeRes(R.dimen.tv_nav_text_size)
                setBackgroundResource(R.drawable.tv_button_secondary)
                setTextColor(context.getColor(R.color.tv_text_primary))
                layoutParams = ViewGroup.LayoutParams(-1, context.dim(R.dimen.tv_nav_button_height) + context.dim(R.dimen.tv_spacing_small))
            }
            return Holder(button)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val cat = categories[position]
            (holder.itemView as Button).apply {
                text = cat
                setOnClickListener { /* Category navigation */ }
            }
        }
        override fun getItemCount(): Int = categories.size
        class Holder(view: View) : RecyclerView.ViewHolder(view)
    }
}
