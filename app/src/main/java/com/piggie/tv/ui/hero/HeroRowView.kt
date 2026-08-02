package com.piggie.tv.ui.hero

import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.util.Log
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.dispose
import coil.imageLoader
import coil.load
import coil.memory.MemoryCache
import coil.request.Disposable
import coil.request.ImageRequest
import com.piggie.tv.R
import com.piggie.tv.BuildConfig
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.diagnostics.PtvCoilEventListenerFactory
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import com.piggie.tv.diagnostics.PtvRedactor
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.ui.rendering.TvRenderingRuntime
import com.piggie.tv.ui.rendering.TvRenderingProfile
import com.piggie.tv.ui.layout.TvLayoutProfileResolver
import com.piggie.tv.ui.shared.TextSanitizer
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes

class HeroRowView(
    context: android.content.Context,
    private val route: HeroRoute,
    private val session: NativeSession,
    private val api: JellyfinNativeApi,
    private val reduceMotion: () -> Boolean,
    private val onPrimary: (MediaItem) -> Unit,
    private val onDetails: (MediaItem) -> Unit,
    private val onControlsFocusChanged: (Boolean) -> Unit,
    private val onImageResult: (String, Long, String?) -> Unit
) : FrameLayout(context) {
    private val backdrop = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(PTVColors.background)
    }
    private val overlay = View(context).apply {
        setBackgroundResource(R.drawable.hero_gradient_overlay)
        // The hero owns exactly one scrim. Keeping it attached on the Fire profile makes
        // text readable without restoring the old full-route translucent backdrop stack.
        visibility = View.VISIBLE
    }
    private val logo = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_START
        adjustViewBounds = true
    }
    private val title = TextView(context).apply {
        setTextSizeRes(R.dimen.tv_text_size_hero_title)
        setTextColor(PTVColors.textPrimary)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val metadata = TextView(context).apply {
        setTextSizeRes(R.dimen.tv_text_size_hero_meta)
        setTextColor(PTVColors.textSecondary)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val overview = TextView(context).apply {
        setTextSizeRes(R.dimen.tv_text_size_hero_body)
        setTextColor(PTVColors.textSecondary)
        maxLines = resources.getInteger(R.integer.tv_hero_overview_max_lines)
        ellipsize = TextUtils.TruncateAt.END
    }
    private val primary = heroButton(primary = true)
    private val details = heroButton(primary = false).apply { text = "Details" }
    private lateinit var content: LinearLayout
    private lateinit var actions: LinearLayout
    private var current: HeroCandidate? = null
    private var lastState: HeroState? = null
    private var preloadDisposable: Disposable? = null
    private var renderedBackdropKey: String? = null
    private var activeBackdropCacheKeys = emptyList<String>()
    private var backdropRequestGeneration = 0L
    private var renderedLogoKey: String? = null
    private var activeLogoCacheKey: String? = null
    private var preloadedBackdropKey: String? = null
    private var lastMeasurementSignature: String? = null

    init {
        id = R.id.hero_row
        minimumHeight = context.dim(R.dimen.tv_hero_height)
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(
                context.dim(R.dimen.tv_screen_margin_horizontal),
                context.dim(R.dimen.tv_spacing_large),
                context.dim(R.dimen.tv_screen_margin_horizontal),
                0
            )
        }
        content.addView(
            logo,
            LinearLayout.LayoutParams(
                context.dim(R.dimen.tv_hero_logo_max_width),
                context.dim(R.dimen.tv_hero_logo_max_height)
            )
        )
        content.addView(title)
        content.addView(
            metadata,
            LinearLayout.LayoutParams(
                context.dim(R.dimen.tv_hero_content_width),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dim(R.dimen.tv_spacing_small) }
        )
        content.addView(
            overview,
            LinearLayout.LayoutParams(
                context.dim(R.dimen.tv_hero_content_width),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dim(R.dimen.tv_spacing_medium) }
        )
        actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, context.dim(R.dimen.tv_spacing_medium), 0, 0)
            addView(
                primary,
                LinearLayout.LayoutParams(
                    context.dim(R.dimen.tv_hero_button_width),
                    context.dim(R.dimen.tv_hero_button_height)
                )
            )
            addView(
                details,
                LinearLayout.LayoutParams(
                    context.dim(R.dimen.tv_hero_button_width),
                    context.dim(R.dimen.tv_hero_button_height)
                ).apply { marginStart = context.dim(R.dimen.tv_spacing_medium) }
            )
        }
        content.addView(actions)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        primary.setOnClickListener { current?.item?.let(onPrimary) }
        details.setOnClickListener { current?.item?.let(onDetails) }
        val focusListener = OnFocusChangeListener { _, _ ->
            onControlsFocusChanged(primary.hasFocus() || details.hasFocus())
        }
        primary.onFocusChangeListener = focusListener
        details.onFocusChangeListener = focusListener
        render(null)
    }

    /** Debug-only physical-layout evidence; contains geometry only, never media identifiers. */
    fun traceMeasurement(row: View = this) {
        if (!BuildConfig.DEBUG) return
        val density = resources.displayMetrics.density
        fun bounds(view: View): String {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return "${location[0]},${location[1]},${location[0] + view.width},${location[1] + view.height}"
        }
        val params = row.layoutParams as? ViewGroup.MarginLayoutParams
        val expectedPx = resources.getDimensionPixelSize(R.dimen.tv_hero_height)
        val (profile, viewport) = TvLayoutProfileResolver.from(context)
        val rowBounds = bounds(row)
        val heroBounds = bounds(this)
        val imageBounds = bounds(backdrop)
        val contentBounds = bounds(content)
        val primaryBounds = bounds(primary)
        val detailsBounds = bounds(details)
        val signature = listOf(
            rowBounds,
            heroBounds,
            imageBounds,
            contentBounds,
            primaryBounds,
            detailsBounds,
            logo.visibility,
            title.visibility,
            overview.lineCount
        ).joinToString(":")
        if (signature == lastMeasurementSignature) return
        lastMeasurementSignature = signature
        Log.i(
            "HeroMeasurement",
            "expectedPx=$expectedPx expectedDp=${expectedPx / density} measuredPx=${row.height} measuredDp=${row.height / density} " +
                "rowBounds=$rowBounds heroBounds=$heroBounds imageBounds=$imageBounds contentBounds=$contentBounds " +
                "primaryBounds=$primaryBounds detailsBounds=$detailsBounds " +
                "marginsPx=${params?.leftMargin ?: 0},${params?.topMargin ?: 0}," +
                "${params?.rightMargin ?: 0},${params?.bottomMargin ?: 0} " +
                "paddingPx=${row.paddingLeft},${row.paddingTop},${row.paddingRight},${row.paddingBottom} " +
                "resource=${resources.getResourceName(R.dimen.tv_hero_height)} density=$density " +
                "sw=${viewport.smallestWidthDp} profile=$profile"
        )
    }

    fun render(state: HeroState?) {
        lastState = state
        val candidate = state?.current
        current = candidate
        if (candidate == null) {
            releaseArtwork()
            if (canLoadArtwork()) applyTerminalBackdrop()
            logo.visibility = View.GONE
            title.visibility = View.VISIBLE
            title.text = "PiggieTV"
            metadata.text = "Featured content is still loading"
            overview.text = ""
            primary.visibility = View.INVISIBLE
            details.visibility = View.INVISIBLE
            return
        }

        primary.visibility = View.VISIBLE
        details.visibility = View.VISIBLE
        primary.text = if (candidate.item.playbackPositionTicks > 0L) "Resume" else "Play"
        val titleFallback = if (candidate.item.type == "Episode") {
            candidate.item.seriesName ?: candidate.item.title
        } else {
            candidate.item.title
        }
        metadata.text = metadata(candidate.item)
        overview.text = TextSanitizer.sanitize(candidate.item.overview)
        if (!canLoadArtwork()) {
            releaseArtwork()
            logo.visibility = View.GONE
            title.visibility = View.VISIBLE
            title.text = titleFallback
            return
        }
        val artwork = candidate.artworkItem
        if (!artwork.logoTag.isNullOrBlank()) {
            title.visibility = View.GONE
            logo.visibility = View.VISIBLE
            val logoKey = "${artwork.id}:${artwork.logoTag}"
            if (renderedLogoKey != logoKey) {
                evictActiveLogoFromMemoryCache()
                renderedLogoKey = logoKey
                activeLogoCacheKey = "ptv-hero-logo:$logoKey"
                logo.load(api.logoUrl(session, artwork)) {
                    crossfade(false)
                    memoryCacheKey(requireNotNull(activeLogoCacheKey))
                    setParameter(
                        PtvCoilEventListenerFactory.CATEGORY_PARAMETER,
                        PtvCoilEventListenerFactory.CATEGORY_HERO,
                        null
                    )
                    size(
                        context.dim(R.dimen.tv_hero_logo_max_width),
                        context.dim(R.dimen.tv_hero_logo_max_height)
                    )
                }
            }
        } else {
            logo.dispose()
            evictActiveLogoFromMemoryCache()
            renderedLogoKey = null
            logo.visibility = View.GONE
            title.visibility = View.VISIBLE
            title.text = titleFallback
        }

        loadBackdrop(candidate)
        preload(state.next)
    }

    override fun onDetachedFromWindow() {
        releaseArtwork()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        render(lastState)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) render(lastState) else releaseArtwork()
    }

    private fun loadBackdrop(candidate: HeroCandidate) {
        val requests = artworkRequests(candidate)
        val backdropKey = HeroArtworkLoadGuard.chainKey(
            candidate.item.id,
            requests.map(HeroArtworkRequest::cacheKey)
        )
        if (renderedBackdropKey == backdropKey) return
        evictActiveBackdropFromMemoryCache()
        backdropRequestGeneration += 1L
        renderedBackdropKey = backdropKey
        activeBackdropCacheKeys = requests.map(HeroArtworkRequest::cacheKey)
        val generation = backdropRequestGeneration
        // Invalidate the ImageView target before beginning a richer replacement chain for the
        // same playable episode. Callback ownership below protects against late cancellation.
        backdrop.dispose()
        val startedAt = SystemClock.elapsedRealtime()
        loadBackdropAttempt(candidate, requests, 0, startedAt, backdropKey, generation)
    }

    private fun loadBackdropAttempt(
        candidate: HeroCandidate,
        requests: List<HeroArtworkRequest>,
        index: Int,
        startedAt: Long,
        backdropKey: String,
        generation: Long
    ) {
        if (!ownsBackdropCallback(candidate, backdropKey, generation)) return
        val request = requests.getOrNull(index)
        if (request == null) {
            backdrop.dispose()
            applyTerminalBackdrop()
            if (route == HeroRoute.MUSIC) {
                PtvDiagnosticsManager.event(
                    "music",
                    "artwork_terminal_fallback",
                    mapOf(
                        "itemId" to
                            (PtvRedactor.identifier(candidate.item.id) ?: "none"),
                        "fallbackMode" to "branded_music_background",
                        "attemptCount" to requests.size.toString()
                    )
                )
            }
            onImageResult(
                candidate.item.id,
                SystemClock.elapsedRealtime() - startedAt,
                "no usable artwork"
            )
            return
        }

        backdrop.load(request.url) {
            crossfade(TvRenderingRuntime.features().transitions && !reduceMotion())
            placeholder(backdrop.drawable ?: ColorDrawable(PTVColors.background))
            size(HERO_IMAGE_WIDTH, HERO_IMAGE_HEIGHT)
            memoryCacheKey(request.cacheKey)
            setParameter(
                PtvCoilEventListenerFactory.CATEGORY_PARAMETER,
                PtvCoilEventListenerFactory.CATEGORY_HERO,
                null
            )
            listener(
                onSuccess = { _, _ ->
                    if (ownsBackdropCallback(candidate, backdropKey, generation)) {
                        if (index > 0) {
                            PtvDiagnosticsManager.event(
                                "hero",
                                "artwork_fallback",
                                mapOf(
                                    "itemId" to
                                        (PtvRedactor.identifier(candidate.item.id) ?: "none"),
                                    "fallbackIndex" to index.toString(),
                                    "imageType" to request.imageType
                                )
                            )
                        }
                        onImageResult(
                            candidate.item.id,
                            SystemClock.elapsedRealtime() - startedAt,
                            null
                        )
                    }
                },
                onError = { _, _ ->
                    if (ownsBackdropCallback(candidate, backdropKey, generation)) {
                        loadBackdropAttempt(
                            candidate,
                            requests,
                            index + 1,
                            startedAt,
                            backdropKey,
                            generation
                        )
                    }
                }
            )
        }
    }

    private fun ownsBackdropCallback(
        candidate: HeroCandidate,
        backdropKey: String,
        generation: Long
    ): Boolean = HeroArtworkLoadGuard.ownsCallback(
        expectedGeneration = generation,
        activeGeneration = backdropRequestGeneration,
        expectedChainKey = backdropKey,
        activeChainKey = renderedBackdropKey,
        expectedCandidateItemId = candidate.item.id,
        currentCandidateItemId = current?.item?.id
    )

    private fun invalidateBackdropRequest() {
        backdropRequestGeneration += 1L
        renderedBackdropKey = null
        backdrop.dispose()
    }

    private fun releaseArtwork() {
        invalidateBackdropRequest()
        backdrop.setImageDrawable(null)
        logo.dispose()
        logo.setImageDrawable(null)
        preloadDisposable?.dispose()
        preloadDisposable = null
        renderedLogoKey = null
        preloadedBackdropKey = null
        evictActiveBackdropFromMemoryCache()
        evictActiveLogoFromMemoryCache()
    }

    /** Drops optional artwork immediately when the host receives a critical memory signal. */
    fun releaseForMemoryPressure() {
        lastState = null
        current = null
        releaseArtwork()
    }

    private fun evictActiveBackdropFromMemoryCache() {
        if (TvRenderingRuntime.profile() == TvRenderingProfile.FIRE_TV_PERFORMANCE) {
            val cache = context.imageLoader.memoryCache
            activeBackdropCacheKeys.forEach { cache?.remove(MemoryCache.Key(it)) }
        }
        activeBackdropCacheKeys = emptyList()
    }

    private fun evictActiveLogoFromMemoryCache() {
        if (TvRenderingRuntime.profile() == TvRenderingProfile.FIRE_TV_PERFORMANCE) {
            activeLogoCacheKey?.let { context.imageLoader.memoryCache?.remove(MemoryCache.Key(it)) }
        }
        activeLogoCacheKey = null
    }

    private fun canLoadArtwork(): Boolean =
        isAttachedToWindow && isShown && windowVisibility == View.VISIBLE

    private fun preload(candidate: HeroCandidate?) {
        // The AFTKM profile has enough memory for the displayed hero, not a second 1280x720
        // decoded bitmap that the user may never see.
        if (TvRenderingRuntime.profile() == TvRenderingProfile.FIRE_TV_PERFORMANCE) {
            preloadDisposable?.dispose()
            preloadDisposable = null
            preloadedBackdropKey = null
            return
        }
        val request = candidate?.let(::artworkRequests)?.firstOrNull()
        val backdropKey = request?.cacheKey
        if (preloadedBackdropKey == backdropKey) return
        preloadDisposable?.dispose()
        preloadDisposable = null
        preloadedBackdropKey = backdropKey
        request ?: return
        preloadDisposable = context.imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(request.url)
                .size(HERO_IMAGE_WIDTH, HERO_IMAGE_HEIGHT)
                .memoryCacheKey(request.cacheKey)
                .setParameter(
                    PtvCoilEventListenerFactory.CATEGORY_PARAMETER,
                    PtvCoilEventListenerFactory.CATEGORY_HERO,
                    null
                )
                .build()
        )
    }

    private fun artworkRequests(candidate: HeroCandidate): List<HeroArtworkRequest> =
        HeroArtworkPolicy.choices(route, candidate).map { choice ->
            val url = when (choice.kind) {
                HeroArtworkKind.BACKDROP -> api.backdropUrl(
                    session,
                    choice.item.id,
                    choice.tag,
                    HERO_IMAGE_WIDTH
                )
                HeroArtworkKind.PRIMARY -> api.primaryImageUrl(
                    session,
                    choice.item.id,
                    choice.tag,
                    HERO_IMAGE_WIDTH
                )
            }
            HeroArtworkRequest(
                url = url,
                cacheKey = choice.cacheKey,
                imageType = choice.kind.wireName
            )
        }

    private fun applyTerminalBackdrop() {
        if (route == HeroRoute.MUSIC) {
            backdrop.setImageResource(R.drawable.music_hero_branded_background)
        } else {
            backdrop.setImageDrawable(ColorDrawable(PTVColors.background))
        }
    }

    private fun metadata(item: MediaItem): String = buildList {
        if (item.type == "Episode") item.episodeLabel?.let(::add)
        item.year?.let(::add)
        item.officialRating?.takeIf(String::isNotBlank)?.let(::add)
        item.genres.firstOrNull()?.let(::add)
    }.joinToString("  •  ")

    private fun heroButton(primary: Boolean) = Button(context).apply {
        isAllCaps = false
        setTextSizeRes(R.dimen.tv_hero_button_text_size)
        setTextColor(PTVColors.textPrimary)
        setBackgroundResource(
            if (primary) R.drawable.tv_button_primary else R.drawable.tv_button_secondary
        )
        setPadding(
            context.dim(R.dimen.tv_spacing_large),
            0,
            context.dim(R.dimen.tv_spacing_large),
            0
        )
    }

    private companion object {
        const val HERO_IMAGE_WIDTH = 1280
        const val HERO_IMAGE_HEIGHT = 720
    }

    private data class HeroArtworkRequest(
        val url: String,
        val cacheKey: String,
        val imageType: String
    )
}
