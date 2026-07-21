package com.piggie.tv.ui.shows

import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.piggie.tv.R
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.*
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.ui.player.MediaDetailsActivity
import com.piggie.tv.util.dim
import com.piggie.tv.util.dimFloat
import com.piggie.tv.util.setTextSizeRes
import kotlin.concurrent.thread

class ShowsFragment : Fragment() {
    private val api by lazy { JellyfinNativeApi(requireContext()) }
    private val store by lazy { SecureSessionStore(requireContext()) }
    private lateinit var session: NativeSession
    private lateinit var root: FrameLayout
    private val shows = mutableListOf<MediaItem>()
    private var loading = false
    private var gridLoading = false
    private var startIndex = 0
    private val limit = 50
    private var hasMore = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        session = (activity as? PtvHostActivity)?.session ?: store.read()!!
        root = FrameLayout(requireContext())
        setupView()
        loadData()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.isFocusable = false
        page.isFocusable = false
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    private lateinit var page: LinearLayout
    private fun setupView() {
        val context = requireContext()
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            isFocusable = false
        }
        page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, context.dim(R.dimen.tv_spacing_large))
        }
        scroll.addView(page)
        root.addView(scroll)
    }

    private fun loadData() {
        if (loading) return
        loading = true
        
        val context = requireContext()
        val loadingLabel = label("Loading Shows...", 18f, R.color.tv_text_secondary, margin = 20, leftPadding = context.dim(R.dimen.tv_screen_margin_horizontal))
        page.addView(loadingLabel)

        thread(start = true) {
            runCatching {
                api.loadShowsIncrementally(session) { shelf ->
                    activity?.runOnUiThread {
                        loadingLabel.visibility = View.GONE
                        addShelf(page, shelf)
                    }
                }
                
                activity?.runOnUiThread {
                    page.addView(label("All Series", context.dimFloat(R.dimen.tv_text_size_section_title), R.color.tv_text_primary, true, margin = 32, leftPadding = context.dim(R.dimen.tv_screen_margin_horizontal), isPx = true))
                    setupGrid()
                    loadGrid()
                }
            }.onSuccess {
                activity?.runOnUiThread { loading = false }
            }
        }
    }

    private lateinit var gridAdapter: ShowGridAdapter
    private fun setupGrid() {
        val context = requireContext()
        val recycler = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 7)
            gridAdapter = ShowGridAdapter(shows, api, session) { position ->
                if (position >= shows.size - 10 && !gridLoading && hasMore) {
                    loadGrid()
                }
            }
            adapter = gridAdapter
            setPadding(context.dim(R.dimen.tv_screen_margin_horizontal), context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_screen_margin_horizontal), context.dim(R.dimen.tv_spacing_small))
            clipToPadding = false
            isNestedScrollingEnabled = false
        }
        page.addView(recycler)
    }

    private fun loadGrid() {
        if (gridLoading || !hasMore) return
        gridLoading = true
        thread(start = true) {
            runCatching {
                api.loadShows(session, startIndex, limit)
            }.onSuccess { results ->
                activity?.runOnUiThread {
                    if (results.isEmpty()) {
                        hasMore = false
                    } else {
                        val oldSize = shows.size
                        shows.addAll(results)
                        startIndex += results.size
                        gridAdapter.notifyItemRangeInserted(oldSize, results.size)
                    }
                    gridLoading = false
                }
            }
        }
    }

    private fun addShelf(parent: LinearLayout, shelf: MediaShelf) {
        val context = requireContext()
        parent.addView(label(shelf.title, context.dimFloat(R.dimen.tv_text_size_section_title), R.color.tv_text_primary, true, margin = 24, leftPadding = context.dim(R.dimen.tv_screen_margin_horizontal), isPx = true))
        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            adapter = MediaCardAdapter(shelf.items, shelf.presentation, session, api)
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(context.dim(R.dimen.tv_screen_margin_horizontal), context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_screen_margin_horizontal), context.dim(R.dimen.tv_spacing_medium))
        }
        val h = when (shelf.presentation) {
            MediaCardPresentation.POSTER -> context.dim(R.dimen.tv_poster_height) + context.dim(R.dimen.tv_spacing_large) + context.dim(R.dimen.tv_spacing_small)
            MediaCardPresentation.LANDSCAPE -> context.dim(R.dimen.tv_landscape_height) + context.dim(R.dimen.tv_spacing_large) + context.dim(R.dimen.tv_spacing_small)
            MediaCardPresentation.SQUARE -> context.dim(R.dimen.tv_square_height) + context.dim(R.dimen.tv_spacing_large) + context.dim(R.dimen.tv_spacing_small)
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

    private class MediaCardAdapter(
        private val items: List<MediaItem>,
        private val presentation: MediaCardPresentation,
        private val session: NativeSession,
        private val api: JellyfinNativeApi
    ) : RecyclerView.Adapter<MediaCardAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            val size = when (presentation) {
                MediaCardPresentation.POSTER -> context.dim(R.dimen.tv_poster_width) to context.dim(R.dimen.tv_poster_height)
                MediaCardPresentation.LANDSCAPE -> context.dim(R.dimen.tv_landscape_width) to context.dim(R.dimen.tv_landscape_height)
                MediaCardPresentation.SQUARE -> context.dim(R.dimen.tv_square_width) to context.dim(R.dimen.tv_square_height)
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
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_spacing_small), 0)
            }
            card.addView(image, LinearLayout.LayoutParams(size.first, size.second))
            card.addView(title, LinearLayout.LayoutParams(size.first, -2))
            return Holder(card, image, title)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.card.setOnClickListener { MediaDetailsActivity.start(holder.card.context, item.id) }
            holder.image.load(api.imageUrl(session, item, presentation)) {
                crossfade(true)
                placeholder(ColorDrawable(0xFF221136.toInt()))
            }
        }
        override fun getItemCount(): Int = items.size
        class Holder(val card: View, val image: ImageView, val title: TextView) : RecyclerView.ViewHolder(card)
    }

    private class ShowGridAdapter(
        private val items: List<MediaItem>,
        private val api: JellyfinNativeApi,
        private val session: NativeSession,
        private val onBind: (Int) -> Unit
    ) : RecyclerView.Adapter<ShowGridAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
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
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_spacing_small), 0)
            }
            card.addView(image, LinearLayout.LayoutParams(context.dim(R.dimen.tv_poster_width), context.dim(R.dimen.tv_poster_height)))
            card.addView(title, LinearLayout.LayoutParams(context.dim(R.dimen.tv_poster_width), -2))
            return Holder(card, image, title)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.card.setOnClickListener { MediaDetailsActivity.start(holder.card.context, item.id) }
            holder.image.load(api.imageUrl(session, item, MediaCardPresentation.POSTER)) {
                crossfade(true)
                placeholder(ColorDrawable(0xFF221136.toInt()))
            }
            onBind(position)
        }
        override fun getItemCount(): Int = items.size
        class Holder(val card: View, val image: ImageView, val title: TextView) : RecyclerView.ViewHolder(card)
    }
}
