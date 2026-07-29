package com.piggie.tv.ui.music

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.playback.MusicPlaybackManager
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import com.piggie.tv.diagnostics.PtvRedactor
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.theme.PTVShapes
import com.piggie.tv.ui.layout.TvCardSpacingDecoration
import com.piggie.tv.ui.layout.TvHorizontalRecyclerView
import com.piggie.tv.ui.layout.TvLinearLayoutManager
import com.piggie.tv.ui.rendering.TvFocusIndicator
import com.piggie.tv.ui.rendering.TvRenderingRuntime
import com.piggie.tv.ui.rendering.applyRenderingTuning
import com.piggie.tv.ui.shared.TextSanitizer
import com.piggie.tv.ui.widgets.MediaCardFactory
import com.piggie.tv.ui.widgets.MediaCardHolder
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Native, remote-first music details surface.
 *
 * The page is one vertical RecyclerView: tracks are adapter rows rather than eagerly-created
 * children in a ScrollView, while related content is a bounded, recycled horizontal shelf.
 */
class MusicDetailsActivity : AppCompatActivity() {
    private val api by lazy { JellyfinNativeApi(this) }
    private val store by lazy { SecureSessionStore(this) }

    private lateinit var session: NativeSession
    private lateinit var itemId: String
    private lateinit var root: FrameLayout
    private lateinit var backdrop: ImageView
    private lateinit var page: RecyclerView
    private lateinit var pageLayoutManager: TvLinearLayoutManager
    private lateinit var pageAdapter: MusicDetailsAdapter

    private var item: MediaItem? = null
    private var songs: List<MediaItem> = emptyList()
    private var sourceTrackCount = 0
    private var relevantAlbums: List<MediaItem> = emptyList()
    private var supportingArtist: MediaItem? = null
    private var relatedState: RelatedState = RelatedState.Loading
    private var currentFocusKey: String? = null
    private var pendingSavedState: PageState? = null
    private var openedChildDetails = false
    private var requestGeneration = 0
    private var relatedRequestGeneration = 0
    private var artworkGeneration = 0
    private var initialLoadStartedAt = 0L

    @Volatile
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        super.onCreate(savedInstanceState)
        window.setWindowAnimations(0)

        session = store.read() ?: run {
            finish()
            return
        }
        itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: run {
            finish()
            return
        }
        pendingSavedState = savedInstanceState?.let {
            PageState(
                firstVisiblePosition = it.getInt(STATE_FIRST_POSITION, 0),
                firstVisibleOffset = it.getInt(STATE_FIRST_OFFSET, 0),
                focusKey = it.getString(STATE_FOCUS_KEY)
            )
        }

        PtvDiagnosticsManager.routeRequested(ROUTE_NAME)
        setupPage()
        loadDetails()
    }

    override fun onResume() {
        super.onResume()
        if (!::page.isInitialized) return
        PtvDiagnosticsManager.routeVisible(ROUTE_NAME, diagnosticFocus(currentFocusKey))
        if (openedChildDetails) {
            openedChildDetails = false
            page.post { restoreFocus(currentFocusKey) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (!::page.isInitialized) {
            super.onSaveInstanceState(outState)
            return
        }
        val state = capturePageState()
        outState.putInt(STATE_FIRST_POSITION, state.firstVisiblePosition)
        outState.putInt(STATE_FIRST_OFFSET, state.firstVisibleOffset)
        outState.putString(STATE_FOCUS_KEY, state.focusKey)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        destroyed = true
        requestGeneration++
        relatedRequestGeneration++
        if (::backdrop.isInitialized) backdrop.dispose()
        api.cancelInFlight()
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun setupPage() {
        root = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.tv_app_background)
        }
        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageResource(R.drawable.music_hero_branded_background)
        }
        val gradient = View(this).apply {
            setBackgroundResource(R.drawable.hero_gradient_overlay)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        pageLayoutManager = TvLinearLayoutManager(this, RecyclerView.VERTICAL, false)
        pageAdapter = MusicDetailsAdapter()
        page = RecyclerView(this).apply {
            id = View.generateViewId()
            layoutManager = pageLayoutManager
            adapter = pageAdapter
            itemAnimator = null
            setHasFixedSize(true)
            setItemViewCacheSize(PAGE_VIEW_CACHE)
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(
                dim(R.dimen.tv_screen_margin_horizontal),
                dim(R.dimen.tv_screen_margin_vertical),
                dim(R.dimen.tv_screen_margin_horizontal),
                dim(R.dimen.tv_spacing_large)
            )
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        root.addView(gradient, FrameLayout.LayoutParams(-1, -1))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        pageAdapter.submitRows(
            listOf(PageRow.Message("initial-loading", "Loading music details\u2026", true, false))
        )
    }

    private fun loadDetails() {
        val generation = ++requestGeneration
        initialLoadStartedAt = SystemClock.elapsedRealtime()
        relatedRequestGeneration++
        item = null
        songs = emptyList()
        sourceTrackCount = 0
        relevantAlbums = emptyList()
        supportingArtist = null
        pageAdapter.submitRows(
            listOf(PageRow.Message("initial-loading", "Loading music details\u2026", true, false))
        )
        diagnose(
            "load_started",
            mapOf("item" to masked(itemId))
        )

        thread(name = "ptv-music-details", start = true) {
            runCatching {
                val details = api.loadItem(session, itemId)
                val loadedSongs = when (details.type) {
                    "MusicAlbum" -> api.loadAlbumSongs(session, details.id)
                    "MusicArtist" -> api.loadArtistSongs(session, details.id)
                    "Playlist" -> api.loadPlaylistSongs(session, details.id)
                    else -> emptyList()
                }
                val initialSongs = MusicDetailsTrackPolicy.initial(loadedSongs)
                val albums = if (details.type == "MusicArtist") {
                    runCatching {
                        api.loadArtistAlbums(session, details.id)
                            .asSequence()
                            .filter { it.type == "MusicAlbum" }
                            .take(SUPPORTING_ALBUM_LIMIT)
                            .toList()
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val artistId = details.artistId
                    ?: initialSongs.firstNotNullOfOrNull(MediaItem::artistId)
                val artist = artistId
                    ?.takeIf { it.isNotBlank() && it != details.id }
                    ?.let { runCatching { api.loadItem(session, it) }.getOrNull() }
                DetailsPayload(
                    item = details,
                    songs = initialSongs,
                    sourceTrackCount = loadedSongs.size,
                    relevantAlbums = albums,
                    artist = artist
                )
            }.onSuccess { payload ->
                runOnUiThread {
                    if (!isCurrent(generation)) return@runOnUiThread
                    item = payload.item
                    songs = payload.songs
                    sourceTrackCount = payload.sourceTrackCount
                    relevantAlbums = payload.relevantAlbums
                    supportingArtist = payload.artist
                    relatedState = RelatedState.Loading
                    renderContent(preservePosition = false)
                    loadPageArtwork(payload.item)
                    PtvDiagnosticsManager.routeFirstContent(ROUTE_NAME)
                    diagnose(
                        "content_ready",
                        mapOf(
                            "item" to masked(payload.item.id),
                            "type" to safeType(payload.item.type),
                            "trackCount" to payload.songs.size.toString(),
                            "sourceTrackCount" to payload.sourceTrackCount.toString(),
                            "tracksTruncated" to
                                (payload.sourceTrackCount > payload.songs.size).toString(),
                            "initialContentMs" to
                                (SystemClock.elapsedRealtime() - initialLoadStartedAt).toString(),
                            "artworkChain" to artworkReasons(payload.item)
                        )
                    )
                    loadRelated(payload.item, generation)
                }
            }.onFailure { error ->
                runOnUiThread {
                    if (!isCurrent(generation)) return@runOnUiThread
                    pageAdapter.submitRows(
                        listOf(
                            PageRow.Message(
                                key = "initial-error",
                                text = "Music details could not be loaded.",
                                showProgress = false,
                                retryable = true
                            )
                        )
                    )
                    page.post { restoreFocus(INITIAL_RETRY_KEY) }
                    diagnose(
                        "load_error",
                        mapOf(
                            "item" to masked(itemId),
                            "error" to error.javaClass.simpleName
                        )
                    )
                }
            }
        }
    }

    private fun loadRelated(details: MediaItem, detailsGeneration: Int) {
        val relatedGeneration = ++relatedRequestGeneration
        relatedState = RelatedState.Loading
        renderContent(preservePosition = true)
        val startedAt = SystemClock.elapsedRealtime()
        diagnose(
            "related_loading",
            mapOf(
                "item" to masked(details.id),
                "title" to MusicDetailsRelatedPolicy.title(details.type)
            )
        )
        thread(name = "ptv-music-related", start = true) {
            runCatching {
                MusicDetailsRelatedPolicy.filter(
                    currentItemId = details.id,
                    currentItemType = details.type,
                    candidates = api.loadSimilar(
                        session,
                        details.id,
                        MusicDetailsRelatedPolicy.INITIAL_LIMIT + 1
                    )
                )
            }.onSuccess { related ->
                runOnUiThread {
                    if (
                        !isCurrent(detailsGeneration) ||
                        relatedGeneration != relatedRequestGeneration
                    ) {
                        return@runOnUiThread
                    }
                    relatedState = if (related.isEmpty()) {
                        RelatedState.Empty
                    } else {
                        RelatedState.Content(related)
                    }
                    renderContent(preservePosition = true)
                    diagnose(
                        "related_ready",
                        mapOf(
                            "item" to masked(details.id),
                            "count" to related.size.toString(),
                            "elapsedMs" to
                                (SystemClock.elapsedRealtime() - startedAt).toString()
                        )
                    )
                }
            }.onFailure { error ->
                runOnUiThread {
                    if (
                        !isCurrent(detailsGeneration) ||
                        relatedGeneration != relatedRequestGeneration
                    ) {
                        return@runOnUiThread
                    }
                    relatedState = RelatedState.Error
                    renderContent(preservePosition = true)
                    diagnose(
                        "related_error",
                        mapOf(
                            "item" to masked(details.id),
                            "error" to error.javaClass.simpleName,
                            "elapsedMs" to
                                (SystemClock.elapsedRealtime() - startedAt).toString()
                        )
                    )
                }
            }
        }
    }

    private fun renderContent(preservePosition: Boolean) {
        val details = item ?: return
        val state = if (preservePosition) capturePageState() else pendingSavedState
        val rows = buildList {
            add(PageRow.Header(details))
            if (songs.isEmpty()) {
                add(
                    PageRow.Message(
                        key = "tracks-empty",
                        text = "No playable tracks are available.",
                        showProgress = false,
                        retryable = false
                    )
                )
            } else {
                add(PageRow.SectionTitle("tracks-title", "Tracks"))
                songs.forEachIndexed { index, song ->
                    add(PageRow.Track(song, index))
                }
            }
            when (val related = relatedState) {
                RelatedState.Loading -> add(
                    PageRow.Related(
                        title = MusicDetailsRelatedPolicy.title(details.type),
                        state = related
                    )
                )
                is RelatedState.Content -> add(
                    PageRow.Related(
                        title = MusicDetailsRelatedPolicy.title(details.type),
                        state = related
                    )
                )
                RelatedState.Error -> add(
                    PageRow.Related(
                        title = MusicDetailsRelatedPolicy.title(details.type),
                        state = related
                    )
                )
                RelatedState.Empty -> Unit
            }
        }
        pageAdapter.submitRows(rows)
        page.post {
            val restore = state
            if (restore != null) {
                pageLayoutManager.scrollToPositionWithOffset(
                    restore.firstVisiblePosition.coerceIn(0, rows.lastIndex.coerceAtLeast(0)),
                    restore.firstVisibleOffset
                )
                page.post {
                    restoreFocus(restore.focusKey)
                    pendingSavedState = null
                }
            } else if (page.findFocus() == null) {
                restoreFocus(defaultFocusKey())
            }
        }
    }

    private fun loadPageArtwork(details: MediaItem) {
        val candidates = artworkCandidates(details)
        loadArtwork(
            image = backdrop,
            candidates = candidates,
            requestedWidth = BACKDROP_WIDTH_PX,
            requestedHeight = BACKDROP_HEIGHT_PX,
            slot = "backdrop"
        )
    }

    private fun artworkCandidates(details: MediaItem): List<MusicArtworkCandidate> =
        MusicDetailsArtworkPolicy.candidates(
            item = details,
            songs = songs,
            relevantAlbums = relevantAlbums,
            artist = supportingArtist
        )

    private fun loadArtwork(
        image: ImageView,
        candidates: List<MusicArtworkCandidate>,
        requestedWidth: Int,
        requestedHeight: Int,
        slot: String
    ) {
        val remote = candidates.asSequence()
            .filter(MusicArtworkCandidate::isRemote)
            .take(MusicDetailsArtworkPolicy.MAX_REMOTE_ATTEMPTS)
            .toList()
        val loadKey = "${++artworkGeneration}:$slot"
        image.dispose()
        image.tag = loadKey
        applyBrandedPlaceholder(image, slot)
        if (remote.isEmpty()) {
            diagnoseArtwork(slot, MusicArtworkSource.BRAND, 0, "missing")
            return
        }

        fun attempt(index: Int) {
            if (image.tag != loadKey) return
            val candidate = remote.getOrNull(index)
            if (candidate == null) {
                applyBrandedPlaceholder(image, slot)
                diagnoseArtwork(slot, MusicArtworkSource.BRAND, index, "exhausted")
                return
            }
            val owner = candidate.ownerItemId ?: return attempt(index + 1)
            val tag = candidate.imageTag ?: return attempt(index + 1)
            val url = when (candidate.imageType) {
                MusicArtworkImageType.BACKDROP ->
                    api.backdropUrl(session, owner, tag, requestedWidth)
                MusicArtworkImageType.PRIMARY ->
                    api.primaryImageUrl(session, owner, tag, requestedWidth)
                MusicArtworkImageType.BRAND -> return attempt(index + 1)
            }
            val startedAt = SystemClock.elapsedRealtime()
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.load(url) {
                crossfade(false)
                placeholder(loadingPlaceholder(slot))
                error(loadingPlaceholder(slot))
                size(requestedWidth, requestedHeight)
                allowRgb565(TvRenderingRuntime.features().rgb565Posters)
                listener(
                    onSuccess = { _, _ ->
                        if (image.tag == loadKey) {
                            diagnoseArtwork(
                                slot,
                                candidate.source,
                                index,
                                "success",
                                SystemClock.elapsedRealtime() - startedAt
                            )
                        }
                    },
                    onError = { _, _ ->
                        if (image.tag == loadKey) {
                            diagnoseArtwork(
                                slot,
                                candidate.source,
                                index,
                                "failed",
                                SystemClock.elapsedRealtime() - startedAt
                            )
                            attempt(index + 1)
                        }
                    }
                )
            }
        }
        attempt(0)
    }

    private fun playAll(shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        val queue = if (shuffle) songs.shuffled() else songs
        MusicPlaybackManager.play(this, session, queue)
        diagnose(
            if (shuffle) "shuffle_started" else "play_all_started",
            mapOf("trackCount" to queue.size.toString())
        )
    }

    private fun playTrack(trackIndex: Int) {
        if (trackIndex !in songs.indices) return
        MusicPlaybackManager.play(this, session, songs, trackIndex)
        diagnose(
            "track_started",
            mapOf(
                "track" to masked(songs[trackIndex].id),
                "index" to trackIndex.toString()
            )
        )
    }

    private fun openRelated(related: MediaItem) {
        currentFocusKey = RELATED_KEY_PREFIX + related.id
        openedChildDetails = true
        diagnose(
            "related_opened",
            mapOf(
                "item" to masked(related.id),
                "type" to safeType(related.type)
            )
        )
        start(this, related.id)
    }

    private fun retryRelated() {
        val details = item ?: return
        loadRelated(details, requestGeneration)
    }

    private fun capturePageState(): PageState {
        val position = pageLayoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val first = pageLayoutManager.findViewByPosition(position)
        val offset = first?.top?.minus(page.paddingTop) ?: 0
        val focusedKey = (page.findFocus()?.tag as? String) ?: currentFocusKey
        return PageState(position, offset, focusedKey)
    }

    private fun restoreFocus(key: String?) {
        val targetKey = key ?: defaultFocusKey() ?: return
        page.findViewWithTag<View>(targetKey)?.let {
            if (it.isFocusable && it.isEnabled) {
                it.requestFocus()
                return
            }
        }
        val outerPosition = pageAdapter.positionForFocusKey(targetKey)
        if (outerPosition < 0) return
        pageLayoutManager.scrollToPositionWithOffset(outerPosition, page.paddingTop)
        page.post {
            page.findViewWithTag<View>(targetKey)?.let {
                if (it.isFocusable && it.isEnabled) it.requestFocus()
            } ?: pageAdapter.restoreNestedFocus(page, outerPosition, targetKey)
        }
    }

    private fun defaultFocusKey(): String? = when {
        songs.isNotEmpty() -> ACTION_PLAY_KEY
        relatedState is RelatedState.Content ->
            (relatedState as RelatedState.Content).items.firstOrNull()?.let {
                RELATED_KEY_PREFIX + it.id
            }
        relatedState == RelatedState.Error -> RELATED_RETRY_KEY
        item == null -> INITIAL_RETRY_KEY
        else -> null
    }

    private fun onFocused(key: String) {
        currentFocusKey = key
        PtvDiagnosticsManager.routeInteractive(ROUTE_NAME, diagnosticFocus(key))
        diagnose("focus", mapOf("target" to diagnosticFocus(key)))
    }

    private fun isCurrent(generation: Int): Boolean =
        !destroyed && generation == requestGeneration

    private fun artworkReasons(details: MediaItem): String =
        artworkCandidates(details).joinToString(">") { it.source.name.lowercase() }

    private fun diagnoseArtwork(
        slot: String,
        source: MusicArtworkSource,
        attempt: Int,
        result: String,
        elapsedMs: Long? = null
    ) {
        diagnose(
            "artwork",
            buildMap {
                put("item", masked(itemId))
                put("slot", slot)
                put("source", source.name.lowercase())
                put("attempt", attempt.toString())
                put("result", result)
                elapsedMs?.let { put("elapsedMs", it.toString()) }
            }
        )
    }

    private fun diagnose(name: String, attributes: Map<String, String>) {
        PtvDiagnosticsManager.event("music_details", name, attributes)
    }

    private fun diagnosticFocus(key: String?): String = when {
        key == null -> "none"
        key.startsWith(TRACK_KEY_PREFIX) -> "track:${masked(key.removePrefix(TRACK_KEY_PREFIX).substringBefore(':'))}"
        key.startsWith(RELATED_KEY_PREFIX) -> "related:${masked(key.removePrefix(RELATED_KEY_PREFIX))}"
        else -> key.take(DIAGNOSTIC_TEXT_LIMIT)
    }

    private fun masked(value: String?): String =
        PtvRedactor.identifier(value) ?: "none"

    private fun safeType(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(DIAGNOSTIC_TEXT_LIMIT)

    private fun applyBrandedPlaceholder(image: ImageView, slot: String) {
        image.scaleType = ImageView.ScaleType.FIT_XY
        image.setImageResource(
            if (slot == "backdrop") {
                R.drawable.music_hero_branded_background
            } else {
                R.drawable.music_card_branded_placeholder
            }
        )
    }

    private fun loadingPlaceholder(slot: String): ColorDrawable =
        ColorDrawable(if (slot == "backdrop") PTVColors.background else PTVColors.cardBackground)

    private fun actionBackground(primary: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dim(R.dimen.tv_spacing_small).toFloat()
            setColor(if (primary) PTVColors.primary else PTVColors.buttonSecondary)
        }

    private fun rowBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dim(R.dimen.tv_spacing_small).toFloat()
            setColor(Color.argb(164, 34, 17, 54))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun text(
        value: CharSequence,
        sizeResource: Int,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        text = value
        setTextSizeRes(sizeResource)
        setTextColor(color)
        typeface = Typeface.create(
            if (bold) "sans-serif-medium" else "sans-serif",
            Typeface.NORMAL
        )
    }

    private fun createActionButton(
        title: String,
        primary: Boolean,
        focusKey: String
    ): Button = Button(this).apply {
        id = View.generateViewId()
        text = title
        contentDescription = title
        tag = focusKey
        isAllCaps = false
        isFocusable = true
        isFocusableInTouchMode = true
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setTextSizeRes(R.dimen.tv_nav_text_size)
        setTextColor(getColor(R.color.tv_text_primary))
        background = actionBackground(primary)
        elevation = 0f
        stateListAnimator = null
        setPadding(
            dim(R.dimen.tv_spacing_large),
            0,
            dim(R.dimen.tv_spacing_large),
            0
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultFocusHighlightEnabled = false
        }
        TvFocusIndicator.registerArtwork(this, this)
        setOnFocusChangeListener { view, focused ->
            PTVShapes.applyFocusEffect(view, focused)
            if (focused) onFocused(focusKey)
        }
    }

    private inner class MusicDetailsAdapter :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<PageRow> = emptyList()

        init {
            setHasStableIds(true)
        }

        fun submitRows(updated: List<PageRow>) {
            rows = updated
            notifyDataSetChanged()
        }

        fun positionForFocusKey(key: String): Int = when {
            key == ACTION_PLAY_KEY || key == ACTION_SHUFFLE_KEY ->
                rows.indexOfFirst { it is PageRow.Header }
            key.startsWith(TRACK_KEY_PREFIX) ->
                rows.indexOfFirst { it.focusKey == key }
            key.startsWith(RELATED_KEY_PREFIX) || key == RELATED_RETRY_KEY ->
                rows.indexOfFirst { it is PageRow.Related }
            key == INITIAL_RETRY_KEY ->
                rows.indexOfFirst { it is PageRow.Message && it.retryable }
            else -> -1
        }

        fun restoreNestedFocus(
            recycler: RecyclerView,
            outerPosition: Int,
            key: String
        ) {
            val holder = recycler.findViewHolderForAdapterPosition(outerPosition)
            if (holder is RelatedHolder && key.startsWith(RELATED_KEY_PREFIX)) {
                holder.restoreRelatedFocus(key.removePrefix(RELATED_KEY_PREFIX))
            }
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemId(position: Int): Long = stableId(rows[position].stableKey)

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is PageRow.Header -> VIEW_HEADER
            is PageRow.SectionTitle -> VIEW_SECTION_TITLE
            is PageRow.Track -> VIEW_TRACK
            is PageRow.Related -> VIEW_RELATED
            is PageRow.Message -> VIEW_MESSAGE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when (viewType) {
                VIEW_HEADER -> createHeaderHolder(parent)
                VIEW_SECTION_TITLE -> SectionTitleHolder(
                    text("", R.dimen.tv_text_size_section_title, PTVColors.textPrimary, true)
                        .apply {
                            setPadding(
                                0,
                                dim(R.dimen.tv_spacing_large),
                                0,
                                dim(R.dimen.tv_spacing_small)
                            )
                        }
                )
                VIEW_TRACK -> createTrackHolder(parent)
                VIEW_RELATED -> createRelatedHolder(parent)
                else -> createMessageHolder(parent)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            bind(holder, rows[position])
        }

        private fun bind(
            holder: RecyclerView.ViewHolder,
            row: PageRow
        ) {
            when {
                holder is HeaderHolder && row is PageRow.Header -> holder.bind(row.item)
                holder is SectionTitleHolder && row is PageRow.SectionTitle ->
                    holder.label.text = row.title
                holder is TrackHolder && row is PageRow.Track -> holder.bind(row)
                holder is RelatedHolder && row is PageRow.Related -> holder.bind(row)
                holder is MessageHolder && row is PageRow.Message -> holder.bind(row)
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is HeaderHolder) holder.art.dispose()
            if (holder is RelatedHolder) holder.recycle()
            super.onViewRecycled(holder)
        }

        private fun createHeaderHolder(parent: ViewGroup): HeaderHolder {
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dim(R.dimen.tv_spacing_small))
            }
            val top = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val art = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                contentDescription = "Music artwork"
                setImageResource(R.drawable.music_card_branded_placeholder)
            }
            top.addView(
                art,
                LinearLayout.LayoutParams(
                    dim(R.dimen.tv_square_width),
                    dim(R.dimen.tv_square_height)
                )
            )
            val titleColumn = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dim(R.dimen.tv_spacing_large), 0, 0, 0)
            }
            val title = text(
                "",
                R.dimen.tv_text_size_hero_title,
                PTVColors.textPrimary,
                true
            ).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val metadata = text(
                "",
                R.dimen.tv_text_size_hero_meta,
                PTVColors.textSecondary
            ).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dim(R.dimen.tv_spacing_small), 0, 0)
            }
            val actions = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                setPadding(0, dim(R.dimen.tv_spacing_medium), 0, 0)
            }
            val play = createActionButton("Play All", true, ACTION_PLAY_KEY)
            val shuffle = createActionButton("Shuffle", false, ACTION_SHUFFLE_KEY)
            play.nextFocusRightId = shuffle.id
            shuffle.nextFocusLeftId = play.id
            actions.addView(
                play,
                LinearLayout.LayoutParams(
                    -2,
                    dim(R.dimen.tv_hero_button_height)
                )
            )
            actions.addView(
                shuffle,
                LinearLayout.LayoutParams(
                    -2,
                    dim(R.dimen.tv_hero_button_height)
                ).apply { marginStart = dim(R.dimen.tv_spacing_medium) }
            )
            titleColumn.addView(title)
            titleColumn.addView(metadata)
            titleColumn.addView(actions)
            top.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
            val overview = text(
                "",
                R.dimen.tv_text_size_body,
                PTVColors.textSecondary
            ).apply {
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dim(R.dimen.tv_spacing_medium), 0, 0)
            }
            container.addView(top)
            container.addView(overview)
            return HeaderHolder(container, art, title, metadata, overview, play, shuffle)
        }

        private fun createTrackHolder(parent: ViewGroup): TrackHolder {
            val row = LinearLayout(parent.context).apply {
                id = View.generateViewId()
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                background = rowBackground()
                setPadding(
                    dim(R.dimen.tv_spacing_medium),
                    0,
                    dim(R.dimen.tv_spacing_medium),
                    0
                )
                elevation = 0f
                stateListAnimator = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    defaultFocusHighlightEnabled = false
                }
            }
            TvFocusIndicator.registerArtwork(row, row)
            val number = text(
                "",
                R.dimen.tv_text_size_body,
                PTVColors.textSecondary
            ).apply { gravity = Gravity.CENTER }
            val titleColumn = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dim(R.dimen.tv_spacing_medium), 0, 0, 0)
            }
            val title = text(
                "",
                R.dimen.tv_text_size_body,
                PTVColors.textPrimary,
                true
            ).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val artist = text(
                "",
                R.dimen.tv_text_size_metadata,
                PTVColors.textSecondary
            ).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            titleColumn.addView(title)
            titleColumn.addView(artist)
            val duration = text(
                "",
                R.dimen.tv_text_size_metadata,
                PTVColors.textSecondary
            ).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
            row.addView(
                number,
                LinearLayout.LayoutParams(dp(TRACK_NUMBER_WIDTH_DP), -1)
            )
            row.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(
                duration,
                LinearLayout.LayoutParams(dp(TRACK_DURATION_WIDTH_DP), -1)
            )
            row.layoutParams = RecyclerView.LayoutParams(-1, dp(TRACK_ROW_HEIGHT_DP)).apply {
                bottomMargin = dim(R.dimen.tv_spacing_small)
            }
            return TrackHolder(row, number, title, artist, duration)
        }

        private fun createRelatedHolder(parent: ViewGroup): RelatedHolder {
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dim(R.dimen.tv_spacing_large), 0, 0)
            }
            return RelatedHolder(container)
        }

        private fun createMessageHolder(parent: ViewGroup): MessageHolder {
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
                setPadding(0, dim(R.dimen.tv_spacing_large), 0, dim(R.dimen.tv_spacing_large))
            }
            val progress = ProgressBar(parent.context).apply {
                isFocusable = false
            }
            val message = text(
                "",
                R.dimen.tv_text_size_body,
                PTVColors.textSecondary
            ).apply {
                setPadding(0, dim(R.dimen.tv_spacing_small), 0, 0)
            }
            val retry = createActionButton("Retry", true, INITIAL_RETRY_KEY)
            container.addView(
                progress,
                LinearLayout.LayoutParams(dp(MESSAGE_PROGRESS_DP), dp(MESSAGE_PROGRESS_DP))
            )
            container.addView(message)
            container.addView(
                retry,
                LinearLayout.LayoutParams(-2, dim(R.dimen.tv_hero_button_height)).apply {
                    topMargin = dim(R.dimen.tv_spacing_medium)
                }
            )
            return MessageHolder(container, progress, message, retry)
        }

        private inner class HeaderHolder(
            view: View,
            val art: ImageView,
            private val title: TextView,
            private val metadata: TextView,
            private val overview: TextView,
            private val play: Button,
            private val shuffle: Button
        ) : RecyclerView.ViewHolder(view) {
            fun bind(details: MediaItem) {
                title.text = details.title
                metadata.text = headerMetadata(details)
                val sanitizedOverview = TextSanitizer.sanitize(details.overview)
                overview.text = sanitizedOverview
                overview.visibility = if (sanitizedOverview.isBlank()) View.GONE else View.VISIBLE
                val hasSongs = songs.isNotEmpty()
                play.isEnabled = hasSongs
                play.isFocusable = hasSongs
                play.alpha = if (hasSongs) 1f else DISABLED_ALPHA
                shuffle.isEnabled = hasSongs
                shuffle.isFocusable = hasSongs
                shuffle.alpha = if (hasSongs) 1f else DISABLED_ALPHA
                play.setOnClickListener { playAll() }
                shuffle.setOnClickListener { playAll(shuffle = true) }
                loadArtwork(
                    art,
                    artworkCandidates(details),
                    dim(R.dimen.tv_square_width),
                    dim(R.dimen.tv_square_height),
                    "cover"
                )
            }
        }

        private inner class SectionTitleHolder(val label: TextView) :
            RecyclerView.ViewHolder(label)

        private inner class TrackHolder(
            view: View,
            private val number: TextView,
            private val title: TextView,
            private val artist: TextView,
            private val duration: TextView
        ) : RecyclerView.ViewHolder(view) {
            fun bind(row: PageRow.Track) {
                itemView.tag = row.focusKey
                itemView.contentDescription = buildString {
                    append("Track ").append(row.index + 1).append(", ").append(row.song.title)
                    row.song.artists.firstOrNull()?.let { append(", ").append(it) }
                }
                number.text = (row.index + 1).toString()
                title.text = row.song.title
                artist.text = row.song.artists.firstOrNull()
                    ?: row.song.albumArtist
                    ?: "Unknown artist"
                duration.text = formatDuration(row.song.runtimeTicks)
                itemView.setOnClickListener { playTrack(row.index) }
                itemView.setOnFocusChangeListener { view, focused ->
                    PTVShapes.applyFocusEffect(view, focused)
                    if (focused) row.focusKey?.let(::onFocused)
                }
                itemView.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) {
                        false
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && row.index == 0) {
                        restoreFocus(ACTION_PLAY_KEY)
                        true
                    } else if (
                        keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                        row.index == songs.lastIndex &&
                        relatedState is RelatedState.Content
                    ) {
                        val firstRelated =
                            (relatedState as RelatedState.Content).items.firstOrNull()
                        if (firstRelated != null) {
                            restoreFocus(RELATED_KEY_PREFIX + firstRelated.id)
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
            }
        }

        private inner class MessageHolder(
            view: View,
            private val progress: ProgressBar,
            private val message: TextView,
            private val retry: Button
        ) : RecyclerView.ViewHolder(view) {
            fun bind(row: PageRow.Message) {
                message.text = row.text
                progress.visibility = if (row.showProgress) View.VISIBLE else View.GONE
                retry.visibility = if (row.retryable) View.VISIBLE else View.GONE
                retry.isFocusable = row.retryable
                retry.isEnabled = row.retryable
                retry.setOnClickListener { loadDetails() }
            }
        }

        private inner class RelatedHolder(
            private val container: LinearLayout
        ) : RecyclerView.ViewHolder(container) {
            private var relatedList: TvHorizontalRecyclerView? = null

            fun bind(row: PageRow.Related) {
                recycle()
                container.removeAllViews()
                container.addView(
                    text(
                        row.title,
                        R.dimen.tv_text_size_section_title,
                        PTVColors.textPrimary,
                        true
                    ).apply {
                        setPadding(0, 0, 0, dim(R.dimen.tv_spacing_small))
                    }
                )
                when (val state = row.state) {
                    RelatedState.Loading -> {
                        val loading = LinearLayout(this@MusicDetailsActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        loading.addView(
                            ProgressBar(this@MusicDetailsActivity),
                            LinearLayout.LayoutParams(
                                dp(MESSAGE_PROGRESS_DP),
                                dp(MESSAGE_PROGRESS_DP)
                            )
                        )
                        loading.addView(
                            text(
                                "Loading recommendations\u2026",
                                R.dimen.tv_text_size_body,
                                PTVColors.textSecondary
                            ).apply {
                                setPadding(dim(R.dimen.tv_spacing_medium), 0, 0, 0)
                            }
                        )
                        container.addView(loading)
                    }
                    RelatedState.Error -> {
                        container.addView(
                            text(
                                "Recommendations could not be loaded.",
                                R.dimen.tv_text_size_body,
                                PTVColors.textSecondary
                            )
                        )
                        val retry = createActionButton(
                            "Retry",
                            true,
                            RELATED_RETRY_KEY
                        ).apply { setOnClickListener { retryRelated() } }
                        container.addView(
                            retry,
                            LinearLayout.LayoutParams(
                                -2,
                                dim(R.dimen.tv_hero_button_height)
                            ).apply { topMargin = dim(R.dimen.tv_spacing_medium) }
                        )
                    }
                    is RelatedState.Content -> {
                        val manager = TvLinearLayoutManager(
                            this@MusicDetailsActivity,
                            RecyclerView.HORIZONTAL,
                            false
                        )
                        val list = TvHorizontalRecyclerView(this@MusicDetailsActivity).apply {
                            id = View.generateViewId()
                            layoutManager = manager
                            adapter = RelatedAdapter(state.items)
                            clipToPadding = false
                            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                            addItemDecoration(
                                TvCardSpacingDecoration(dim(R.dimen.tv_card_spacing))
                            )
                            applyRenderingTuning(manager, RELATED_VISIBLE_ITEMS)
                        }
                        relatedList = list
                        container.addView(
                            list,
                            LinearLayout.LayoutParams(
                                -1,
                                dim(R.dimen.tv_square_height) +
                                    dim(R.dimen.tv_shelf_extra_height)
                            )
                        )
                    }
                    RelatedState.Empty -> Unit
                }
            }

            fun restoreRelatedFocus(itemId: String) {
                val list = relatedList ?: return
                val adapter = list.adapter as? RelatedAdapter ?: return
                val position = adapter.positionOf(itemId)
                if (position < 0) return
                (list.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(position, list.paddingLeft)
                list.post {
                    list.findViewHolderForAdapterPosition(position)
                        ?.itemView
                        ?.requestFocus()
                }
            }

            fun recycle() {
                relatedList?.let { list ->
                    (list.adapter as? RelatedAdapter)?.recycleVisible(list)
                    list.adapter = null
                }
                relatedList = null
            }
        }

        private inner class RelatedAdapter(
            private val items: List<MediaItem>
        ) : RecyclerView.Adapter<MediaCardHolder>() {
            init {
                setHasStableIds(true)
            }

            override fun getItemCount(): Int = items.size

            override fun getItemId(position: Int): Long = stableId(items[position].id)

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaCardHolder =
                MediaCardHolder(
                    MediaCardFactory.createView(parent, MediaCardPresentation.SQUARE)
                )

            override fun onBindViewHolder(holder: MediaCardHolder, position: Int) {
                val related = items[position]
                MediaCardFactory.bindView(
                    holder,
                    related,
                    MediaCardPresentation.SQUARE,
                    session,
                    api
                )
                val focusKey = RELATED_KEY_PREFIX + related.id
                holder.itemView.tag = focusKey
                holder.itemView.contentDescription =
                    "${related.title}, ${safeType(related.type)}"
                holder.itemView.setOnClickListener { openRelated(related) }
                holder.itemView.setOnFocusChangeListener { view, focused ->
                    PTVShapes.applyFocusEffect(view, focused)
                    if (focused) onFocused(focusKey)
                }
                holder.itemView.setOnKeyListener { _, keyCode, event ->
                    if (
                        event.action == KeyEvent.ACTION_DOWN &&
                        keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                        songs.isNotEmpty()
                    ) {
                        restoreFocus(
                            TRACK_KEY_PREFIX +
                                songs.last().id +
                                ":" +
                                songs.lastIndex
                        )
                        true
                    } else {
                        false
                    }
                }
            }

            override fun onViewRecycled(holder: MediaCardHolder) {
                holder.image.dispose()
                super.onViewRecycled(holder)
            }

            fun positionOf(itemId: String): Int = items.indexOfFirst { it.id == itemId }

            fun recycleVisible(recycler: RecyclerView) {
                for (index in 0 until recycler.childCount) {
                    recycler.getChildViewHolder(recycler.getChildAt(index))
                        .let { it as? MediaCardHolder }
                        ?.image
                        ?.dispose()
                }
            }
        }
    }

    private fun headerMetadata(details: MediaItem): String {
        val artist = details.albumArtist
            ?: details.artists.firstOrNull()
            ?: songs.firstNotNullOfOrNull(MediaItem::albumArtist)
            ?: songs.asSequence().flatMap { it.artists.asSequence() }.firstOrNull()
        val count = songs.takeIf { it.isNotEmpty() }?.size?.let {
            when {
                sourceTrackCount > it -> "First $it tracks"
                it == 1 -> "1 track"
                else -> "$it tracks"
            }
        }
        val typeLabel = when (details.type) {
            "MusicArtist" -> "Artist"
            "MusicAlbum" -> "Album"
            "Playlist" -> "Playlist"
            else -> "Music"
        }
        return listOfNotNull(artist, details.year, count, typeLabel)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString("  \u2022  ")
    }

    private fun formatDuration(runtimeTicks: Long): String {
        if (runtimeTicks <= 0) return ""
        val totalSeconds = runtimeTicks / TICKS_PER_SECOND
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    private sealed class PageRow(
        val stableKey: String,
        open val focusKey: String? = null
    ) {
        data class Header(val item: MediaItem) : PageRow("header")

        data class SectionTitle(val key: String, val title: String) :
            PageRow(key)

        data class Track(val song: MediaItem, val index: Int) :
            PageRow(
                stableKey = "track-row:${song.id}:$index",
                focusKey = TRACK_KEY_PREFIX + song.id + ":" + index
            )

        data class Related(val title: String, val state: RelatedState) :
            PageRow("related")

        data class Message(
            val key: String,
            val text: String,
            val showProgress: Boolean,
            val retryable: Boolean
        ) : PageRow(key, if (retryable) INITIAL_RETRY_KEY else null)
    }

    private sealed interface RelatedState {
        data object Loading : RelatedState
        data object Empty : RelatedState
        data object Error : RelatedState
        data class Content(val items: List<MediaItem>) : RelatedState
    }

    private data class DetailsPayload(
        val item: MediaItem,
        val songs: List<MediaItem>,
        val sourceTrackCount: Int,
        val relevantAlbums: List<MediaItem>,
        val artist: MediaItem?
    )

    private data class PageState(
        val firstVisiblePosition: Int,
        val firstVisibleOffset: Int,
        val focusKey: String?
    )

    companion object {
        private const val EXTRA_ITEM_ID = "extra_item_id"
        private const val ROUTE_NAME = "music_details"
        private const val STATE_FIRST_POSITION = "music_details.first_position"
        private const val STATE_FIRST_OFFSET = "music_details.first_offset"
        private const val STATE_FOCUS_KEY = "music_details.focus_key"

        private const val ACTION_PLAY_KEY = "action:play_all"
        private const val ACTION_SHUFFLE_KEY = "action:shuffle"
        private const val TRACK_KEY_PREFIX = "track:"
        private const val RELATED_KEY_PREFIX = "related:"
        private const val INITIAL_RETRY_KEY = "action:retry_details"
        private const val RELATED_RETRY_KEY = "action:retry_related"

        private const val VIEW_MESSAGE = 0
        private const val VIEW_HEADER = 1
        private const val VIEW_SECTION_TITLE = 2
        private const val VIEW_TRACK = 3
        private const val VIEW_RELATED = 4
        private const val SUPPORTING_ALBUM_LIMIT = 4
        private const val RELATED_VISIBLE_ITEMS = 7
        private const val PAGE_VIEW_CACHE = 8
        private const val TRACK_ROW_HEIGHT_DP = 42
        private const val TRACK_NUMBER_WIDTH_DP = 36
        private const val TRACK_DURATION_WIDTH_DP = 52
        private const val MESSAGE_PROGRESS_DP = 24
        private const val BACKDROP_WIDTH_PX = 1280
        private const val BACKDROP_HEIGHT_PX = 720
        private const val DIAGNOSTIC_TEXT_LIMIT = 48
        private const val TICKS_PER_SECOND = 10_000_000L
        private const val DISABLED_ALPHA = 0.5f

        fun start(context: Context, itemId: String) {
            context.startActivity(
                Intent(context, MusicDetailsActivity::class.java).apply {
                    putExtra(EXTRA_ITEM_ID, itemId)
                    if (context !is android.app.Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            )
        }

        private fun stableId(value: String): Long {
            var hash = -0x340d631b7bdddcdbL
            value.forEach { character ->
                hash = hash xor character.code.toLong()
                hash *= 0x100000001b3L
            }
            return hash
        }
    }
}
