package com.piggie.tv.ui.hero

import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.dispose
import coil.imageLoader
import coil.load
import coil.request.Disposable
import coil.request.ImageRequest
import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import com.piggie.tv.diagnostics.PtvRedactor
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.ui.rendering.TvRenderingRuntime
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
    private var current: HeroCandidate? = null
    private var lastState: HeroState? = null
    private var preloadDisposable: Disposable? = null
    private var renderedBackdropKey: String? = null
    private var backdropRequestGeneration = 0L
    private var renderedLogoKey: String? = null
    private var preloadedBackdropKey: String? = null

    init {
        id = R.id.hero_row
        minimumHeight = context.dim(R.dimen.tv_hero_height)
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val content = LinearLayout(context).apply {
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
        val actions = LinearLayout(context).apply {
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

    fun render(state: HeroState?) {
        lastState = state
        val candidate = state?.current
        current = candidate
        if (candidate == null) {
            invalidateBackdropRequest()
            applyTerminalBackdrop()
            logo.dispose()
            logo.visibility = View.GONE
            title.visibility = View.VISIBLE
            title.text = ""
            metadata.text = ""
            overview.text = ""
            primary.visibility = View.INVISIBLE
            details.visibility = View.INVISIBLE
            preloadDisposable?.dispose()
            preloadDisposable = null
            renderedLogoKey = null
            preloadedBackdropKey = null
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
        val artwork = candidate.artworkItem
        if (!artwork.logoTag.isNullOrBlank()) {
            title.visibility = View.GONE
            logo.visibility = View.VISIBLE
            val logoKey = "${artwork.id}:${artwork.logoTag}"
            if (renderedLogoKey != logoKey) {
                renderedLogoKey = logoKey
                logo.load(api.logoUrl(session, artwork)) {
                    crossfade(false)
                    size(
                        context.dim(R.dimen.tv_hero_logo_max_width),
                        context.dim(R.dimen.tv_hero_logo_max_height)
                    )
                }
            }
        } else {
            logo.dispose()
            renderedLogoKey = null
            logo.visibility = View.GONE
            title.visibility = View.VISIBLE
            title.text = titleFallback
        }

        metadata.text = metadata(candidate.item)
        overview.text = TextSanitizer.sanitize(candidate.item.overview)
        loadBackdrop(candidate)
        preload(state.next)
    }

    override fun onDetachedFromWindow() {
        invalidateBackdropRequest()
        logo.dispose()
        preloadDisposable?.dispose()
        preloadDisposable = null
        renderedLogoKey = null
        preloadedBackdropKey = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        render(lastState)
    }

    private fun loadBackdrop(candidate: HeroCandidate) {
        val requests = artworkRequests(candidate)
        val backdropKey = HeroArtworkLoadGuard.chainKey(
            candidate.item.id,
            requests.map(HeroArtworkRequest::cacheKey)
        )
        if (renderedBackdropKey == backdropKey) return
        backdropRequestGeneration += 1L
        renderedBackdropKey = backdropKey
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

    private fun preload(candidate: HeroCandidate?) {
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
