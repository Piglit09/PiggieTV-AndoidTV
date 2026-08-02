package com.piggie.tv.ui.reading

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.piggie.tv.R
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.*
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.ui.player.MediaDetailsActivity
import com.piggie.tv.ui.layout.TvLinearLayoutManager
import com.piggie.tv.ui.shared.BackdropManager
import com.piggie.tv.ui.rendering.TvRenderingRuntime
import com.piggie.tv.ui.rendering.applyRenderingTuning
import com.piggie.tv.ui.widgets.MediaCardFactory
import com.piggie.tv.ui.widgets.MediaCardHolder
import com.piggie.tv.util.dim
import com.piggie.tv.util.dimFloat
import com.piggie.tv.util.sp
import kotlin.concurrent.thread

class ReadingFragment : Fragment() {
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
            alpha = 0.4f
            visibility = if (TvRenderingRuntime.features().dynamicBrowsingBackdrops) View.VISIBLE else View.GONE
        }
        root.addView(backdropView, ViewGroup.LayoutParams(-1, -1))

        backdropOverlay = View(requireContext()).apply {
            setBackgroundResource(R.drawable.hero_gradient_overlay)
            visibility = if (TvRenderingRuntime.features().dynamicBrowsingBackdrops) View.VISIBLE else View.GONE
        }
        root.addView(backdropOverlay, ViewGroup.LayoutParams(-1, -1))

        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = true
            isSmoothScrollingEnabled = false
            clipToPadding = false
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val parallax = scrollY * -0.5f
                backdropView.translationY = parallax
                backdropOverlay.translationY = parallax
            }
        }
        pageContent = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, requireContext().dim(R.dimen.tv_spacing_large))
        }
        scroll.addView(pageContent)
        root.addView(scroll)

        loadReading()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root.isFocusable = false
        root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    private fun loadReading() {
        if (loading) return
        loading = true

        val context = requireContext()
        val titleLabel = label("Reading", context.sp(R.dimen.tv_text_size_page_title), R.color.tv_text_primary, true, margin = 20, leftPadding = context.dim(R.dimen.tv_screen_margin_horizontal))
        pageContent.addView(titleLabel)

        val loadingLabel = label("Loading library...", 16f, R.color.tv_text_secondary, margin = 20, leftPadding = context.dim(R.dimen.tv_screen_margin_horizontal))
        pageContent.addView(loadingLabel)

        thread(start = true) {
            runCatching {
                api.loadReadingHomeIncrementally(session) { shelf ->
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
                    pageContent.removeAllViews()
                    val errorView = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = android.view.Gravity.CENTER
                        setPadding(requireContext().dim(R.dimen.tv_screen_margin_horizontal), 100, requireContext().dim(R.dimen.tv_screen_margin_horizontal), 0)
                        addView(TextView(context).apply {
                            text = "Reading Library Unavailable"
                            textSize = 20f
                            setTextColor(requireContext().getColor(R.color.tv_text_primary))
                        })
                        addView(TextView(context).apply {
                            text = error.message ?: "Failed to connect to reading library"
                            textSize = 16f
                            setTextColor(requireContext().getColor(R.color.tv_text_secondary))
                            setPadding(0, 20, 0, 0)
                        })
                        val retry = android.widget.Button(context).apply {
                            text = "Retry"
                            setOnClickListener {
                                pageContent.removeAllViews()
                                loadReading()
                            }
                        }
                        addView(retry, LinearLayout.LayoutParams(requireContext().dim(R.dimen.tv_hero_button_width), -2).apply { topMargin = 40 })
                        retry.requestFocus()
                    }
                    pageContent.addView(errorView)
                }
            }
        }
    }

    private fun addShelf(parent: LinearLayout, shelf: MediaShelf) {
        val context = requireContext()
        val title = label(shelf.title, context.dimFloat(R.dimen.tv_text_size_section_title), R.color.tv_text_primary, true, margin = 32, leftPadding = context.dim(R.dimen.tv_screen_margin_horizontal), isPx = true)
        
        val manager = TvLinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        val recycler = RecyclerView(context).apply {
            layoutManager = manager
            adapter = ReadingShelfAdapter(shelf.items, shelf.presentation, session, api)
            applyRenderingTuning(manager, 3)
            clipChildren = false
            clipToPadding = false
            setPadding(context.dim(R.dimen.tv_screen_margin_horizontal), context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_screen_margin_horizontal), context.dim(R.dimen.tv_spacing_small))
        }
        
        val h = when (shelf.presentation) {
            MediaCardPresentation.POSTER -> context.dim(R.dimen.tv_poster_height) + context.dim(R.dimen.tv_spacing_large) + context.dim(R.dimen.tv_spacing_medium)
            MediaCardPresentation.SQUARE -> context.dim(R.dimen.tv_square_height) + context.dim(R.dimen.tv_spacing_large) + context.dim(R.dimen.tv_spacing_medium)
            else -> 300
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(recycler, LinearLayout.LayoutParams(-1, h))
        }
        parent.addView(container)
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

    private inner class ReadingShelfAdapter(
        private val items: List<MediaItem>,
        private val presentation: MediaCardPresentation,
        private val session: NativeSession,
        private val api: JellyfinNativeApi
    ) : RecyclerView.Adapter<MediaCardHolder>() {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaCardHolder {
            return MediaCardHolder(MediaCardFactory.createView(parent, presentation))
        }

        override fun onBindViewHolder(holder: MediaCardHolder, position: Int) {
            val item = items[position]
            MediaCardFactory.bindView(holder, item, presentation, session, api)
            
            holder.itemView.setOnClickListener {
                MediaDetailsActivity.start(it.context, item)
            }

            holder.itemView.setOnFocusChangeListener { v, focused ->
                com.piggie.tv.theme.PTVShapes.applyFocusEffect(v, focused)
                if (focused) {
                    BackdropManager.updateBrowsing(backdropView, backdropOverlay, session, item, api)
                }
            }
        }

        override fun getItemCount(): Int = items.size
        override fun onViewRecycled(holder: MediaCardHolder) {
            MediaCardFactory.recycleView(holder)
            holder.itemView.setOnClickListener(null)
            super.onViewRecycled(holder)
        }
    }
}
