package com.piggie.tv.ui.player

import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DimenRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import coil.size.Size
import coil.transform.Transformation
import com.piggie.tv.R
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.memory.MemoryPressureParticipant
import com.piggie.tv.memory.MemoryPressurePolicy
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.api.NativeRequestScope
import com.piggie.tv.data.models.AudioTrack
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.models.SubtitleTrack
import com.piggie.tv.data.playback.ConnectionSpeed
import com.piggie.tv.data.playback.PendingPlaybackPreference
import com.piggie.tv.data.playback.PendingPlaybackPreferences
import com.piggie.tv.data.playback.SubtitleSelectionMode
import com.piggie.tv.data.session.NativeSettings
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.diagnostics.PtvCoilEventListenerFactory
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import com.piggie.tv.diagnostics.PtvFocusTrace
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.theme.PTVShapes
import com.piggie.tv.ui.layout.TvLinearLayoutManager
import com.piggie.tv.ui.reader.ReaderActivity
import com.piggie.tv.ui.rendering.*
import com.piggie.tv.ui.shared.TextSanitizer
import com.piggie.tv.ui.widgets.MediaCardFactory
import com.piggie.tv.ui.widgets.MediaCardHolder
import com.piggie.tv.ui.widgets.PtvSelectionDialog
import com.piggie.tv.ui.widgets.PtvStatusIconButton
import com.piggie.tv.util.PTVLog
import com.piggie.tv.util.dim
import com.piggie.tv.util.dimFloat
import com.piggie.tv.util.setTextSizeRes
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.lang.ref.WeakReference
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

private object PosterBackdropBlurTransformation : Transformation {
    override val cacheKey: String = "ptv-details-poster-backdrop-blur-v2"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val sampledWidth = (input.width / 18).coerceAtLeast(1)
        val sampledHeight = (input.height / 18).coerceAtLeast(1)
        // ImageView performs the final bilinear scale. Retaining a second full-size bitmap only
        // to display a deliberately blurred fallback wastes several megabytes on TV hardware.
        return Bitmap.createScaledBitmap(input, sampledWidth, sampledHeight, true)
    }
}

private object DetailsPosterOutlineProvider : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        if (view.width <= 0 || view.height <= 0) {
            outline.setEmpty()
        } else {
            outline.setRoundRect(
                0,
                0,
                view.width,
                view.height,
                view.resources.getDimension(R.dimen.tv_media_artwork_corner_radius)
            )
        }
    }
}

class MediaDetailsFragment : Fragment(), MemoryPressureParticipant {
    private val api by lazy { JellyfinNativeApi(requireContext().applicationContext) }
    private val settings by lazy { NativeSettings(requireContext().applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private val apiWorkers = ConcurrentHashMap<Thread, NativeRequestScope>()
    private val apiWorkerKeys = ConcurrentHashMap<String, Thread>()
    private val clearableViewReferences = mutableListOf<ClearableViewReference<*>>()

    private fun <T : Any> viewReference(): ClearableViewReference<T> =
        ClearableViewReference<T>().also(clearableViewReferences::add)

    private lateinit var session: NativeSession
    private lateinit var features: RendererFeatures
    private lateinit var itemId: String
    private var root: FrameLayout by viewReference()
    private var scroll: ScrollView by viewReference()
    private var sideInfo: LinearLayout by viewReference()
    private var infoLeft: LinearLayout by viewReference()
    private var logo: ImageView by viewReference()
    private var poster: ImageView by viewReference()
    private var title: TextView by viewReference()
    private var secondaryTitle: TextView by viewReference()
    private var metadataContainer: LinearLayout by viewReference()
    private var overview: TextView by viewReference()
    private var actionsRow: LinearLayout by viewReference()
    private var castContainer: LinearLayout by viewReference()
    private var seasonsContainer: LinearLayout by viewReference()
    private var episodesContainer: LinearLayout by viewReference()
    private var relatedContainer: LinearLayout by viewReference()
    private var backdrop: ImageView by viewReference()

    private var currentItem: MediaItem? = null
    private var nextUpItem: MediaItem? = null
    private var currentSeasonId: String? = null
    private var seasonList: RecyclerView? = null
    private var episodeList: RecyclerView? = null
    private var episodePlayAllAction: View? = null
    private var episodeShuffleAllAction: View? = null
    private var currentSeasonEpisodes: List<MediaItem> = emptyList()
    private var relatedList: RecyclerView? = null
    private var primaryAction: View? = null
    private var tracksReady = false
    private var destroyed = true
    private var requestGeneration = 0
    private var episodeRequestGeneration = 0
    private var titleLogoGeneration = 0
    private var posterGeneration = 0
    private var interactiveReported = false
    private val traceCookie = TRACE_SEQUENCE.incrementAndGet()
    private var traceStarted = false
    private val detailsStack = ArrayDeque<DetailsStackEntry>()
    private var pendingRestore: DetailsStackEntry? = null
    private var seasonsSettled = true
    private var episodesSettled = true
    private var relatedSettled = true
    private var pendingEpisodeFocusSeasonId: String? = null
    private var pendingActionSeasonFocus = false
    private var focusBeforeExternalActivity: WeakReference<View>? = null

    private data class DetailsStackEntry(
        val itemId: String,
        val scrollY: Int,
        val focusedItemId: String?,
        val seasonId: String?,
        val tracksReady: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        itemId = savedInstanceState?.getString(STATE_ITEM_ID)
            ?: requireArguments().getString(ARG_ITEM_ID).orEmpty()
        savedInstanceState?.getStringArrayList(STATE_STACK_ITEM_IDS)?.forEach { savedItemId ->
            detailsStack.addLast(
                DetailsStackEntry(savedItemId, 0, null, null, tracksReady = false)
            )
        }
        session = (activity as? PtvHostActivity)?.session
            ?: SecureSessionStore(requireContext()).read()
            ?: error("No active session")
        features = TvRenderingRuntime.features()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()) {
            Trace.beginAsyncSection("PiggieTV#details:content", traceCookie)
            traceStarted = true
        }
    }

    private fun launchApiWork(key: String, name: String, action: () -> Unit) {
        if (destroyed) return
        cancelApiWork(key)
        val scope = NativeRequestScope()
        val worker = Thread(
            {
                try {
                    api.withRequestScope(scope) {
                        if (!scope.isCancelled && !Thread.currentThread().isInterrupted) {
                            action()
                        }
                    }
                } finally {
                    val current = Thread.currentThread()
                    apiWorkers.remove(current)
                    apiWorkerKeys.remove(key, current)
                }
            },
            name
        ).apply { isDaemon = true }
        apiWorkers[worker] = scope
        apiWorkerKeys[key] = worker
        worker.start()
    }

    private fun cancelApiWork(key: String) {
        val worker = apiWorkerKeys.remove(key) ?: return
        apiWorkers.remove(worker)?.cancel()
        worker.interrupt()
    }

    private fun cancelAllApiWork() {
        val workers = apiWorkers.entries.toList()
        apiWorkerKeys.clear()
        apiWorkers.clear()
        workers.forEach { (worker, scope) ->
            scope.cancel()
            worker.interrupt()
        }
        api.cancelInFlightRequests()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        destroyed = false
        interactiveReported = false
        tracksReady = false
        seasonsSettled = true
        episodesSettled = true
        relatedSettled = true
        buildLayout()
        val seed = MediaDetailsSeedStore.getItem(itemId)
        if (seed != null) {
            markSecondaryUnresolved(seed)
            renderPrimary(seed, fullDetails = false)
            reportInteractive()
        } else {
            renderLoadingIdentity()
        }
        loadDetails(itemId)
        return root
    }

    override fun onPause() {
        if (!destroyed && view != null && focusBeforeExternalActivity == null) {
            activity?.currentFocus
                ?.takeIf { focused -> isDescendant(root, focused) }
                ?.let { focusBeforeExternalActivity = WeakReference(it) }
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        val target = focusBeforeExternalActivity?.get() ?: return
        focusBeforeExternalActivity = null
        target.post {
            if (!destroyed && target.isAttachedToWindow && target.isShown) {
                target.requestFocus()
            }
        }
    }

    override fun onDestroyView() {
        destroyed = true
        requestGeneration++
        handler.removeCallbacksAndMessages(null)
        cancelAllApiWork()
        endContentTrace()

        releaseViewTree(root)
        root.removeAllViews()
        seasonList = null
        episodeList = null
        episodePlayAllAction = null
        episodeShuffleAllAction = null
        currentSeasonEpisodes = emptyList()
        relatedList = null
        primaryAction = null
        currentItem = null
        nextUpItem = null
        currentSeasonId = null
        pendingRestore = null
        pendingEpisodeFocusSeasonId = null
        pendingActionSeasonFocus = false
        focusBeforeExternalActivity = null
        clearableViewReferences.forEach(ClearableViewReference<*>::clear)
        super.onDestroyView()
    }

    private fun isDescendant(parent: ViewGroup, child: View): Boolean {
        var candidate: View? = child
        while (candidate != null) {
            if (candidate === parent) return true
            candidate = candidate.parent as? View
        }
        return false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ITEM_ID, itemId)
        outState.putStringArrayList(
            STATE_STACK_ITEM_IDS,
            ArrayList(detailsStack.map(DetailsStackEntry::itemId))
        )
    }

    override fun onMemoryPressure(level: Int) {
        val actions = MemoryPressurePolicy.actions(level)
        if (!actions.releaseCurrentScreenContent || destroyed || view == null) return
        handler.removeCallbacksAndMessages(BACKDROP_HANDLER_TOKEN)
        cancelApiWork(WORK_RELATED)
        cancelApiWork(WORK_SEASONS)
        cancelApiWork(WORK_EPISODES)
        backdrop.dispose()
        backdrop.setImageDrawable(null)
        titleLogoGeneration++
        logo.dispose()
        logo.setImageDrawable(null)
        logo.visibility = View.GONE
        logo.contentDescription = null
        title.text = currentItem?.title ?: "Loading details…"
        title.visibility = View.VISIBLE
        secondaryTitle.visibility = View.GONE
        posterGeneration++
        poster.dispose()
        poster.setImageDrawable(null)
        poster.visibility = View.GONE
        sideInfo.visibility = if (sideInfo.children().any { it.visibility == View.VISIBLE }) {
            View.VISIBLE
        } else {
            View.GONE
        }
        seasonList?.recycledViewPool?.clear()
        episodeList?.recycledViewPool?.clear()
        relatedList?.recycledViewPool?.clear()
    }

    private fun releaseViewTree(view: View) {
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                releaseViewTree(view.getChildAt(index))
            }
        }
        when (view) {
            is RecyclerView -> {
                view.adapter = null
                view.layoutManager = null
                view.recycledViewPool.clear()
            }
            is ImageView -> {
                view.dispose()
                view.setImageDrawable(null)
            }
        }
        view.setOnClickListener(null)
        view.onFocusChangeListener = null
        view.setOnKeyListener(null)
    }

    private fun buildLayout() {
        val context = requireContext()
        root = FrameLayout(context).apply { setBackgroundColor(PTVColors.background) }
        backdrop = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(PTVColors.background.toDrawable())
        }
        root.addView(backdrop, ViewGroup.LayoutParams(-1, -1))
        root.addView(View(context).apply {
            setBackgroundResource(R.drawable.hero_gradient_overlay)
        }, ViewGroup.LayoutParams(-1, -1))

        scroll = ScrollView(context).apply {
            isFillViewport = true
            isSmoothScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                context.dim(R.dimen.tv_screen_margin_horizontal),
                context.dim(R.dimen.tv_screen_margin_vertical),
                context.dim(R.dimen.tv_screen_margin_horizontal),
                context.dim(R.dimen.tv_spacing_large)
            )
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }

        sideInfo = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, context.dim(R.dimen.tv_spacing_large), 0)
        }
        poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = DetailsPosterOutlineProvider
            clipToOutline = true
            visibility = View.GONE
        }
        top.addView(
            sideInfo,
            LinearLayout.LayoutParams(
                context.dim(R.dimen.tv_poster_width) + context.dim(R.dimen.tv_spacing_large),
                -2
            )
        )

        infoLeft = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val identity = FrameLayout(context).apply {
            minimumHeight = context.dim(R.dimen.tv_details_logo_height)
        }
        title = textView("Loading details…", R.dimen.tv_text_size_hero_title, R.color.tv_text_primary, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        logo = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_START
            visibility = View.GONE
        }
        identity.addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        identity.addView(
            logo,
            FrameLayout.LayoutParams(
                context.dim(R.dimen.tv_details_logo_width),
                context.dim(R.dimen.tv_details_logo_height),
                Gravity.START or Gravity.CENTER_VERTICAL
            )
        )
        secondaryTitle = textView(
            "",
            R.dimen.tv_text_size_section_title,
            R.color.tv_text_primary,
            true
        ).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        metadataContainer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        overview = textView("", R.dimen.tv_text_size_body, R.color.tv_text_secondary).apply {
            maxLines = 5
            setLineSpacing(0f, 1.2f)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        infoLeft.addView(identity)
        infoLeft.addView(secondaryTitle, topMargin(R.dimen.tv_spacing_small))
        infoLeft.addView(metadataContainer, topMargin(R.dimen.tv_spacing_small))
        infoLeft.addView(overview, topMargin(R.dimen.tv_spacing_medium))

        actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        infoLeft.addView(actionsRow, topMargin(R.dimen.tv_spacing_medium))
        top.addView(infoLeft, LinearLayout.LayoutParams(0, -2, 1f))
        
        page.addView(top)

        castContainer = sectionContainer()
        seasonsContainer = sectionContainer()
        episodesContainer = sectionContainer()
        relatedContainer = sectionContainer()
        page.addView(castContainer)
        page.addView(seasonsContainer)
        page.addView(episodesContainer)
        page.addView(relatedContainer)

        scroll.addView(page)
        root.addView(scroll, ViewGroup.LayoutParams(-1, -1))
    }

    private fun sectionContainer() = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }

    private fun renderLoadingIdentity() {
        resetTitleArtwork("Loading details…")
        poster.dispose()
        poster.setImageDrawable(null)
        poster.visibility = View.GONE
        sideInfo.removeAllViews()
        sideInfo.visibility = View.GONE
        metadataContainer.removeAllViews()
        overview.text = "Title and playback actions will appear as soon as the item is available."
        actionsRow.removeAllViews()
    }

    private fun loadDetails(requestedItemId: String) {
        cancelAllApiWork()
        val generation = ++requestGeneration
        launchApiWork(WORK_DETAILS, "ptv-details-$requestedItemId") {
            runCatching { api.loadItem(session, requestedItemId) }
                .onSuccess { details ->
                    activity?.runOnUiThread {
                        if (!isCurrentRequest(generation, requestedItemId)) return@runOnUiThread
                        val resolvedDetails = DetailsNavigationMetadataPolicy.merge(currentItem, details)
                        currentItem = resolvedDetails
                        tracksReady = !isVideoTrackItem(resolvedDetails)
                        renderPrimary(
                            resolvedDetails,
                            fullDetails = true,
                            preserveActionFocus = true
                        )
                        renderSecondary(resolvedDetails, generation)
                        schedulePendingDetailsRestore()
                        if (isVideoTrackItem(resolvedDetails)) {
                            loadPlaybackMetadata(resolvedDetails, generation)
                        }
                        reportInteractive()
                    }
                }
                .onFailure { error ->
                    activity?.runOnUiThread {
                        if (!isCurrentRequest(generation, requestedItemId)) return@runOnUiThread
                        renderLoadFailure(error, generation)
                    }
                }
        }
    }

    private fun isVideoTrackItem(item: MediaItem): Boolean =
        DetailsPlaybackMetadataPolicy.needsVideoTracks(item)

    private fun loadPlaybackMetadata(item: MediaItem, generation: Int) {
        launchApiWork(WORK_PLAYBACK_INFO, "ptv-playback-info-${item.id}") {
            runCatching {
                api.loadPlaybackInfoMetadata(
                    session,
                    item.id,
                    ConnectionSpeed.fromStored(settings.connectionSpeed).negotiationBitrate().toLong()
                )
            }.onSuccess { playbackInfo ->
                activity?.runOnUiThread {
                    if (!isCurrentRequest(generation, item.id)) return@runOnUiThread
                    val updated = (currentItem ?: item).copy(
                        mediaSourceId = playbackInfo.mediaSourceId,
                        audioTracks = playbackInfo.audioTracks,
                        subtitleTracks = playbackInfo.subtitleTracks
                    )
                    currentItem = updated
                    tracksReady = true
                    rerenderActionsPreservingFocus(updated)
                }
            }.onFailure {
                activity?.runOnUiThread {
                    if (!isCurrentRequest(generation, item.id)) return@runOnUiThread
                    tracksReady = true
                    rerenderActionsPreservingFocus(currentItem ?: item)
                }
            }
        }
    }

    private fun isCurrentRequest(generation: Int, requestedItemId: String): Boolean =
        isAdded && !destroyed && generation == requestGeneration && itemId == requestedItemId

    private fun renderLoadFailure(error: Throwable, generation: Int) {
        val fallbackItem = currentItem
        if (fallbackItem != null) {
            Toast.makeText(requireContext(), "Some details could not be refreshed.", Toast.LENGTH_SHORT).show()
            if (fallbackItem.type.equals("Season", ignoreCase = true)) {
                // A lightweight Season card already carries the parent Series identity. Preserve
                // the requested route when the full item refresh times out and load its episodes
                // from that safe seed instead of leaving a blank secondary surface.
                renderSecondary(fallbackItem, generation)
            } else {
                seasonsSettled = true
                episodesSettled = true
                relatedSettled = true
                schedulePendingActionSecondaryFocus()
            }
            return
        }
        resetTitleArtwork("Details unavailable")
        overview.text = error.message ?: "The server did not return this item."
        actionsRow.removeAllViews()
        actionsRow.addView(actionButton("Retry", false, "Retry details") { loadDetails(itemId) })
    }

    private fun renderPrimary(
        item: MediaItem,
        fullDetails: Boolean,
        preserveActionFocus: Boolean = false
    ) {
        currentItem = item
        itemId = item.id

        // 1. Side rail. Movies lead with their poster; timing reflects remaining playback.
        renderSideInfo(item)

        // 2. Center Info
        resetTitleArtwork(item.title)
        metadataContainer.removeAllViews()

        if (
            (item.type.equals("Episode", ignoreCase = true) ||
                item.type.equals("Season", ignoreCase = true)) &&
            DetailsArtworkPolicy.logoCandidates(item).isNotEmpty()
        ) {
            secondaryTitle.text = item.title
        }

        if (item.communityRating != null && item.communityRating > 0) {
            metadataContainer.addView(ImageView(requireContext()).apply { setImageResource(R.drawable.ic_rating_star) }, LinearLayout.LayoutParams(requireContext().dim(R.dimen.tv_rating_icon_size), requireContext().dim(R.dimen.tv_rating_icon_size)))
            metadataContainer.addView(textView(String.format(Locale.getDefault(), " %.1f", item.communityRating), R.dimen.tv_text_size_metadata, R.color.tv_text_primary, true))
            metadataContainer.addView(View(requireContext()), LinearLayout.LayoutParams(requireContext().dim(R.dimen.tv_spacing_medium), 1))
        }

        if (item.criticRating != null && item.criticRating > 0) {
            metadataContainer.addView(ImageView(requireContext()).apply { setImageResource(R.drawable.ic_rating_tomato) }, LinearLayout.LayoutParams(requireContext().dim(R.dimen.tv_rating_icon_size), requireContext().dim(R.dimen.tv_rating_icon_size)))
            metadataContainer.addView(textView(String.format(Locale.getDefault(), " %d%%", item.criticRating.toInt()), R.dimen.tv_text_size_metadata, R.color.tv_text_primary, true))
            metadataContainer.addView(View(requireContext()), LinearLayout.LayoutParams(requireContext().dim(R.dimen.tv_spacing_medium), 1))
        }

        metadataContainer.addView(textView(metadataText(item), R.dimen.tv_text_size_metadata, R.color.tv_text_secondary))

        val cleanOverview = TextSanitizer.sanitize(item.overview)
        overview.text = cleanOverview
        overview.visibility = if (cleanOverview.isBlank()) View.GONE else View.VISIBLE

        renderArtwork(item, fullDetails)
        if (preserveActionFocus) {
            rerenderActionsPreservingFocus(item)
        } else {
            renderActions(item)
        }
        if (fullDetails && item.type == "Series") loadNextUp(item, requestGeneration)
    }

    private fun renderSideInfo(item: MediaItem) {
        sideInfo.removeAllViews()
        posterGeneration++
        poster.dispose()
        poster.setImageDrawable(null)
        poster.visibility = View.GONE

        if (item.type.equals("Movie", ignoreCase = true)) {
            val posterCandidates = DetailsArtworkPolicy.posterCandidates(item)
            if (posterCandidates.isNotEmpty()) {
                poster.contentDescription = "${item.title} poster"
                poster.visibility = View.VISIBLE
                sideInfo.addView(
                    poster,
                    LinearLayout.LayoutParams(
                        requireContext().dim(R.dimen.tv_poster_width),
                        requireContext().dim(R.dimen.tv_poster_height)
                    ).apply {
                        bottomMargin = requireContext().dim(R.dimen.tv_spacing_medium)
                    }
                )
                loadPosterArtwork(item, posterCandidates, generation = posterGeneration)
            }
        }

        item.director?.takeIf(String::isNotBlank)?.let { director ->
            addSideInfoValue("DIRECTED BY", director)
        }
        if (
            !item.type.equals("Series", ignoreCase = true) &&
            !item.type.equals("Season", ignoreCase = true)
        ) {
            DetailsTimePolicy.playTime(item.runtimeTicks)?.let { playTime ->
                addSideInfoValue("PLAY TIME", playTime)
                DetailsTimePolicy.endsAtMillis(
                    nowMillis = System.currentTimeMillis(),
                    runtimeTicks = item.runtimeTicks,
                    playbackPositionTicks = item.playbackPositionTicks
                )?.let { endsAt ->
                    val time = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(endsAt))
                    addSideInfoValue("ENDS", time, trailingSpace = false)
                }
            }
        }
        sideInfo.visibility = if (sideInfo.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun addSideInfoValue(label: String, value: String, trailingSpace: Boolean = true) {
        sideInfo.addView(textView(label, R.dimen.tv_text_size_metadata, R.color.tv_text_muted, true))
        sideInfo.addView(
            textView(value, R.dimen.tv_text_size_metadata, R.color.tv_text_secondary),
            topMargin(R.dimen.tv_spacing_small)
        )
        if (trailingSpace) {
            sideInfo.addView(
                View(requireContext()),
                LinearLayout.LayoutParams(1, requireContext().dim(R.dimen.tv_spacing_medium))
            )
        }
    }

    private fun resetTitleArtwork(fallbackTitle: String) {
        titleLogoGeneration++
        logo.dispose()
        logo.setImageDrawable(null)
        logo.visibility = View.GONE
        logo.contentDescription = null
        title.text = fallbackTitle
        title.visibility = View.VISIBLE
        secondaryTitle.text = ""
        secondaryTitle.visibility = View.GONE
    }

    private fun loadTitleLogo(
        item: MediaItem,
        candidates: List<DetailsArtworkRef>,
        generation: Int,
        index: Int = 0
    ) {
        if (index >= candidates.size) {
            if (currentItem?.id == item.id && titleLogoGeneration == generation) {
                logo.setImageDrawable(null)
                logo.visibility = View.GONE
                title.visibility = View.VISIBLE
                secondaryTitle.visibility = View.GONE
            }
            return
        }
        val candidate = candidates[index]
        logo.load(artworkUrl(candidate)) {
            crossfade(features.transitions)
            placeholder(android.graphics.Color.TRANSPARENT.toDrawable())
            size(DETAILS_LOGO_WIDTH, DETAILS_LOGO_HEIGHT)
            setParameter(
                PtvCoilEventListenerFactory.CATEGORY_PARAMETER,
                PtvCoilEventListenerFactory.CATEGORY_DETAILS_LOGO,
                null
            )
            listener(
                onSuccess = { _, _ ->
                    logo.post {
                        if (
                            isAdded && !destroyed && currentItem?.id == item.id &&
                            titleLogoGeneration == generation
                        ) {
                            logo.contentDescription = item.seriesName?.takeIf(String::isNotBlank) ?: item.title
                            logo.visibility = View.VISIBLE
                            title.visibility = View.GONE
                            secondaryTitle.visibility = if (
                                item.type.equals("Episode", ignoreCase = true) ||
                                item.type.equals("Season", ignoreCase = true)
                            ) View.VISIBLE else View.GONE
                        }
                    }
                },
                onError = { _, _ ->
                    logo.post {
                        if (
                            isAdded && !destroyed && currentItem?.id == item.id &&
                            titleLogoGeneration == generation
                        ) {
                            loadTitleLogo(item, candidates, generation, index + 1)
                        }
                    }
                }
            )
        }
    }

    private fun loadPosterArtwork(
        item: MediaItem,
        candidates: List<DetailsArtworkRef>,
        index: Int = 0,
        generation: Int
    ) {
        if (index >= candidates.size) {
            if (currentItem?.id == item.id && posterGeneration == generation) {
                poster.setImageDrawable(null)
                poster.visibility = View.GONE
                sideInfo.visibility = if (sideInfo.children().any { it.visibility == View.VISIBLE }) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }
            return
        }
        val candidate = candidates[index]
        poster.load(artworkUrl(candidate)) {
            crossfade(features.transitions)
            placeholder(android.graphics.Color.TRANSPARENT.toDrawable())
            size(
                requireContext().dim(R.dimen.tv_poster_width),
                requireContext().dim(R.dimen.tv_poster_height)
            )
            setParameter(
                PtvCoilEventListenerFactory.CATEGORY_PARAMETER,
                PtvCoilEventListenerFactory.CATEGORY_DETAILS_POSTER,
                null
            )
            allowRgb565(features.rgb565Posters)
            listener(
                onError = { _, _ ->
                    poster.post {
                        if (
                            isAdded && !destroyed && currentItem?.id == item.id &&
                            posterGeneration == generation
                        ) {
                            loadPosterArtwork(item, candidates, index + 1, generation)
                        }
                    }
                }
            )
        }
    }

    private fun renderArtwork(item: MediaItem, fullDetails: Boolean) {
        val logos = DetailsArtworkPolicy.logoCandidates(item)
        if (logos.isNotEmpty()) {
            val generation = titleLogoGeneration
            loadTitleLogo(item, logos, generation)
        }

        if (!fullDetails || features.detailsBackdrop == DetailsBackdropMode.NONE) return
        handler.removeCallbacksAndMessages(BACKDROP_HANDLER_TOKEN)
        val load = Runnable {
            if (isAdded && !destroyed && currentItem?.id == item.id) {
                loadArtwork(backdrop, DetailsArtworkPolicy.backdropCandidates(item), item.id)
            }
        }
        if (features.detailsBackdrop == DetailsBackdropMode.DELAYED) {
            handler.postAtTime(load, BACKDROP_HANDLER_TOKEN, SystemClock.uptimeMillis() + DETAILS_BACKDROP_DELAY_MS)
        } else {
            load.run()
        }
    }

    private fun loadArtwork(
        target: ImageView,
        candidates: List<DetailsArtworkRef>,
        expectedItemId: String,
        index: Int = 0
    ) {
        target.dispose()
        if (index >= candidates.size) {
            target.setImageDrawable(PTVColors.background.toDrawable())
            return
        }
        val candidate = candidates[index]
        target.load(artworkUrl(candidate)) {
            crossfade(features.transitions)
            placeholder(PTVColors.background.toDrawable())
            size(DETAILS_BACKDROP_WIDTH, DETAILS_BACKDROP_HEIGHT)
            setParameter(
                PtvCoilEventListenerFactory.CATEGORY_PARAMETER,
                PtvCoilEventListenerFactory.CATEGORY_DETAILS_BACKDROP,
                null
            )
            allowRgb565(false)
            if (target === backdrop && candidate.blurred) {
                transformations(PosterBackdropBlurTransformation)
            }
            listener(
                onError = { _, _ ->
                    target.post {
                        if (isAdded && !destroyed && currentItem?.id == expectedItemId) {
                            loadArtwork(target, candidates, expectedItemId, index + 1)
                        }
                    }
                }
            )
        }
    }

    private fun artworkUrl(ref: DetailsArtworkRef): String {
        return when (ref.kind) {
            DetailsArtworkKind.PRIMARY -> api.primaryImageUrl(session, ref.itemId, ref.tag, 400)
            DetailsArtworkKind.THUMB -> api.thumbUrl(session, ref.itemId, ref.tag, 640)
            DetailsArtworkKind.BACKDROP -> session.serverUrl + "/Items/" + ref.itemId + "/Images/Backdrop?maxWidth=1280&tag=" + ref.tag
            DetailsArtworkKind.LOGO -> session.serverUrl + "/Items/" + ref.itemId + "/Images/Logo?maxWidth=400&tag=" + ref.tag
        }
    }
    
    private fun renderActions(
        item: MediaItem,
        restoreDescription: String? = null,
        restoreIndex: Int? = null
    ) {
        actionsRow.removeAllViews()
        episodePlayAllAction = null
        episodeShuffleAllAction = null
        if (item.type.equals("Season", ignoreCase = true)) {
            renderSeasonActions(item, restoreDescription, restoreIndex)
            return
        }
        val playableVideo = isVideoTrackItem(item)
        val playLabel = when {
            item.type == "Book" && item.playbackPositionTicks > 0 -> "Resume Reading"
            item.type == "Book" -> "Read"
            item.type == "Series" && nextUpItem?.playbackPositionTicks?.let { it > 0 } == true -> "Resume Next"
            item.type == "Series" && nextUpItem != null -> "Play Next"
            item.type == "Series" -> "Play Series"
            item.playbackPositionTicks > 0 -> "Resume"
            else -> "Play"
        }
        val play = premiumActionButton(playLabel, android.R.drawable.ic_media_play) {
            when (item.type) {
                "Book" -> ReaderActivity.start(requireContext(), currentItem ?: item)
                "Series" -> playSeries(currentItem ?: item)
                else -> startPlayback(currentItem ?: item)
            }
        }
        primaryAction = play
        addAction(play)

        EpisodeSeriesNavigationPolicy.seriesId(item)?.let { seriesId ->
            addAction(
                actionButton("View Series", true, SERIES_DESCRIPTION) {
                    openNestedDetails(seriesId)
                }.apply { tag = VIEW_SERIES_FOCUS_TAG }
            )
        }

        addAction(
            iconActionButton(
                R.drawable.ic_action_favorite,
                item.isFavorite,
                if (item.isFavorite) "Remove from favorites" else "Add to favorites"
            ) { updateFavorite(item) },
            square = true
        )
        addAction(
            iconActionButton(
                R.drawable.ic_action_watched,
                item.isPlayed,
                if (item.isPlayed) "Mark unplayed" else "Mark played"
            ) { updatePlayed(item) },
            square = true
        )

        if (playableVideo) {
            if (item.audioTracks.size > 1) {
                addAction(actionButton("Audio", true, AUDIO_DESCRIPTION) { showAudioSelection(item, it) })
            }
            if (item.subtitleTracks.isNotEmpty()) {
                addAction(actionButton("Subtitles", true, SUBTITLE_DESCRIPTION) { showSubtitleSelection(item, it) })
            }
            addAction(actionButton("Quality", true, SPEED_DESCRIPTION) { showConnectionSpeedSelection(it) })
        }

        actionsRow.children().forEach { action ->
            action.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) requestFirstSeasonOrSecondary() else false
            }
        }
        if (restoreDescription != null || restoreIndex != null) {
            // Capture the concrete row. A fast Details close can destroy the fragment view before
            // this focus callback runs; touching the clearable actionsRow property then crashes.
            val row = actionsRow
            row.post {
                if (destroyed || !row.isAttachedToWindow) return@post
                val target = restoreDescription?.let { description ->
                    row.children().firstOrNull { it.contentDescription == description }
                } ?: restoreIndex?.takeIf { it in 0 until row.childCount }?.let(row::getChildAt)
                target?.requestFocus()
            }
        } else if (currentItem != null && root.findFocus() == null) {
            play.requestFocus()
        }
    }

    private fun renderSeasonActions(
        season: MediaItem,
        restoreDescription: String?,
        restoreIndex: Int?
    ) {
        var defaultFocus: View? = null
        if (currentSeasonEpisodes.isNotEmpty()) {
            val playAll = premiumActionButton("Play All", android.R.drawable.ic_media_play) {
                rememberExternalFocus(activity?.currentFocus)
                VideoPlayerActivity.startSeason(requireContext(), currentSeasonEpisodes, shuffle = false)
            }.apply {
                contentDescription = "Play all episodes in this season"
                id = View.generateViewId()
            }
            val shuffleAll = actionButton(
                "Shuffle All",
                true,
                "Shuffle all episodes in this season"
            ) { opener ->
                rememberExternalFocus(opener)
                VideoPlayerActivity.startSeason(requireContext(), currentSeasonEpisodes, shuffle = true)
            }.apply { id = View.generateViewId() }
            playAll.nextFocusRightId = shuffleAll.id
            shuffleAll.nextFocusLeftId = playAll.id
            episodePlayAllAction = playAll
            episodeShuffleAllAction = shuffleAll
            addAction(playAll)
            addAction(shuffleAll)
            defaultFocus = playAll
        }

        EpisodeSeriesNavigationPolicy.seriesId(season)?.let { seriesId ->
            val viewSeries = actionButton("View Series", true, SERIES_DESCRIPTION) {
                openNestedDetails(seriesId)
            }.apply { tag = VIEW_SERIES_FOCUS_TAG }
            addAction(viewSeries)
            if (defaultFocus == null) defaultFocus = viewSeries
        }

        val favorite = iconActionButton(
            R.drawable.ic_action_favorite,
            season.isFavorite,
            if (season.isFavorite) "Remove from favorites" else "Add to favorites"
        ) { updateFavorite(season) }
        addAction(favorite, square = true)
        if (defaultFocus == null) defaultFocus = favorite

        val played = iconActionButton(
            R.drawable.ic_action_watched,
            season.isPlayed,
            if (season.isPlayed) "Mark unplayed" else "Mark played"
        ) { updatePlayed(season) }
        addAction(played, square = true)

        primaryAction = defaultFocus
        actionsRow.children().forEach { action ->
            action.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) requestSeasonEpisodeRow(season.id) else false
            }
        }
        restoreActionFocus(defaultFocus, restoreDescription, restoreIndex)
    }

    private fun restoreActionFocus(
        defaultFocus: View?,
        restoreDescription: String?,
        restoreIndex: Int?
    ) {
        if (restoreDescription != null || restoreIndex != null) {
            val row = actionsRow
            row.post {
                if (destroyed || !row.isAttachedToWindow) return@post
                val target = restoreDescription?.let { description ->
                    row.children().firstOrNull { it.contentDescription == description }
                } ?: restoreIndex?.takeIf { it in 0 until row.childCount }?.let(row::getChildAt)
                (target ?: defaultFocus)?.requestFocus()
            }
        } else if (currentItem != null && root.findFocus() == null) {
            defaultFocus?.requestFocus()
        }
    }

    private fun rerenderActionsPreservingFocus(item: MediaItem) {
        val focused = activity?.currentFocus
        val focusedIndex = focused?.takeIf { it.parent === actionsRow }?.let(actionsRow::indexOfChild)?.takeIf { it >= 0 }
        renderActions(item, restoreDescription = focused?.contentDescription?.toString(), restoreIndex = focusedIndex)
    }

    private fun LinearLayout.children(): Sequence<View> =
        (0 until childCount).asSequence().map(::getChildAt)

    private fun addAction(view: View, square: Boolean = false) {
        val context = requireContext()
        actionsRow.addView(
            view,
            LinearLayout.LayoutParams(
                if (square) context.dim(R.dimen.tv_details_action_height) else -2,
                context.dim(R.dimen.tv_details_action_height)
            ).apply {
                if (actionsRow.childCount > 1) marginStart = context.dim(R.dimen.tv_spacing_small)
            }
        )
    }

    private fun actionButton(
        label: String,
        secondary: Boolean,
        description: String,
        action: (View) -> Unit
    ) = Button(requireContext()).apply {
        text = label
        contentDescription = description
        isAllCaps = false
        includeFontPadding = false
        gravity = Gravity.CENTER
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setTextSizeRes(R.dimen.tv_details_action_text_size)
        setTextColor(requireContext().getColor(R.color.tv_text_primary))
        setBackgroundResource(if (secondary) R.drawable.tv_button_secondary else R.drawable.tv_button_primary)
        val horizontalPadding = requireContext().dim(R.dimen.tv_details_action_padding_horizontal)
        setPadding(horizontalPadding, 0, horizontalPadding, 0)
        setOnClickListener { action(this) }
        setOnFocusChangeListener { v, f -> PTVShapes.applyFocusEffect(v, f) }
    }

    private fun premiumActionButton(label: String, iconRes: Int, action: () -> Unit): Button =
        Button(requireContext()).apply {
            text = label
            isAllCaps = false
            includeFontPadding = false
            gravity = Gravity.CENTER
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            val icon = AppCompatResources.getDrawable(requireContext(), iconRes)?.mutate()?.apply {
                val iconSize = requireContext().dim(R.dimen.tv_details_action_icon_size)
                setBounds(0, 0, iconSize, iconSize)
            }
            setCompoundDrawables(icon, null, null, null)
            compoundDrawablePadding = requireContext().dim(R.dimen.tv_spacing_small)
            setTextSizeRes(R.dimen.tv_details_action_text_size)
            setTextColor(requireContext().getColor(R.color.tv_text_primary))
            setBackgroundResource(R.drawable.tv_button_primary)
            val horizontalPadding = requireContext().dim(R.dimen.tv_details_action_padding_horizontal)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            setOnClickListener { action() }
            setOnFocusChangeListener { v, f -> PTVShapes.applyFocusEffect(v, f) }
        }

    private fun iconActionButton(
        iconRes: Int,
        active: Boolean,
        description: String,
        action: () -> Unit
    ): PtvStatusIconButton = PtvStatusIconButton(
        requireContext(),
        iconRes,
        description,
        active,
        action
    )

    private fun showAudioSelection(item: MediaItem, opener: View) {
        val options = listOf("Default / Auto") + item.audioTracks.map(::audioTrackLabel)
        val selectedIndex = PendingPlaybackPreferences.get(item.id).audioIndex?.let { selected ->
            item.audioTracks.indexOfFirst { it.index == selected }.takeIf { it >= 0 }?.plus(1)
        } ?: 0
        PtvSelectionDialog(
            requireContext(),
            "Audio",
            options,
            selectedIndex = selectedIndex,
            defaultIndex = 0,
            restoreFocusTo = opener
        ) { index ->
            PendingPlaybackPreferences.setAudio(item.id, if (index == 0) null else item.audioTracks[index - 1].index)
            rerenderActionsPreservingFocus(currentItem ?: item)
        }.show()
    }

    private fun showSubtitleSelection(item: MediaItem, opener: View) {
        val options = listOf("Default / Auto", "Off") + item.subtitleTracks.map(::subtitleTrackLabel)
        val preference = PendingPlaybackPreferences.get(item.id)
        val selectedIndex = when (preference.subtitleMode) {
            SubtitleSelectionMode.DEFAULT -> 0
            SubtitleSelectionMode.OFF -> 1
            SubtitleSelectionMode.TRACK -> item.subtitleTracks
                .indexOfFirst { it.index == preference.subtitleIndex }
                .takeIf { it >= 0 }
                ?.plus(2)
                ?: 0
        }
        PtvSelectionDialog(
            requireContext(),
            "Subtitles",
            options,
            selectedIndex = selectedIndex,
            defaultIndex = 0,
            restoreFocusTo = opener
        ) { index ->
            when (index) {
                0 -> PendingPlaybackPreferences.setSubtitle(item.id, SubtitleSelectionMode.DEFAULT)
                1 -> PendingPlaybackPreferences.setSubtitle(item.id, SubtitleSelectionMode.OFF)
                else -> PendingPlaybackPreferences.setSubtitle(item.id, SubtitleSelectionMode.TRACK, item.subtitleTracks[index - 2].index)
            }
            rerenderActionsPreservingFocus(currentItem ?: item)
        }.show()
    }

    private fun showConnectionSpeedSelection(opener: View) {
        val options = ConnectionSpeed.entries
        PtvSelectionDialog(requireContext(), "Connection Speed", options.map { it.displayLabel }, restoreFocusTo = opener) { index ->
            settings.connectionSpeed = options[index].wireValue
            rerenderActionsPreservingFocus(currentItem!!)
        }.show()
    }

    private fun audioTrackLabel(track: AudioTrack): String = buildList {
        track.language?.takeIf(String::isNotBlank)?.let(::add)
        track.title?.takeIf(String::isNotBlank)?.let(::add)
        track.codec?.takeIf(String::isNotBlank)?.let(::add)
        track.channels?.let { add("$it ch") }
    }.joinToString(" · ")

    private fun subtitleTrackLabel(track: SubtitleTrack): String = buildList {
        track.language?.takeIf(String::isNotBlank)?.let(::add)
        track.title?.takeIf(String::isNotBlank)?.let(::add)
        if (track.isForced) add("Forced")
    }.joinToString(" · ")

    private fun startPlayback(item: MediaItem) {
        // Reuse the fully loaded Details item in the player. Besides avoiding a redundant request,
        // this preserves the exact MediaSourceId that owns the displayed stream indices.
        rememberExternalFocus(activity?.currentFocus)
        MediaDetailsSeedStore.put(item)
        VideoPlayerActivity.start(requireContext(), item.id, item.playbackPositionTicks, PendingPlaybackPreferences.get(item.id))
    }

    private fun rememberExternalFocus(target: View?) {
        target
            ?.takeIf { focused -> isDescendant(root, focused) }
            ?.let { focusBeforeExternalActivity = WeakReference(it) }
    }

    private fun playSeries(series: MediaItem) {
        nextUpItem?.let { startPlayback(it); return }
        launchApiWork(WORK_SERIES_PLAY, "ptv-series-play-${series.id}") {
            val episode = runCatching {
                api.loadNextUpForSeries(session, series.id)
                    ?: api.loadSeasons(session, series.id).firstOrNull()?.let { s ->
                        api.loadEpisodes(session, series.id, s.id).firstOrNull()
                    }
            }.getOrNull()
            activity?.runOnUiThread {
                if (episode != null && isAdded && !destroyed && currentItem?.id == series.id) {
                    startPlayback(episode)
                }
            }
        }
    }

    private fun loadNextUp(series: MediaItem, generation: Int) {
        launchApiWork(WORK_NEXT_UP, "ptv-details-next-up-${series.id}") {
            val next = runCatching { api.loadNextUpForSeries(session, series.id) }.getOrNull()
            activity?.runOnUiThread {
                if (isCurrentRequest(generation, series.id)) {
                    nextUpItem = next
                    rerenderActionsPreservingFocus(currentItem ?: series)
                }
            }
        }
    }

    private fun updateFavorite(item: MediaItem) {
        updateStatus(item, favorite = true)
    }

    private fun updatePlayed(item: MediaItem) {
        updateStatus(item, favorite = false)
    }

    private fun updateStatus(item: MediaItem, favorite: Boolean) {
        launchApiWork(WORK_STATUS, "ptv-details-status") {
            val result = runCatching {
                if (favorite) api.setFavorite(session, item.id, !item.isFavorite)
                else api.setPlayed(session, item.id, !item.isPlayed)
            }
            activity?.runOnUiThread {
                if (isAdded && !destroyed && currentItem?.id == item.id) {
                    result.onSuccess {
                        val updated = (currentItem ?: item).let { if (favorite) it.copy(isFavorite = !item.isFavorite) else it.copy(isPlayed = !item.isPlayed) }
                        currentItem = updated
                        rerenderActionsPreservingFocus(updated)
                    }
                }
            }
        }
    }

    private fun renderSecondary(item: MediaItem, generation: Int) {
        if (item.type.equals("Season", ignoreCase = true)) {
            castContainer.removeAllViews()
            castContainer.visibility = View.GONE
        } else {
            renderCast(item)
        }
        val loading = DetailsSecondaryLoadingPolicy.decide(item)
        seasonsSettled = loading.kind != DetailsSecondaryLoadKind.SERIES_SEASONS
        episodesSettled = loading.kind != DetailsSecondaryLoadKind.SEASON_EPISODES
        relatedSettled = loading.kind == DetailsSecondaryLoadKind.SEASON_EPISODES
        when (loading.kind) {
            DetailsSecondaryLoadKind.SERIES_SEASONS -> {
                loadSeasons(item, generation)
                loadRelated(item, generation)
            }
            DetailsSecondaryLoadKind.SEASON_EPISODES -> {
                seasonsContainer.visibility = View.GONE
                relatedContainer.visibility = View.GONE
                loadSeasonEpisodes(item, generation)
            }
            DetailsSecondaryLoadKind.RELATED_ONLY -> {
                seasonsContainer.visibility = View.GONE
                episodesContainer.visibility = View.GONE
                loadRelated(item, generation)
            }
        }
    }

    private fun markSecondaryUnresolved(item: MediaItem) {
        val loading = DetailsSecondaryLoadingPolicy.decide(item)
        seasonsSettled = loading.kind != DetailsSecondaryLoadKind.SERIES_SEASONS
        episodesSettled = loading.kind != DetailsSecondaryLoadKind.SEASON_EPISODES
        relatedSettled = loading.kind == DetailsSecondaryLoadKind.SEASON_EPISODES
    }

    private fun renderCast(item: MediaItem) {
        castContainer.removeAllViews()
        if (item.people.isEmpty()) {
            castContainer.visibility = View.GONE
            return
        }
        castContainer.visibility = View.VISIBLE
        castContainer.addView(sectionLabel("Cast / Crew"))
        val manager = TvLinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        val list = RecyclerView(requireContext()).apply {
            layoutManager = manager
            adapter = PersonAdapter(item.people)
            applyRenderingTuning(manager, 6)
        }
        castContainer.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = requireContext().dim(R.dimen.tv_spacing_small) })
    }

    private fun loadSeasons(series: MediaItem, generation: Int) {
        seasonsSettled = false
        episodesSettled = false
        launchApiWork(WORK_SEASONS, "ptv-seasons-${series.id}") {
            runCatching { api.loadSeasons(session, series.id) }
                .onSuccess { seasons ->
                    activity?.runOnUiThread {
                        if (!isCurrentRequest(generation, series.id)) return@runOnUiThread
                        seasonsSettled = true
                        if (seasons.isEmpty()) {
                            episodesSettled = true
                            clearSeasonShelf()
                            clearEpisodeShelf()
                            seasonsContainer.visibility = View.GONE
                            episodesContainer.visibility = View.GONE
                        } else {
                            showSeasons(series, seasons)
                        }
                        schedulePendingActionSecondaryFocus()
                        schedulePendingDetailsRestore()
                    }
                }
                .onFailure {
                    activity?.runOnUiThread {
                        if (!isCurrentRequest(generation, series.id)) return@runOnUiThread
                        seasonsSettled = true
                        episodesSettled = true
                        clearSeasonShelf()
                        seasonsContainer.visibility = View.GONE
                        clearEpisodeShelf()
                        episodesContainer.visibility = View.GONE
                        pendingEpisodeFocusSeasonId = null
                        schedulePendingActionSecondaryFocus()
                        schedulePendingDetailsRestore()
                    }
                }
        }
    }

    private fun showSeasons(series: MediaItem, seasons: List<MediaItem>) {
        clearEpisodeShelf()
        episodesContainer.visibility = View.GONE
        episodesSettled = true
        seasonsContainer.removeAllViews()
        seasonsContainer.visibility = View.VISIBLE
        seasonsContainer.addView(sectionLabel("Seasons"))
        val manager = TvLinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        val list = RecyclerView(requireContext()).apply {
            layoutManager = manager
            adapter = SeasonAdapter(series, seasons)
            applyRenderingTuning(manager, 7)
        }
        seasonList = list
        seasonsContainer.addView(list, LinearLayout.LayoutParams(-1, requireContext().dim(R.dimen.tv_poster_height) + requireContext().dim(R.dimen.tv_shelf_extra_height)))
        val seasonToRestore = pendingRestore
            ?.takeIf { it.itemId == series.id }
            ?.seasonId
            ?.let { savedSeasonId -> seasons.firstOrNull { it.id == savedSeasonId } }
            ?: seasons.first()
        currentSeasonId = seasonToRestore.id
        list.post {
            schedulePendingActionSecondaryFocus()
            schedulePendingDetailsRestore()
        }
    }

    private fun loadSeasonEpisodes(season: MediaItem, generation: Int) {
        val seriesId = EpisodeSeriesNavigationPolicy.seriesId(season)
        currentSeasonId = season.id
        currentSeasonEpisodes = emptyList()
        episodesSettled = false
        val episodeRequest = ++episodeRequestGeneration
        showEpisodesLoading(season)
        if (seriesId == null) {
            episodesSettled = true
            showSeasonEpisodesFailure(season, generation, "Series information is unavailable.")
            return
        }
        launchApiWork(WORK_EPISODES, "ptv-season-details-episodes-${season.id}") {
            runCatching { api.loadEpisodes(session, seriesId, season.id) }
                .onSuccess { episodes ->
                    activity?.runOnUiThread {
                        if (
                            !isCurrentRequest(generation, season.id) ||
                            currentSeasonId != season.id ||
                            episodeRequestGeneration != episodeRequest
                        ) return@runOnUiThread
                        episodesSettled = true
                        currentSeasonEpisodes = episodes
                        showEpisodes(episodes, season.id)
                        rerenderActionsPreservingFocus(currentItem ?: season)
                    }
                }
                .onFailure {
                    activity?.runOnUiThread {
                        if (
                            !isCurrentRequest(generation, season.id) ||
                            currentSeasonId != season.id ||
                            episodeRequestGeneration != episodeRequest
                        ) return@runOnUiThread
                        episodesSettled = true
                        currentSeasonEpisodes = emptyList()
                        showSeasonEpisodesFailure(season, generation)
                        rerenderActionsPreservingFocus(currentItem ?: season)
                    }
                }
        }
    }

    private fun showSeasonEpisodesFailure(
        season: MediaItem,
        generation: Int,
        message: String = "Episodes could not be loaded."
    ) {
        clearEpisodeShelf()
        episodesContainer.visibility = View.VISIBLE
        episodesContainer.addView(sectionLabel("Episodes"))
        episodesContainer.addView(
            textView(message, R.dimen.tv_text_size_body, R.color.tv_text_secondary),
            topMargin(R.dimen.tv_spacing_small)
        )
        episodesContainer.addView(
            actionButton("Retry", true, "Retry episodes") {
                loadSeasonEpisodes(currentItem ?: season, generation)
            },
            LinearLayout.LayoutParams(-2, requireContext().dim(R.dimen.tv_details_action_height)).apply {
                topMargin = requireContext().dim(R.dimen.tv_spacing_small)
            }
        )
    }

    private fun showEpisodesLoading(season: MediaItem) {
        clearEpisodeShelf()
        episodesContainer.visibility = View.VISIBLE
        episodesContainer.addView(sectionLabel("Episodes"))
        episodesContainer.addView(
            textView(
                "Loading ${SeasonCardPolicy.label(season)} episodes\u2026",
                R.dimen.tv_text_size_body,
                R.color.tv_text_secondary
            ),
            topMargin(R.dimen.tv_spacing_small)
        )
    }

    private fun showEpisodes(episodes: List<MediaItem>, seasonId: String) {
        clearEpisodeShelf()
        episodesContainer.visibility = View.VISIBLE
        if (episodes.isEmpty()) {
            episodesContainer.addView(sectionLabel("Episodes"))
            episodesContainer.addView(
                textView(
                    "No episodes are available for this season.",
                    R.dimen.tv_text_size_body,
                    R.color.tv_text_secondary
                ),
                topMargin(R.dimen.tv_spacing_small)
            )
            pendingEpisodeFocusSeasonId = null
            return
        }
        episodesContainer.addView(sectionLabel("Episodes"))
        val manager = TvLinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        val list = RecyclerView(requireContext()).apply {
            layoutManager = manager
            adapter = EpisodeAdapter(episodes)
            applyRenderingTuning(manager, 5)
        }
        episodeList = list
        episodesContainer.addView(list, LinearLayout.LayoutParams(-1, requireContext().dim(R.dimen.tv_landscape_height) + requireContext().dim(R.dimen.tv_shelf_extra_height)))
        list.post {
            schedulePendingEpisodeFocus(seasonId)
            schedulePendingDetailsRestore()
        }
    }

    private fun clearEpisodeShelf() {
        releaseShelf(episodeList)
        episodeList = null
        episodesContainer.removeAllViews()
    }

    private fun clearSeasonShelf() {
        releaseShelf(seasonList)
        seasonList = null
        seasonsContainer.removeAllViews()
        currentSeasonId = null
    }

    private fun clearRelatedShelf() {
        releaseShelf(relatedList)
        relatedList = null
        relatedContainer.removeAllViews()
    }

    private fun releaseShelf(list: RecyclerView?) {
        list ?: return
        list.adapter = null
        list.layoutManager = null
        list.recycledViewPool.clear()
    }

    private fun clearSecondaryShelves() {
        clearSeasonShelf()
        clearEpisodeShelf()
        clearRelatedShelf()
        seasonsContainer.visibility = View.GONE
        episodesContainer.visibility = View.GONE
        relatedContainer.visibility = View.GONE
        pendingEpisodeFocusSeasonId = null
        pendingActionSeasonFocus = false
        currentSeasonEpisodes = emptyList()
        episodePlayAllAction = null
        episodeShuffleAllAction = null
    }

    private fun loadRelated(item: MediaItem, generation: Int) {
        relatedSettled = false
        launchApiWork(WORK_RELATED, "ptv-related-${item.id}") {
            runCatching { api.loadSimilar(session, item.id, 12) }
                .onSuccess { related ->
                    activity?.runOnUiThread {
                        if (!isCurrentRequest(generation, item.id)) return@runOnUiThread
                        relatedSettled = true
                        if (related.isEmpty()) {
                            clearRelatedShelf()
                            relatedContainer.visibility = View.GONE
                        } else {
                            showRelated(related)
                        }
                        schedulePendingActionSecondaryFocus()
                        schedulePendingDetailsRestore()
                    }
                }
                .onFailure {
                    activity?.runOnUiThread {
                        if (!isCurrentRequest(generation, item.id)) return@runOnUiThread
                        relatedSettled = true
                        clearRelatedShelf()
                        relatedContainer.visibility = View.GONE
                        schedulePendingActionSecondaryFocus()
                        schedulePendingDetailsRestore()
                    }
                }
        }
    }

    private fun showRelated(items: List<MediaItem>) {
        clearRelatedShelf()
        relatedContainer.visibility = View.VISIBLE
        relatedContainer.addView(sectionLabel("More Like This"))
        val manager = TvLinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        val list = RecyclerView(requireContext()).apply {
            layoutManager = manager
            adapter = RelatedAdapter(items)
            applyRenderingTuning(manager, 7)
        }
        relatedList = list
        relatedContainer.addView(list, LinearLayout.LayoutParams(-1, requireContext().dim(R.dimen.tv_poster_height) + requireContext().dim(R.dimen.tv_shelf_extra_height)))
        list.post {
            schedulePendingActionSecondaryFocus()
            schedulePendingDetailsRestore()
        }
    }

    private fun requestFirstSeasonOrSecondary(): Boolean {
        val seasonTarget = firstFocusableChild(seasonList)
        val relatedTarget = firstFocusableChild(relatedList)
        val decision = ActionSecondaryFocusPolicy.decide(
            hasFocusableSeason = seasonTarget != null,
            seasonState = shelfState(seasonList, seasonsSettled),
            hasFocusableRelated = relatedTarget != null,
            relatedState = shelfState(relatedList, relatedSettled)
        )
        return when (decision) {
            ActionSecondaryFocusDecision.MOVE_TO_SEASONS -> seasonTarget?.requestFocus() == true
            ActionSecondaryFocusDecision.MOVE_TO_RELATED -> relatedTarget?.requestFocus() == true
            ActionSecondaryFocusDecision.WAIT -> {
                pendingActionSeasonFocus = true
                (seasonList ?: relatedList)?.post { schedulePendingActionSecondaryFocus() }
                true
            }
            ActionSecondaryFocusDecision.PASS_THROUGH -> false
        }
    }

    private fun firstFocusableChild(list: RecyclerView?): View? {
        val recycler = list ?: return null
        return recycler.layoutManager?.getChildAt(0)
    }

    private fun shelfState(list: RecyclerView?, settled: Boolean): AsyncShelfState = when {
        !settled -> AsyncShelfState.LOADING
        (list?.adapter?.itemCount ?: 0) > 0 -> AsyncShelfState.NON_EMPTY
        else -> AsyncShelfState.EMPTY_OR_ERROR
    }

    private fun schedulePendingActionSecondaryFocus() {
        if (!pendingActionSeasonFocus) return
        val target = firstFocusableChild(seasonList) ?: firstFocusableChild(relatedList)
        if (target?.requestFocus() == true) {
            pendingActionSeasonFocus = false
        } else if (seasonsSettled && relatedSettled) {
            pendingActionSeasonFocus = false
        }
    }

    private fun requestSeasonEpisodeRow(seasonId: String): Boolean {
        val target = firstFocusableChild(episodeList)
        if (target?.requestFocus() == true) {
            scrollEpisodesIntoView()
            return true
        }
        if (!episodesSettled) {
            pendingEpisodeFocusSeasonId = seasonId
            return true
        }
        return false
    }

    private fun schedulePendingEpisodeFocus(seasonId: String) {
        if (pendingEpisodeFocusSeasonId != seasonId || currentSeasonId != seasonId) return
        val target = firstFocusableChild(episodeList) ?: return
        if (target.requestFocus()) {
            pendingEpisodeFocusSeasonId = null
            scrollEpisodesIntoView()
        }
    }

    private fun scrollEpisodesIntoView() {
        scroll.smoothScrollTo(0, episodesContainer.top)
    }

    private fun schedulePendingDetailsRestore() {
        val restore = pendingRestore?.takeIf { it.itemId == itemId } ?: return
        root.post {
            if (destroyed || pendingRestore !== restore || itemId != restore.itemId) return@post
            val target = restore.focusedItemId?.let { findFocusableViewWithTag(root, it) }
            if (restore.focusedItemId != null && target == null) return@post
            val focused = target?.requestFocus() ?: primaryAction?.requestFocus() ?: false
            if (focused) {
                scroll.scrollTo(0, restore.scrollY)
                pendingRestore = null
            }
        }
    }

    private fun findFocusableViewWithTag(view: View, itemTag: String): View? {
        if (view.tag == itemTag && view.isFocusable && view.isShown) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findFocusableViewWithTag(view.getChildAt(index), itemTag)?.let { return it }
        }
        return null
    }

    private inner class SeasonAdapter(
        private val series: MediaItem,
        private val seasons: List<MediaItem>
    ) : RecyclerView.Adapter<MediaCardHolder>() {
        override fun getItemCount(): Int = seasons.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            MediaCardHolder(MediaCardFactory.createView(parent, MediaCardPresentation.POSTER))
        override fun onBindViewHolder(holder: MediaCardHolder, position: Int) {
            val season = seasons[position]
            MediaCardFactory.bindView(holder, season, MediaCardPresentation.POSTER, session, api)
            holder.itemView.setOnClickListener {
                if (
                    SeasonDetailsNavigationPolicy.clickDecision(series, season) ==
                    SeasonDetailsClickDecision.OPEN_DETAILS
                ) {
                    currentSeasonId = season.id
                    openNestedDetails(SeasonDetailsNavigationPolicy.routeItem(series, season))
                }
            }
        }

        override fun onViewRecycled(holder: MediaCardHolder) {
            MediaCardFactory.recycleView(holder)
            holder.itemView.setOnClickListener(null)
            holder.itemView.setOnKeyListener(null)
            super.onViewRecycled(holder)
        }
    }

    private inner class EpisodeAdapter(private val episodes: List<MediaItem>) : RecyclerView.Adapter<MediaCardHolder>() {
        override fun getItemCount(): Int = episodes.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            MediaCardHolder(MediaCardFactory.createView(parent, MediaCardPresentation.LANDSCAPE))
        override fun onBindViewHolder(holder: MediaCardHolder, position: Int) {
            val ep = episodes[position]
            MediaCardFactory.bindView(holder, ep, MediaCardPresentation.LANDSCAPE, session, api)
            holder.itemView.setOnClickListener { openNestedDetails(ep) }
            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_UP) {
                    false
                } else {
                    val target = if (position == 0) episodePlayAllAction else episodeShuffleAllAction
                    target?.requestFocus() == true
                }
            }
        }

        override fun onViewRecycled(holder: MediaCardHolder) {
            MediaCardFactory.recycleView(holder)
            holder.itemView.setOnClickListener(null)
            holder.itemView.setOnKeyListener(null)
            super.onViewRecycled(holder)
        }
    }

    private inner class RelatedAdapter(private val items: List<MediaItem>) : RecyclerView.Adapter<MediaCardHolder>() {
        override fun getItemCount(): Int = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            MediaCardHolder(MediaCardFactory.createView(parent, MediaCardPresentation.POSTER))
        override fun onBindViewHolder(holder: MediaCardHolder, position: Int) {
            val item = items[position]
            MediaCardFactory.bindView(holder, item, MediaCardPresentation.POSTER, session, api)
            holder.itemView.setOnClickListener { openNestedDetails(item) }
        }

        override fun onViewRecycled(holder: MediaCardHolder) {
            MediaCardFactory.recycleView(holder)
            holder.itemView.setOnClickListener(null)
            super.onViewRecycled(holder)
        }
    }

    private inner class PersonAdapter(private val people: List<com.piggie.tv.data.models.Person>) : RecyclerView.Adapter<PersonAdapter.Holder>() {
        override fun getItemCount() = people.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, requireContext().dim(R.dimen.tv_spacing_medium), 0)
            }
            val avatar = ImageView(parent.context)
            val name = textView("", R.dimen.tv_text_size_metadata, R.color.tv_text_primary, true).apply { gravity = Gravity.CENTER }
            val role = textView("", R.dimen.tv_text_size_metadata, R.color.tv_text_secondary).apply { gravity = Gravity.CENTER }
            view.addView(avatar, LinearLayout.LayoutParams(requireContext().dim(R.dimen.tv_avatar_size), requireContext().dim(R.dimen.tv_avatar_size)))
            view.addView(name, topMargin(R.dimen.tv_spacing_small))
            view.addView(role)
            return Holder(view, avatar, name, role)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val p = people[position]
            holder.name.text = p.name
            holder.role.text = p.role ?: p.type
            holder.avatar.load("${session.serverUrl}/Items/${p.id}/Images/Primary?maxWidth=200") {
                placeholder(PTVColors.cardBackground.toDrawable())
                transformations(coil.transform.CircleCropTransformation())
                val avatarSize = requireContext().dim(R.dimen.tv_avatar_size)
                size(avatarSize, avatarSize)
                setParameter(
                    PtvCoilEventListenerFactory.CATEGORY_PARAMETER,
                    PtvCoilEventListenerFactory.CATEGORY_DETAILS_PERSON,
                    null
                )
            }
        }
        inner class Holder(view: View, val avatar: ImageView, val name: TextView, val role: TextView) : RecyclerView.ViewHolder(view)
    }

    private fun openNestedDetails(item: MediaItem) {
        pendingRestore = null
        pushCurrentDetails()
        MediaDetailsSeedStore.put(item)
        switchItem(item, false)
    }

    private fun openNestedDetails(nestedItemId: String) {
        pendingRestore = null
        pushCurrentDetails()
        switchItem(nestedItemId)
    }

    private fun pushCurrentDetails() {
        currentItem?.let {
            MediaDetailsSeedStore.put(it)
            detailsStack.addLast(
                DetailsStackEntry(
                    itemId = it.id,
                    scrollY = scroll.scrollY,
                    focusedItemId = activity?.currentFocus?.tag as? String,
                    seasonId = currentSeasonId,
                    tracksReady = tracksReady
                )
            )
        }
    }

    /** Called by the host before it removes the details surface. */
    fun handleBackWithinDetails(): Boolean {
        val previous = detailsStack.pollLast() ?: return false
        pendingRestore = previous
        val seed = MediaDetailsSeedStore.getItem(previous.itemId)
        if (seed != null) {
            switchItem(seed, true)
        } else {
            switchItem(previous.itemId)
        }
        return true
    }

    private fun switchItem(restoredItemId: String) {
        cancelAllApiWork()
        requestGeneration++
        itemId = restoredItemId
        requireArguments().putString(ARG_ITEM_ID, restoredItemId)
        currentItem = null
        nextUpItem = null
        clearSecondaryShelves()
        renderLoadingIdentity()
        scroll.scrollTo(0, 0)
        loadDetails(restoredItemId)
    }

    private fun switchItem(item: MediaItem, fullDetails: Boolean) {
        cancelAllApiWork()
        requestGeneration++
        itemId = item.id
        requireArguments().putString(ARG_ITEM_ID, item.id)
        currentItem = item
        nextUpItem = null
        clearSecondaryShelves()
        markSecondaryUnresolved(item)
        renderPrimary(item, fullDetails)
        schedulePendingDetailsRestore()
        scroll.scrollTo(0, 0)
        loadDetails(item.id)
    }

    private fun reportInteractive() {
        if (interactiveReported) return
        interactiveReported = true
        root.postOnAnimation {
            if (isAdded && !destroyed) {
                (activity as? PtvHostActivity)?.onDetailsInteractive(SystemClock.elapsedRealtime())
                endContentTrace()
            }
        }
    }

    private fun endContentTrace() {
        if (traceStarted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.endAsyncSection("PiggieTV#details:content", traceCookie)
            traceStarted = false
        }
    }

    private fun metadataText(item: MediaItem): String {
        val durationOrCount = if (item.type.equals("Season", ignoreCase = true)) {
            (item.episodeCount ?: item.childCount ?: item.recursiveItemCount)
                ?.takeIf { it >= 0 }
                ?.let { count -> "$count ${if (count == 1) "episode" else "episodes"}" }
        } else {
            item.runtimeTicks.takeIf {
                it > 0L &&
                    !item.type.equals("Series", ignoreCase = true) &&
                    !item.type.equals("Movie", ignoreCase = true)
            }?.let { "${it / 10_000_000L / 60L} min" }
        }
        return TextSanitizer.formatMetadata(
            item.productionYear ?: item.year,
            item.officialRating,
            durationOrCount
        )
    }

    private fun textView(value: String, @DimenRes size: Int, color: Int, bold: Boolean = false) = TextView(requireContext()).apply {
        text = value
        setTextSizeRes(size)
        setTextColor(requireContext().getColor(color))
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun sectionLabel(value: String) = textView(value, R.dimen.tv_text_size_section_title, R.color.tv_text_primary, true).apply {
        setPadding(0, requireContext().dim(R.dimen.tv_spacing_medium), 0, requireContext().dim(R.dimen.tv_spacing_small))
    }

    private fun topMargin(@DimenRes top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = requireContext().dim(top) }

    private class ClearableViewReference<T : Any> : ReadWriteProperty<Any?, T> {
        private var value: T? = null

        override fun getValue(thisRef: Any?, property: KProperty<*>): T =
            checkNotNull(value) { "${property.name} accessed outside the details view lifecycle" }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            this.value = value
        }

        fun clear() {
            value = null
        }
    }

    companion object {
        private const val ARG_ITEM_ID = "item_id"
        private const val STATE_ITEM_ID = "state_item_id"
        private const val STATE_STACK_ITEM_IDS = "state_stack_item_ids"
        private const val DETAILS_BACKDROP_DELAY_MS = 450L
        private const val DETAILS_BACKDROP_WIDTH = 1280
        private const val DETAILS_BACKDROP_HEIGHT = 720
        private const val DETAILS_LOGO_WIDTH = 400
        private const val DETAILS_LOGO_HEIGHT = 240
        private const val AUDIO_DESCRIPTION = "Choose audio track"
        private const val SUBTITLE_DESCRIPTION = "Choose subtitle track"
        private const val SPEED_DESCRIPTION = "Choose connection speed"
        private const val SERIES_DESCRIPTION = "View series details"
        private const val VIEW_SERIES_FOCUS_TAG = "details-action-view-series"
        private const val WORK_DETAILS = "details"
        private const val WORK_PLAYBACK_INFO = "playback_info"
        private const val WORK_SERIES_PLAY = "series_play"
        private const val WORK_NEXT_UP = "next_up"
        private const val WORK_STATUS = "status"
        private const val WORK_SEASONS = "seasons"
        private const val WORK_EPISODES = "episodes"
        private const val WORK_RELATED = "related"
        private val BACKDROP_HANDLER_TOKEN = Any()
        private val TRACE_SEQUENCE = AtomicInteger()
        fun newInstance(itemId: String) = MediaDetailsFragment().apply {
            arguments = Bundle().apply { putString(ARG_ITEM_ID, itemId) }
        }
    }
}
