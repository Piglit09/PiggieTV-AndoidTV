package com.piggie.tv.ui.library

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.discovery.DiscoveryBrowseRequest
import com.piggie.tv.data.discovery.DiscoveryFilter
import com.piggie.tv.data.discovery.DiscoveryFilterType
import com.piggie.tv.data.discovery.DiscoveryPageRequest
import com.piggie.tv.data.discovery.DiscoveryManager
import com.piggie.tv.data.discovery.DiscoveryBrowserResult
import com.piggie.tv.data.discovery.ShelfStatus
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.theme.PTVTypography
import com.piggie.tv.ui.layout.TvGridLayoutManager
import com.piggie.tv.ui.player.MediaDetailsActivity
import com.piggie.tv.ui.rendering.applyRenderingTuning
import com.piggie.tv.ui.widgets.MediaCardFactory
import com.piggie.tv.ui.widgets.MediaCardHolder
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes

class LibraryBrowserActivity : AppCompatActivity() {
    private val api by lazy { JellyfinNativeApi(this) }
    private val store by lazy { SecureSessionStore(this) }
    private lateinit var session: NativeSession
    private lateinit var stateContainer: FrameLayout
    private lateinit var browseRequest: DiscoveryBrowseRequest
    private var loadHandle: DiscoveryPageRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = store.read() ?: run {
            finish()
            return
        }
        browseRequest = requestFromIntent(intent)
        setupView()
        loadData()
    }

    override fun onDestroy() {
        loadHandle?.cancel()
        loadHandle = null
        super.onDestroy()
    }

    private fun setupView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PTVColors.background)
            setPadding(
                dim(R.dimen.tv_screen_margin_horizontal),
                dim(R.dimen.tv_screen_margin_vertical),
                dim(R.dimen.tv_screen_margin_horizontal),
                0
            )
        }
        root.addView(
            TextView(this).apply {
                text = browseRequest.title
                PTVTypography.pageTitle(this)
                setPadding(0, 0, 0, dim(R.dimen.tv_spacing_large))
            }
        )
        stateContainer = FrameLayout(this)
        root.addView(stateContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun loadData() {
        loadHandle?.cancel()
        showLoading()
        loadHandle = DiscoveryManager.loadBrowser(api, session, browseRequest) { result ->
            runOnUiThread {
                when (result.status) {
                    ShelfStatus.READY -> showContent(result.items)
                    else -> showFailure(result.status, result.message)
                }
            }
        }
    }

    private fun showLoading() {
        stateContainer.removeAllViews()
        stateContainer.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(ProgressBar(context).apply { isIndeterminate = true })
                addView(
                    TextView(context).apply {
                        text = "Loading ${browseRequest.title}…"
                        setTextSizeRes(R.dimen.tv_text_size_body)
                        setTextColor(PTVColors.textSecondary)
                        gravity = Gravity.CENTER
                        setPadding(0, dim(R.dimen.tv_spacing_medium), 0, 0)
                    }
                )
            },
            FrameLayout.LayoutParams(-1, -1)
        )
    }

    private fun showFailure(status: ShelfStatus, message: String?) {
        stateContainer.removeAllViews()
        val retry = Button(this).apply {
            text = "Retry"
            isAllCaps = false
            setTextSizeRes(R.dimen.tv_text_size_body)
            setBackgroundResource(R.drawable.tv_button_secondary)
            setTextColor(PTVColors.textPrimary)
            setOnClickListener { loadData() }
        }
        stateContainer.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(
                    TextView(context).apply {
                        text = status.name.replace('_', ' ')
                        PTVTypography.sectionTitle(this)
                        gravity = Gravity.CENTER
                    }
                )
                addView(
                    TextView(context).apply {
                        text = message ?: "Unable to load this browser."
                        setTextSizeRes(R.dimen.tv_text_size_body)
                        setTextColor(PTVColors.textSecondary)
                        gravity = Gravity.CENTER
                        setPadding(0, dim(R.dimen.tv_spacing_small), 0, dim(R.dimen.tv_spacing_medium))
                    }
                )
                addView(
                    retry,
                    LinearLayout.LayoutParams(
                        dim(R.dimen.tv_hero_button_width),
                        dim(R.dimen.tv_hero_button_height)
                    )
                )
            },
            FrameLayout.LayoutParams(-1, -1)
        )
        retry.requestFocus()
    }

    private fun showContent(items: List<MediaItem>) {
        stateContainer.removeAllViews()
        val manager = TvGridLayoutManager(this, 7)
        val recycler = RecyclerView(this).apply {
            layoutManager = manager
            adapter = BrowserAdapter(items, session, api)
            applyRenderingTuning(manager, 2)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dim(R.dimen.tv_spacing_small), 0, dim(R.dimen.tv_spacing_large))
        }
        stateContainer.addView(recycler, FrameLayout.LayoutParams(-1, -1))
        recycler.post {
            recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private class BrowserAdapter(
        private val items: List<MediaItem>,
        private val session: NativeSession,
        private val api: JellyfinNativeApi
    ) : RecyclerView.Adapter<MediaCardHolder>() {

        init {
            setHasStableIds(true)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaCardHolder =
            MediaCardHolder(MediaCardFactory.createView(parent, MediaCardPresentation.POSTER))

        override fun onBindViewHolder(holder: MediaCardHolder, position: Int) {
            val item = items[position]
            MediaCardFactory.bindView(holder, item, MediaCardPresentation.POSTER, session, api)
            holder.itemView.setOnClickListener { MediaDetailsActivity.start(it.context, item) }
        }

        override fun getItemCount(): Int = items.size
        override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

        override fun onViewRecycled(holder: MediaCardHolder) {
            holder.image.dispose()
            holder.itemView.setOnClickListener(null)
            super.onViewRecycled(holder)
        }
    }

    private fun requestFromIntent(intent: Intent): DiscoveryBrowseRequest {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Library"
        val type = intent.getStringExtra(EXTRA_FILTER_TYPE)
            ?.let { runCatching { DiscoveryFilterType.valueOf(it) }.getOrNull() }
            ?: DiscoveryFilterType.LIBRARY
        val itemTypes = intent.getStringExtra(EXTRA_ITEM_TYPES)
            ?.split(',')
            ?.filter(String::isNotBlank)
            .orEmpty()
            .ifEmpty { listOf("Movie", "Series") }
        return DiscoveryBrowseRequest(
            title = title,
            filter = DiscoveryFilter(type, intent.getStringExtra(EXTRA_FILTER_VALUE) ?: title),
            itemTypes = itemTypes,
            libraryId = intent.getStringExtra(EXTRA_LIBRARY_ID)
        )
    }

    companion object {
        private const val EXTRA_LIBRARY_ID = "lib_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_FILTER_TYPE = "filter_type"
        private const val EXTRA_FILTER_VALUE = "filter_value"
        private const val EXTRA_ITEM_TYPES = "item_types"

        fun start(context: Context, request: DiscoveryBrowseRequest) {
            context.startActivity(Intent(context, LibraryBrowserActivity::class.java).apply {
                putExtra(EXTRA_TITLE, request.title)
                putExtra(EXTRA_FILTER_TYPE, request.filter.type.name)
                putExtra(EXTRA_FILTER_VALUE, request.filter.value)
                putExtra(EXTRA_ITEM_TYPES, request.itemTypes.joinToString(","))
                putExtra(EXTRA_LIBRARY_ID, request.libraryId)
            })
        }

        fun start(
            context: Context,
            title: String,
            libraryId: String? = null,
            genre: String? = null,
            studio: String? = null
        ) {
            val filter = when {
                !genre.isNullOrBlank() -> DiscoveryFilter(DiscoveryFilterType.GENRE, genre)
                !studio.isNullOrBlank() -> DiscoveryFilter(DiscoveryFilterType.STUDIO, studio)
                else -> DiscoveryFilter(DiscoveryFilterType.LIBRARY, title)
            }
            start(
                context,
                DiscoveryBrowseRequest(
                    title = title,
                    filter = filter,
                    libraryId = libraryId
                )
            )
        }
    }
}
