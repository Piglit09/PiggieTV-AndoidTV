package com.piggie.tv.ui.player

import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DimenRes
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import coil.size.Size
import coil.transform.Transformation
import com.piggie.tv.R
import com.piggie.tv.core.PtvHostActivity
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

private object PosterBackdropBlurTransformation : Transformation {
    override val cacheKey: String = "ptv-details-poster-backdrop-blur-v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val sampledWidth = (input.width / 18).coerceAtLeast(1)
        val sampledHeight = (input.height / 18).coerceAtLeast(1)
        val sampled = Bitmap.createScaledBitmap(input, sampledWidth, sampledHeight, true)
        val output = Bitmap.createScaledBitmap(sampled, input.width, input.height, true)
        if (sampled !== input && sampled !== output) sampled.recycle()
        return output
    }
}

class MediaDetailsFragment : Fragment() {
    private val api by lazy { JellyfinNativeApi(requireContext()) }
    private val settings by lazy { NativeSettings(requireContext()) }
    private val handler = Handler(Looper.getMainLooper())
    private val apiWorkers = ConcurrentHashMap<Thread, NativeRequestScope>()
    private val apiWorkerKeys = ConcurrentHashMap<String, Thread>()

    private lateinit var session: NativeSession
    private lateinit var features: RendererFeatures
    private lateinit var itemId: String
    private lateinit var root: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var sideInfo: LinearLayout
    private lateinit var infoLeft: LinearLayout
    private lateinit var brandingRight: FrameLayout
    private lateinit var logo: ImageView
    private lateinit var title: TextView
    private lateinit var metadataContainer: LinearLayout
    private lateinit var overview: TextView
    private lateinit var actionsRow: LinearLayout
    private lateinit var castContainer: LinearLayout
    private lateinit var seasonsContainer: LinearLayout
    private lateinit var episodesContainer: LinearLayout
    private lateinit var relatedContainer: LinearLayout
    private lateinit var backdrop: ImageView

    private var currentItem: MediaItem? = null
    private var nextUpItem: MediaItem? = null
    private var currentSeasonId: String? = null
    private var seasonList: RecyclerView? = null
    private var episodeList: RecyclerView? = null
    private var relatedList: RecyclerView? = null
    private var primaryAction: View? = null
    private var tracksReady = false
    private var destroyed = false
    private var requestGeneration = 0
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

    private data class DetailsStackEntry(
        val item: MediaItem,
        val scrollY: Int,
        val focusedItemId: String?,
        val seasonId: String?,
        val tracksReady: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        itemId = requireArguments().getString(ARG_ITEM_ID).orEmpty()
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

    private fun buildLayout() {
        val context = requireContext()
        root = FrameLayout(context).apply { setBackgroundColor(PTVColors.background) }
        backdrop = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(ColorDrawable(PTVColors.background))
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
        top.addView(sideInfo, LinearLayout.LayoutParams(context.dim(R.dimen.tv_hero_button_width) + 20, -2))

        infoLeft = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        title = textView("Loading details…", R.dimen.tv_text_size_hero_title, R.color.tv_text_primary, true)
        metadataContainer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        overview = textView("", R.dimen.tv_text_size_body, R.color.tv_text_secondary).apply {
            maxLines = 5
            setLineSpacing(0f, 1.2f)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        infoLeft.addView(title)
        infoLeft.addView(metadataContainer, topMargin(R.dimen.tv_spacing_small))
        infoLeft.addView(overview, topMargin(R.dimen.tv_spacing_medium))

        actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        infoLeft.addView(actionsRow, topMargin(R.dimen.tv_spacing_large))
        top.addView(infoLeft, LinearLayout.LayoutParams(0, -2, 1f))

        val brandingRightFrame = FrameLayout(context).apply {
            setPadding(context.dim(R.dimen.tv_spacing_large), 0, 0, 0)
        }
        logo = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        brandingRightFrame.addView(logo, FrameLayout.LayoutParams(
            context.dim(R.dimen.tv_hero_logo_max_width_expanded),
            context.dim(R.dimen.tv_hero_logo_max_height) + 120,
            Gravity.CENTER
        ))
        brandingRight = brandingRightFrame
        top.addView(brandingRightFrame, LinearLayout.LayoutParams(-2, -2))
        
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
        title.text = "Loading details…"
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
                        currentItem = details
                        tracksReady = !isVideoTrackItem(details)
                        renderPrimary(
                            details,
                            fullDetails = true,
                            preserveActionFocus = true
                        )
                        renderSecondary(details, generation)
                        if (isVideoTrackItem(details)) {
                            loadPlaybackMetadata(details, generation)
                        }
                        reportInteractive()
                    }
                }
                .onFailure { error ->
                    activity?.runOnUiThread {
                        if (!isCurrentRequest(generation, requestedItemId)) return@runOnUiThread
                        renderLoadFailure(error)
                    }
                }
        }
    }

    private fun isVideoTrackItem(item: MediaItem): Boolean =
        item.type !in setOf("Series", "Book", "MusicArtist", "MusicAlbum", "Audio")

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

    private fun renderLoadFailure(error: Throwable) {
        if (currentItem != null) {
            Toast.makeText(requireContext(), "Some details could not be refreshed.", Toast.LENGTH_SHORT).show()
            seasonsSettled = true
            episodesSettled = true
            relatedSettled = true
            schedulePendingActionSecondaryFocus()
            return
        }
        title.text = "Details unavailable"
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

        // 1. Side Info
        sideInfo.removeAllViews()
        if (item.director != null) {
            sideInfo.addView(textView("DIRECTED BY", R.dimen.tv_text_size_metadata, R.color.tv_text_muted, true))
            sideInfo.addView(textView(item.director, R.dimen.tv_text_size_metadata, R.color.tv_text_secondary), topMargin(R.dimen.tv_spacing_small))
            sideInfo.addView(View(requireContext()), LinearLayout.LayoutParams(1, requireContext().dim(R.dimen.tv_spacing_medium)))
        }
        if (item.runtimeTicks > 0) {
            sideInfo.addView(textView("RUNS", R.dimen.tv_text_size_metadata, R.color.tv_text_muted, true))
            val mins = item.runtimeTicks / 10_000_000 / 60
            sideInfo.addView(textView("$mins min", R.dimen.tv_text_size_metadata, R.color.tv_text_secondary), topMargin(R.dimen.tv_spacing_small))
            sideInfo.addView(View(requireContext()), LinearLayout.LayoutParams(1, requireContext().dim(R.dimen.tv_spacing_medium)))

            val endsAt = System.currentTimeMillis() + (item.runtimeTicks / 10_000)
            val time = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(endsAt))
            sideInfo.addView(textView("ENDS", R.dimen.tv_text_size_metadata, R.color.tv_text_muted, true))
            sideInfo.addView(textView(time, R.dimen.tv_text_size_metadata, R.color.tv_text_secondary), topMargin(R.dimen.tv_spacing_small))
        }

        // 2. Center Info
        title.text = item.title
        metadataContainer.removeAllViews()
        
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

    private fun renderArtwork(item: MediaItem, fullDetails: Boolean) {
        val logos = DetailsArtworkPolicy.logoCandidates(item)
        if (logos.isNotEmpty()) loadArtwork(logo, logos, item.id)

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
            target.setImageDrawable(ColorDrawable(if (target === backdrop) PTVColors.background else PTVColors.cardBackground))
            return
        }
        val candidate = candidates[index]
        target.load(artworkUrl(candidate)) {
            crossfade(500)
            placeholder(ColorDrawable(if (target === backdrop) PTVColors.background else PTVColors.cardBackground))
            if (target.width > 0 && target.height > 0) size(target.width, target.height)
            allowRgb565(features.rgb565Posters && target !== backdrop)
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

        addAction(
            iconActionButton(R.drawable.ic_action_favorite, item.isFavorite) { updateFavorite(item) },
            square = true
        )
        addAction(
            iconActionButton(R.drawable.ic_action_watched, item.isPlayed) { updatePlayed(item) },
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
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) requestFirstSeasonOrSecondary(action) else false
            }
        }
        if (restoreDescription != null || restoreIndex != null) {
            actionsRow.post {
                val target = restoreDescription?.let { description ->
                    actionsRow.children().firstOrNull { it.contentDescription == description }
                } ?: restoreIndex?.takeIf { it in 0 until actionsRow.childCount }?.let(actionsRow::getChildAt)
                target?.requestFocus()
            }
        } else if (currentItem != null && root.findFocus() == null) {
            play.requestFocus()
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
                if (square) context.dim(R.dimen.tv_hero_button_height) else -2,
                context.dim(R.dimen.tv_hero_button_height)
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
        setTextSizeRes(R.dimen.tv_nav_text_size)
        setTextColor(requireContext().getColor(R.color.tv_text_primary))
        setBackgroundResource(if (secondary) R.drawable.tv_button_secondary else R.drawable.tv_button_primary)
        setPadding(requireContext().dim(R.dimen.tv_spacing_medium), 0, requireContext().dim(R.dimen.tv_spacing_medium), 0)
        setOnClickListener { action(this) }
        setOnFocusChangeListener { v, f -> PTVShapes.applyFocusEffect(v, f) }
    }

    private fun premiumActionButton(label: String, iconRes: Int, action: () -> Unit): Button =
        Button(requireContext()).apply {
            text = label
            isAllCaps = false
            setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
            compoundDrawablePadding = requireContext().dim(R.dimen.tv_spacing_small)
            setTextSizeRes(R.dimen.tv_nav_text_size)
            setTextColor(requireContext().getColor(R.color.tv_text_primary))
            setBackgroundResource(R.drawable.tv_button_primary)
            setPadding(requireContext().dim(R.dimen.tv_spacing_large), 0, requireContext().dim(R.dimen.tv_spacing_large), 0)
            setOnClickListener { action() }
            setOnFocusChangeListener { v, f -> PTVShapes.applyFocusEffect(v, f) }
        }

    private fun iconActionButton(iconRes: Int, active: Boolean, action: () -> Unit): Button =
        Button(requireContext()).apply {
            setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
            setBackgroundResource(if (active) R.drawable.tv_button_primary else R.drawable.tv_button_secondary)
            setOnClickListener { action() }
            setOnFocusChangeListener { v, f -> PTVShapes.applyFocusEffect(v, f) }
        }

    private fun showAudioSelection(item: MediaItem, opener: View) {
        val options = listOf("Default / Auto") + item.audioTracks.map(::audioTrackLabel)
        PtvSelectionDialog(requireContext(), "Audio", options, restoreFocusTo = opener) { index ->
            PendingPlaybackPreferences.setAudio(item.id, if (index == 0) null else item.audioTracks[index - 1].index)
            rerenderActionsPreservingFocus(currentItem ?: item)
        }.show()
    }

    private fun showSubtitleSelection(item: MediaItem, opener: View) {
        val options = listOf("Default / Auto", "Off") + item.subtitleTracks.map(::subtitleTrackLabel)
        PtvSelectionDialog(requireContext(), "Subtitles", options, restoreFocusTo = opener) { index ->
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
        VideoPlayerActivity.start(requireContext(), item.id, item.playbackPositionTicks, PendingPlaybackPreferences.get(item.id))
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
            activity?.runOnUiThread { if (episode != null) startPlayback(episode) }
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
                result.onSuccess {
                    val updated = (currentItem ?: item).let { if (favorite) it.copy(isFavorite = !item.isFavorite) else it.copy(isPlayed = !item.isPlayed) }
                    currentItem = updated
                    rerenderActionsPreservingFocus(updated)
                }
            }
        }
    }

    private fun renderSecondary(item: MediaItem, generation: Int) {
        renderCast(item)
        seasonsSettled = item.type != "Series"
        episodesSettled = item.type != "Series"
        relatedSettled = false
        if (item.type == "Series") loadSeasons(item, generation) else {
            seasonsContainer.visibility = View.GONE
            episodesContainer.visibility = View.GONE
        }
        loadRelated(item, generation)
    }

    private fun markSecondaryUnresolved(item: MediaItem) {
        seasonsSettled = item.type != "Series"
        episodesSettled = item.type != "Series"
        relatedSettled = false
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
                            seasonsContainer.visibility = View.GONE
                        } else {
                            showSeasons(series, seasons, generation)
                        }
                        schedulePendingActionSecondaryFocus()
                    }
                }
        }
    }

    private fun showSeasons(series: MediaItem, seasons: List<MediaItem>, generation: Int) {
        seasonsContainer.removeAllViews()
        seasonsContainer.visibility = View.VISIBLE
        seasonsContainer.addView(sectionLabel("Seasons"))
        val manager = TvLinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        val list = RecyclerView(requireContext()).apply {
            layoutManager = manager
            adapter = SeasonAdapter(series, seasons, generation)
            applyRenderingTuning(manager, 7)
        }
        seasonList = list
        seasonsContainer.addView(list, LinearLayout.LayoutParams(-1, requireContext().dim(R.dimen.tv_poster_height) + requireContext().dim(R.dimen.tv_shelf_extra_height)))
        selectSeason(series, seasons.first(), generation, false)
    }

    private fun selectSeason(series: MediaItem, season: MediaItem, generation: Int, userInitiated: Boolean) {
        currentSeasonId = season.id
        episodesSettled = false
        launchApiWork(WORK_EPISODES, "ptv-episodes-${season.id}") {
            runCatching { api.loadEpisodes(session, series.id, season.id) }
                .onSuccess { episodes ->
                    activity?.runOnUiThread {
                        if (!isCurrentRequest(generation, series.id) || currentSeasonId != season.id) return@runOnUiThread
                        showEpisodes(episodes)
                        episodesSettled = true
                    }
                }
        }
    }

    private fun showEpisodes(episodes: List<MediaItem>) {
        episodesContainer.removeAllViews()
        if (episodes.isEmpty()) { episodesContainer.visibility = View.GONE; return }
        episodesContainer.visibility = View.VISIBLE
        episodesContainer.addView(sectionLabel("Episodes"))
        val manager = TvLinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        val list = RecyclerView(requireContext()).apply {
            layoutManager = manager
            adapter = EpisodeAdapter(episodes)
            applyRenderingTuning(manager, 5)
        }
        episodeList = list
        episodesContainer.addView(list, LinearLayout.LayoutParams(-1, requireContext().dim(R.dimen.tv_landscape_height) + requireContext().dim(R.dimen.tv_shelf_extra_height)))
    }

    private fun loadRelated(item: MediaItem, generation: Int) {
        relatedSettled = false
        launchApiWork(WORK_RELATED, "ptv-related-${item.id}") {
            runCatching { api.loadSimilar(session, item.id, 12) }
                .onSuccess { related ->
                    activity?.runOnUiThread {
                        if (!isCurrentRequest(generation, item.id)) return@runOnUiThread
                        relatedSettled = true
                        if (related.isEmpty()) relatedContainer.visibility = View.GONE else showRelated(related)
                        schedulePendingActionSecondaryFocus()
                    }
                }
        }
    }

    private fun showRelated(items: List<MediaItem>) {
        relatedContainer.removeAllViews()
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
    }

    private fun requestFirstSeasonOrSecondary(previous: View): Boolean {
        val target = firstFocusableChild(seasonList) ?: firstFocusableChild(relatedList)
        return target?.requestFocus() ?: false
    }

    private fun firstFocusableChild(list: RecyclerView?): View? {
        val recycler = list ?: return null
        return recycler.layoutManager?.getChildAt(0)
    }

    private fun schedulePendingActionSecondaryFocus() {
        if (pendingActionSeasonFocus) {
             val target = firstFocusableChild(seasonList) ?: firstFocusableChild(relatedList)
             target?.requestFocus()
             pendingActionSeasonFocus = false
        }
    }

    private inner class SeasonAdapter(
        private val series: MediaItem,
        private val seasons: List<MediaItem>,
        private val generation: Int
    ) : RecyclerView.Adapter<MediaCardHolder>() {
        override fun getItemCount(): Int = seasons.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            MediaCardHolder(MediaCardFactory.createView(parent, MediaCardPresentation.POSTER))
        override fun onBindViewHolder(holder: MediaCardHolder, position: Int) {
            val season = seasons[position]
            MediaCardFactory.bindView(holder, season, MediaCardPresentation.POSTER, session, api)
            holder.itemView.setOnClickListener { selectSeason(series, season, generation, true) }
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
                placeholder(ColorDrawable(PTVColors.cardBackground))
                transformations(coil.transform.CircleCropTransformation())
            }
        }
        inner class Holder(view: View, val avatar: ImageView, val name: TextView, val role: TextView) : RecyclerView.ViewHolder(view)
    }

    private fun openNestedDetails(item: MediaItem) {
        currentItem?.let {
            detailsStack.addLast(
                DetailsStackEntry(
                    item = it,
                    scrollY = scroll.scrollY,
                    focusedItemId = activity?.currentFocus?.tag as? String,
                    seasonId = currentSeasonId,
                    tracksReady = tracksReady
                )
            )
        }
        MediaDetailsSeedStore.put(item)
        switchItem(item, false)
    }

    /** Called by the host before it removes the details surface. */
    fun handleBackWithinDetails(): Boolean {
        val previous = detailsStack.pollLast() ?: return false
        switchItem(previous.item, true)
        scroll.post { scroll.scrollTo(0, previous.scrollY) }
        return true
    }

    private fun switchItem(item: MediaItem, fullDetails: Boolean) {
        cancelAllApiWork()
        requestGeneration++
        itemId = item.id
        currentItem = item
        nextUpItem = null
        markSecondaryUnresolved(item)
        renderPrimary(item, fullDetails)
        scroll.scrollTo(0, 0)
        loadDetails(item.id)
    }

    private fun reportInteractive() {
        if (interactiveReported) return
        interactiveReported = true
        root.postOnAnimation {
            if (isAdded && !destroyed) (activity as? PtvHostActivity)?.onDetailsInteractive(SystemClock.elapsedRealtime())
        }
    }

    private fun metadataText(item: MediaItem): String {
        val runtime = item.runtimeTicks.takeIf { it > 0L && item.type != "Series" }?.let { "${it / 10_000_000L / 60L} min" }
        return TextSanitizer.formatMetadata(item.productionYear ?: item.year, item.officialRating, runtime)
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

    companion object {
        private const val ARG_ITEM_ID = "item_id"
        private const val DETAILS_BACKDROP_DELAY_MS = 450L
        private const val AUDIO_DESCRIPTION = "Choose audio track"
        private const val SUBTITLE_DESCRIPTION = "Choose subtitle track"
        private const val SPEED_DESCRIPTION = "Choose connection speed"
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
