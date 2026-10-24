package com.piggie.tv.ui.library

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.piggie.tv.R
import com.piggie.tv.auth.AuthSessionCoordinator
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.discovery.DiscoveryBrowseRequest
import com.piggie.tv.data.discovery.DiscoveryBrowseSort
import com.piggie.tv.data.discovery.DiscoveryBrowseWatchFilter
import com.piggie.tv.data.discovery.DiscoveryFilter
import com.piggie.tv.data.discovery.DiscoveryFilterType
import com.piggie.tv.data.discovery.DiscoveryPageRequest
import com.piggie.tv.data.discovery.DiscoveryManager
import com.piggie.tv.data.discovery.ShelfStatus
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.theme.PTVGlassTier
import com.piggie.tv.theme.PTVMaterials
import com.piggie.tv.theme.PTVShapes
import com.piggie.tv.theme.PTVTypography
import com.piggie.tv.ui.layout.TvGridLayoutManager
import com.piggie.tv.ui.player.MediaDetailsActivity
import com.piggie.tv.ui.rendering.applyRenderingTuning
import com.piggie.tv.ui.widgets.MediaCardFactory
import com.piggie.tv.ui.widgets.MediaCardHolder
import com.piggie.tv.ui.widgets.MediaCardArtworkSize
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes
import kotlin.math.roundToInt

class LibraryBrowserActivity : AppCompatActivity() {
    private val api by lazy { JellyfinNativeApi(this) }
    private val store by lazy { SecureSessionStore(this) }
    private lateinit var session: NativeSession
    private lateinit var stateContainer: FrameLayout
    private lateinit var browseRequest: DiscoveryBrowseRequest
    private lateinit var stateStore: LibraryBrowseStateStore
    private lateinit var header: PremiumLibraryHeaderView
    private lateinit var sortButton: Button
    private lateinit var filterButton: Button
    private lateinit var viewButton: Button
    private var compactView = true
    private var lastItems = emptyList<MediaItem>()
    private val resultGate = LibraryBrowserResultGate()
    private var loadHandle: DiscoveryPageRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = AuthSessionCoordinator.validatedSession(store) ?: run {
            finish()
            return
        }
        browseRequest = requestFromIntent(intent)
        stateStore = LibraryBrowseStateStore(this)
        browseRequest.libraryName?.let { library ->
            val (sort, watchFilter) = stateStore.read(session, library)
            browseRequest = browseRequest.copy(sort = sort, watchFilter = watchFilter)
        }
        setupView()
        loadData()
    }

    override fun onDestroy() {
        resultGate.retire()
        loadHandle?.cancel()
        loadHandle = null
        if (::stateContainer.isInitialized) clearStateContainer()
        super.onDestroy()
    }

    private fun clearStateContainer() {
        for (index in 0 until stateContainer.childCount) {
            (stateContainer.getChildAt(index) as? RecyclerView)?.adapter = null
        }
        stateContainer.removeAllViews()
    }

    private fun setupView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.ptv_library_page_background)
            clipChildren = false
            clipToPadding = false
            setPadding(
                dim(R.dimen.tv_screen_margin_horizontal),
                0,
                dim(R.dimen.tv_screen_margin_horizontal),
                0
            )
        }
        header = PremiumLibraryHeaderView(this, browseRequest.title,
            topClearance = dim(R.dimen.tv_spacing_large))
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dim(R.dimen.tv_spacing_medium))
        }
        sortButton = controlButton(R.id.ptv_library_sort, sortLabel()) { cycleSort() }
        filterButton = controlButton(R.id.ptv_library_filter, filterLabel()) { cycleFilter() }
        viewButton = controlButton(R.id.ptv_library_view, viewLabel()) {
            compactView = !compactView
            viewButton.text = viewLabel()
            if (lastItems.isNotEmpty()) showContent(lastItems)
            viewButton.requestFocus()
        }
        listOf(sortButton, filterButton, viewButton).forEach { button ->
            controls.addView(button, LinearLayout.LayoutParams(
                dim(R.dimen.tv_hero_button_width),
                dim(R.dimen.tv_hero_button_height)
            ).apply { marginEnd = dim(R.dimen.tv_spacing_medium) })
        }
        root.addView(controls)
        stateContainer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        root.addView(stateContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun controlButton(id: Int, label: String, action: () -> Unit): Button = Button(this).apply {
        this.id = id
        text = label
        isAllCaps = false
        setTextSizeRes(R.dimen.tv_text_size_body)
        setTextColor(PTVColors.textPrimary)
        background = PTVMaterials.drawable(this@LibraryBrowserActivity, PTVGlassTier.LIGHT,
            dim(R.dimen.tv_spacing_medium).toFloat())
        onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            PTVShapes.applyFocusEffect(view, focused)
        }
        setOnClickListener { action() }
    }

    private fun sortLabel(): String = when (browseRequest.sort) {
        DiscoveryBrowseSort.RECENT -> "Sort: Recent"
        DiscoveryBrowseSort.TITLE -> "Sort: A–Z"
        DiscoveryBrowseSort.RATING -> "Sort: Rating"
    }

    private fun filterLabel(): String = when (browseRequest.watchFilter) {
        DiscoveryBrowseWatchFilter.ALL -> "Filter: All"
        DiscoveryBrowseWatchFilter.UNPLAYED -> "Filter: Unplayed"
        DiscoveryBrowseWatchFilter.IN_PROGRESS -> "Filter: In progress"
    }

    private fun viewLabel(): String = if (compactView) "View: Compact" else "View: Comfort"

    private fun cycleSort() {
        val next = when (browseRequest.sort) {
            DiscoveryBrowseSort.RECENT -> DiscoveryBrowseSort.TITLE
            DiscoveryBrowseSort.TITLE -> DiscoveryBrowseSort.RATING
            DiscoveryBrowseSort.RATING -> DiscoveryBrowseSort.RECENT
        }
        browseRequest = browseRequest.copy(sort = next)
        sortButton.text = sortLabel()
        saveScopePreferences()
        loadData()
    }

    private fun cycleFilter() {
        val next = when (browseRequest.watchFilter) {
            DiscoveryBrowseWatchFilter.ALL -> DiscoveryBrowseWatchFilter.UNPLAYED
            DiscoveryBrowseWatchFilter.UNPLAYED -> DiscoveryBrowseWatchFilter.IN_PROGRESS
            DiscoveryBrowseWatchFilter.IN_PROGRESS -> DiscoveryBrowseWatchFilter.ALL
        }
        browseRequest = browseRequest.copy(watchFilter = next)
        filterButton.text = filterLabel()
        saveScopePreferences()
        loadData()
    }

    private fun saveScopePreferences() {
        browseRequest.libraryName?.let { library ->
            stateStore.save(session, library, browseRequest.sort, browseRequest.watchFilter)
        }
    }

    private fun loadData() {
        val generation = resultGate.start()
        loadHandle?.cancel()
        lastItems = emptyList()
        showLoading()
        loadHandle = DiscoveryManager.loadBrowser(api, session, browseRequest) { result ->
            runOnUiThread {
                if (!resultGate.accepts(generation)) return@runOnUiThread
                when (result.status) {
                    ShelfStatus.READY -> if (result.items.isEmpty()) {
                        showFailure(ShelfStatus.EMPTY, "No titles match these controls.")
                    } else showContent(result.items)
                    else -> showFailure(result.status, result.message)
                }
            }
        }
    }

    private fun showLoading() {
        clearStateContainer()
        header.setShowingCount(null)
        stateContainer.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP
                setPadding(0, dim(R.dimen.tv_spacing_medium), 0, 0)
                addView(
                    TextView(context).apply {
                        text = "Loading ${browseRequest.title}…"
                        setTextSizeRes(R.dimen.tv_text_size_body)
                        setTextColor(PTVColors.textSecondary)
                    }, LinearLayout.LayoutParams(-1, -2)
                )
                val placeholders = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dim(R.dimen.tv_spacing_medium), 0, 0)
                }
                repeat(6) {
                    placeholders.addView(View(context).apply {
                        background = PTVMaterials.drawable(context, PTVGlassTier.LIGHT,
                            context.dim(R.dimen.tv_spacing_small).toFloat())
                    }, LinearLayout.LayoutParams(
                        dim(R.dimen.tv_poster_width), dim(R.dimen.tv_poster_height)
                    ).apply { marginEnd = dim(R.dimen.tv_card_spacing) })
                }
                addView(placeholders)
            },
            FrameLayout.LayoutParams(-1, -1)
        )
    }

    private fun showFailure(status: ShelfStatus, message: String?) {
        clearStateContainer()
        val retry = Button(this).apply {
            val clearFilter = status == ShelfStatus.EMPTY &&
                browseRequest.watchFilter != DiscoveryBrowseWatchFilter.ALL
            text = if (clearFilter) "Clear filter" else "Retry"
            isAllCaps = false
            setTextSizeRes(R.dimen.tv_text_size_body)
            background = PTVMaterials.drawable(this@LibraryBrowserActivity, PTVGlassTier.LIGHT,
                dim(R.dimen.tv_spacing_medium).toFloat())
            setTextColor(PTVColors.textPrimary)
            onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
                PTVShapes.applyFocusEffect(view, focused)
            }
            setOnClickListener {
                if (clearFilter) {
                    browseRequest = browseRequest.copy(watchFilter = DiscoveryBrowseWatchFilter.ALL)
                    filterButton.text = filterLabel()
                    saveScopePreferences()
                }
                loadData()
            }
        }
        stateContainer.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(
                    TextView(context).apply {
                        text = if (status == ShelfStatus.EMPTY) "No matching titles"
                            else if (status == ShelfStatus.MISSING_LIBRARY) "Library unavailable"
                            else "Unable to load library"
                        PTVTypography.sectionTitle(this)
                        gravity = Gravity.CENTER
                    }
                )
                addView(
                    TextView(context).apply {
                        text = message ?: "Unable to load this library."
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
        if (!sortButton.hasFocus() && !filterButton.hasFocus() && !viewButton.hasFocus()) {
            retry.requestFocus()
        }
    }

    private fun showContent(items: List<MediaItem>) {
        lastItems = items
        header.setShowingCount(items.size)
        clearStateContainer()
        val movies = browseRequest.libraryName == "Movies"
        val columns = if (compactView) 7 else if (movies) 6 else 5
        val artworkSize = if (movies) {
            val width = dim(if (compactView) R.dimen.ptv_movies_compact_poster_width else R.dimen.ptv_movies_comfort_poster_width)
            MediaCardArtworkSize(width, (width * 1.44f).roundToInt())
        } else null
        val manager = TvGridLayoutManager(this, columns)
        val recycler = RecyclerView(this).apply {
            layoutManager = manager
            adapter = BrowserAdapter(items, session, api, artworkSize)
            applyRenderingTuning(manager, 2)
            // Scrolled Movies rows must stay below the fixed header and controls. The
            // existing padding and inset card border retain focus clearance inside the grid.
            clipChildren = movies
            clipToPadding = movies
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dim(R.dimen.tv_spacing_small), 0, dim(R.dimen.tv_spacing_large))
            if (movies) addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    outRect.bottom = dim(R.dimen.tv_spacing_medium)
                }
            })
        }
        stateContainer.addView(recycler, FrameLayout.LayoutParams(-1, -1))
        recycler.post {
            if (!sortButton.hasFocus() && !filterButton.hasFocus() && !viewButton.hasFocus()) {
                recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }

    private class BrowserAdapter(
        private val items: List<MediaItem>,
        private val session: NativeSession,
        private val api: JellyfinNativeApi,
        private val artworkSize: MediaCardArtworkSize?
    ) : RecyclerView.Adapter<MediaCardHolder>() {

        init {
            setHasStableIds(true)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaCardHolder =
            MediaCardHolder(MediaCardFactory.createView(
                parent, MediaCardPresentation.POSTER, premiumMaterial = true,
                browseTitle = true, artworkSize = artworkSize
            ).apply {
                artworkSize?.let { layoutParams = RecyclerView.LayoutParams(it.width, ViewGroup.LayoutParams.WRAP_CONTENT) }
            })

        override fun onBindViewHolder(holder: MediaCardHolder, position: Int) {
            val item = items[position]
            MediaCardFactory.bindView(holder, item, MediaCardPresentation.POSTER, session, api)
            holder.itemView.setTag(R.id.ptv_discovery_item_id, item.id)
            holder.itemView.setOnClickListener { MediaDetailsActivity.start(it.context, item) }
        }

        override fun getItemCount(): Int = items.size
        override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

        override fun onViewRecycled(holder: MediaCardHolder) {
            MediaCardFactory.recycleView(holder)
            holder.itemView.setTag(R.id.ptv_discovery_item_id, null)
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
            libraryId = intent.getStringExtra(EXTRA_LIBRARY_ID),
            libraryName = intent.getStringExtra(EXTRA_LIBRARY_NAME),
            sort = intent.getStringExtra(EXTRA_SORT)
                ?.let { runCatching { DiscoveryBrowseSort.valueOf(it) }.getOrNull() }
                ?: DiscoveryBrowseSort.RECENT,
            watchFilter = intent.getStringExtra(EXTRA_WATCH_FILTER)
                ?.let { runCatching { DiscoveryBrowseWatchFilter.valueOf(it) }.getOrNull() }
                ?: DiscoveryBrowseWatchFilter.ALL
        )
    }

    companion object {
        private const val EXTRA_LIBRARY_ID = "lib_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_FILTER_TYPE = "filter_type"
        private const val EXTRA_FILTER_VALUE = "filter_value"
        private const val EXTRA_ITEM_TYPES = "item_types"
        private const val EXTRA_LIBRARY_NAME = "library_name"
        private const val EXTRA_SORT = "sort"
        private const val EXTRA_WATCH_FILTER = "watch_filter"

        fun start(context: Context, request: DiscoveryBrowseRequest) {
            context.startActivity(Intent(context, LibraryBrowserActivity::class.java).apply {
                putExtra(EXTRA_TITLE, request.title)
                putExtra(EXTRA_FILTER_TYPE, request.filter.type.name)
                putExtra(EXTRA_FILTER_VALUE, request.filter.value)
                putExtra(EXTRA_ITEM_TYPES, request.itemTypes.joinToString(","))
                putExtra(EXTRA_LIBRARY_ID, request.libraryId)
                putExtra(EXTRA_LIBRARY_NAME, request.libraryName)
                putExtra(EXTRA_SORT, request.sort.name)
                putExtra(EXTRA_WATCH_FILTER, request.watchFilter.name)
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
