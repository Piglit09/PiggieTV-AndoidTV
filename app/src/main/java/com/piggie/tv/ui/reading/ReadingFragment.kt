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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.piggie.tv.R
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.*
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.ui.player.MediaDetailsActivity
import com.piggie.tv.ui.shared.BackdropManager
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
        val title = label("Reading", context.sp(R.dimen.tv_text_size_page_title), R.color.tv_text_primary, true, margin = 20, leftPadding = context.dim(R.dimen.tv_screen_margin_horizontal))
        pageContent.addView(title)

        val loadingLabel = label("Loading library...", 16f, R.color.tv_text_secondary, margin = 20, leftPadding = context.dim(R.dimen.tv_screen_margin_horizontal))
        pageContent.addView(loadingLabel)

        thread(start = true) {
            runCatching {
                api.loadReadingHomeIncrementally(session) { shelf ->
                    activity?.runOnUiThread {
                        loadingLabel.visibility = View.GONE
                        addShelf(pageContent, shelf)
                        
                        // Update backdrop for the first item in the first shelf if focused?
                        // For now just update on focus change in adapter
                    }
                }
            }.onSuccess {
                activity?.runOnUiThread { loading = false }
            }.onFailure {
                activity?.runOnUiThread {
                    loading = false
                    loadingLabel.text = "No reading items found."
                }
            }
        }
    }

    private fun addShelf(parent: LinearLayout, shelf: MediaShelf) {
        val context = requireContext()
        parent.addView(label(shelf.title, context.dimFloat(R.dimen.tv_text_size_section_title), R.color.tv_text_primary, true, margin = 32, leftPadding = context.dim(R.dimen.tv_screen_margin_horizontal), isPx = true))
        
        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            adapter = ReadingShelfAdapter(shelf.items, shelf.presentation, session, api)
            clipChildren = false
            clipToPadding = false
            setPadding(context.dim(R.dimen.tv_screen_margin_horizontal), context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_screen_margin_horizontal), context.dim(R.dimen.tv_spacing_small))
        }
        
        val h = when (shelf.presentation) {
            MediaCardPresentation.POSTER -> context.dim(R.dimen.tv_poster_height) + context.dim(R.dimen.tv_spacing_large) + context.dim(R.dimen.tv_spacing_medium)
            else -> 300
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

    private inner class ReadingShelfAdapter(
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
