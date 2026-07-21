package com.piggie.tv.ui.home

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
import android.widget.ProgressBar
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
import com.piggie.tv.data.playback.PlaybackProgress
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.theme.PTVTypography
import com.piggie.tv.ui.player.MediaDetailsActivity
import com.piggie.tv.ui.player.VideoPlayerActivity
import com.piggie.tv.ui.shared.BackdropManager
import com.piggie.tv.ui.shared.TextSanitizer
import com.piggie.tv.ui.widgets.MediaCardFactory
import com.piggie.tv.ui.widgets.MediaCardHolder
import com.piggie.tv.util.dim
import com.piggie.tv.util.dimFloat
import com.piggie.tv.util.setTextSizeRes
import com.piggie.tv.util.sp
import kotlin.concurrent.thread

class HomeFragment : Fragment() {
    private val api by lazy { JellyfinNativeApi(requireContext()) }
    private val store by lazy { SecureSessionStore(requireContext()) }
    private lateinit var session: NativeSession
    private lateinit var root: FrameLayout
    private lateinit var backdropView: ImageView
    private lateinit var backdropOverlay: View
    private lateinit var pageContent: LinearLayout
    private var loading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        session = (activity as? PtvHostActivity)?.session ?: store.read()!!
        
        root = FrameLayout(requireContext()).apply {
            setBackgroundColor(PTVColors.background)
        }

        backdropView = ImageView(requireContext()).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.5f
        }
        root.addView(backdropView, ViewGroup.LayoutParams(-1, -1))

        backdropOverlay = View(requireContext()).apply {
            setBackgroundResource(R.drawable.hero_gradient_overlay)
        }
        root.addView(backdropOverlay, ViewGroup.LayoutParams(-1, -1))

        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = true
            clipToPadding = false
        }
        pageContent = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, requireContext().dim(R.dimen.tv_spacing_large))
        }
        scroll.addView(pageContent)
        root.addView(scroll)

        loadHome()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root.isFocusable = false
        root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    private fun loadHome() {
        if (loading) return
        loading = true
        
        val loadingLabel = label("Loading...", 18f, R.color.tv_text_secondary, margin = 20)
        pageContent.addView(loadingLabel)

        thread(start = true) {
            runCatching {
                val hero = api.loadHero(session)
                activity?.runOnUiThread {
                    if (hero != null) {
                        addHero(pageContent, hero)
                        loadingLabel.visibility = View.GONE
                        BackdropManager.update(backdropView, backdropOverlay, session, hero, api)
                    }
                }
                
                api.loadHomeIncrementally(session) { shelf ->
                    activity?.runOnUiThread {
                        loadingLabel.visibility = View.GONE
                        addShelf(pageContent, shelf)
                    }
                }
            }.onSuccess {
                activity?.runOnUiThread { loading = false }
            }.onFailure { error ->
                activity?.runOnUiThread {
                    loading = false
                    loadingLabel.text = "Error loading home: ${error.message}"
                }
            }
        }
    }

    private fun addHero(parent: LinearLayout, item: MediaItem) {
        val context = requireContext()
        val hero = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, context.dim(R.dimen.tv_hero_height))
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dim(R.dimen.tv_screen_margin_horizontal), context.dim(R.dimen.tv_spacing_large), context.dim(R.dimen.tv_screen_margin_horizontal), 0)
        }
        
        if (item.logoTag != null) {
            val logo = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_START
                adjustViewBounds = true
                load(api.logoUrl(session, item))
            }
            content.addView(logo, LinearLayout.LayoutParams(context.dim(R.dimen.tv_hero_logo_max_width), context.dim(R.dimen.tv_hero_logo_max_height)))
        } else {
            val title = label(item.title, context.dimFloat(R.dimen.tv_text_size_hero_title), R.color.tv_text_primary, true, isPx = true)
            content.addView(title)
        }

        val metaText = TextSanitizer.formatMetadata(item.year, item.officialRating, item.genres.firstOrNull())
        content.addView(label(metaText, context.dimFloat(R.dimen.tv_text_size_hero_meta), R.color.tv_text_secondary, margin = 8, isPx = true))

        val overview = TextView(context).apply {
            text = TextSanitizer.sanitize(item.overview)
            setTextSizeRes(R.dimen.tv_text_size_hero_body)
            setTextColor(PTVColors.textSecondary)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(context.dim(R.dimen.tv_hero_content_width), -2).apply { topMargin = context.dim(R.dimen.tv_spacing_medium) }
        }
        content.addView(overview)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, context.dim(R.dimen.tv_spacing_medium), 0, 0)
        }
        val play = Button(context).apply {
            text = item.playbackPositionTicks.let { if (it > 0) "Resume" else "Play" }
            setTextSizeRes(R.dimen.tv_hero_button_text_size)
            isAllCaps = false
            setBackgroundResource(R.drawable.tv_button_primary)
            setTextColor(PTVColors.textPrimary)
            setPadding(context.dim(R.dimen.tv_spacing_large), 0, context.dim(R.dimen.tv_spacing_large), 0)
            setOnClickListener { VideoPlayerActivity.start(requireContext(), item.id, item.playbackPositionTicks) }
        }
        val details = Button(context).apply {
            text = "Details"
            setTextSizeRes(R.dimen.tv_hero_button_text_size)
            isAllCaps = false
            setBackgroundResource(R.drawable.tv_button_secondary)
            setTextColor(PTVColors.textPrimary)
            setPadding(context.dim(R.dimen.tv_spacing_large), 0, context.dim(R.dimen.tv_spacing_large), 0)
            setOnClickListener { MediaDetailsActivity.start(requireContext(), item.id) }
        }
        actions.addView(play, LinearLayout.LayoutParams(context.dim(R.dimen.tv_hero_button_width), context.dim(R.dimen.tv_hero_button_height)))
        actions.addView(details, LinearLayout.LayoutParams(context.dim(R.dimen.tv_hero_button_width), context.dim(R.dimen.tv_hero_button_height)).apply { marginStart = context.dim(R.dimen.tv_spacing_medium) })
        content.addView(actions)

        hero.addView(content)
        parent.addView(hero, 0)
    }

    private fun addShelf(parent: LinearLayout, shelf: MediaShelf) {
        val context = requireContext()
        val title = label(shelf.title, context.dimFloat(R.dimen.tv_text_size_section_title), R.color.tv_text_primary, true, margin = 20, leftPadding = context.dim(R.dimen.tv_screen_margin_horizontal), isPx = true)
        parent.addView(title)
        
        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            adapter = ShelfAdapter(shelf.items, shelf.presentation, session, api)
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(context.dim(R.dimen.tv_screen_margin_horizontal), context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_screen_margin_horizontal), context.dim(R.dimen.tv_spacing_small))
        }
        
        val height = when (shelf.presentation) {
            MediaCardPresentation.POSTER -> context.dim(R.dimen.tv_poster_height) + context.dim(R.dimen.tv_spacing_large) + context.dim(R.dimen.tv_spacing_medium)
            MediaCardPresentation.LANDSCAPE -> context.dim(R.dimen.tv_landscape_height) + context.dim(R.dimen.tv_spacing_large) + context.dim(R.dimen.tv_spacing_medium)
            MediaCardPresentation.SQUARE -> context.dim(R.dimen.tv_square_height) + context.dim(R.dimen.tv_spacing_large) + context.dim(R.dimen.tv_spacing_medium)
        }
        parent.addView(recycler, LinearLayout.LayoutParams(-1, height))
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false, margin: Int = 0, leftPadding: Int = 0, isPx: Boolean = false): TextView =
        TextView(requireContext()).apply {
            text = value
            if (isPx) setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size) else textSize = size
            setTextColor(requireContext().getColor(color))
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            setPadding(leftPadding, 0, 0, 0)
            if (margin > 0) layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = requireContext().dim(R.dimen.tv_spacing_small).coerceAtLeast(margin) }
        }

    private inner class ShelfAdapter(
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
            
            holder.itemView.setOnClickListener {
                MediaDetailsActivity.start(it.context, item.id)
            }

            holder.itemView.setOnFocusChangeListener { v, focused ->
                com.piggie.tv.theme.PTVShapes.applyFocusEffect(v, focused)
                if (focused) {
                    BackdropManager.update(backdropView, backdropOverlay, session, item, api)
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
