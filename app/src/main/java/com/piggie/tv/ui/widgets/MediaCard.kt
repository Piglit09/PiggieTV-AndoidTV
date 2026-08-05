package com.piggie.tv.ui.widgets

import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.content.res.AppCompatResources
import coil.dispose
import coil.load
import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.diagnostics.PtvCoilEventListenerFactory
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.theme.PTVShapes
import com.piggie.tv.ui.rendering.TvRenderingRuntime
import com.piggie.tv.ui.rendering.TvFocusIndicator
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes

class MediaCardHolder(val view: View) : RecyclerView.ViewHolder(view) {
    val image: ImageView = view.findViewById(R.id.card_image)
    val artwork: View = view.findViewById(R.id.card_artwork)
    val title: TextView = view.findViewById(R.id.card_title)
    val subtitle: TextView = view.findViewById(R.id.card_subtitle)
    val badge: TextView = view.findViewById(R.id.card_badge)
    val ratingStar: ImageView = view.findViewById(R.id.card_rating_star)
    val rating: TextView = view.findViewById(R.id.card_rating_text)
    val progress: ProgressBar? = view.findViewById(R.id.card_progress)
    val placeholder = ColorDrawable(PTVColors.cardBackground)
    val cardSurface: Drawable? = view.background
    val cardForeground: Drawable? = view.foreground
    val artworkSurface: Drawable? = artwork.background
    val artworkHighlight: Drawable? = artwork.foreground
    val progressStyle: Drawable? = progress?.progressDrawable
}

object MediaCardFactory {
    /** Shared geometry-only outline; clipping no longer needs a translucent under-image draw. */
    private val artworkOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            if (view.width <= 0 || view.height <= 0) {
                outline.setEmpty()
                return
            }
            outline.setRoundRect(
                0,
                0,
                view.width,
                view.height,
                view.resources.getDimension(R.dimen.tv_media_artwork_corner_radius)
            )
        }
    }

    fun createView(parent: ViewGroup, presentation: MediaCardPresentation): View {
        val context = parent.context
        val features = TvRenderingRuntime.features()
        val size = when (presentation) {
            MediaCardPresentation.POSTER -> context.dim(R.dimen.tv_poster_width) to context.dim(R.dimen.tv_poster_height)
            MediaCardPresentation.LANDSCAPE -> context.dim(R.dimen.tv_landscape_width) to context.dim(R.dimen.tv_landscape_height)
            MediaCardPresentation.SQUARE -> context.dim(R.dimen.tv_square_width) to context.dim(R.dimen.tv_square_height)
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            PTVShapes.applyMediaCardSurface(this)
            // AFTKM keeps the accepted fixed-width geometry: the safe static ice surface is
            // applied directly to the root, without restoring the old outer card padding.
            val padding = if (features.flatRectangularCards) 0 else context.dim(R.dimen.tv_card_padding)
            setPadding(padding, padding, padding, padding)

            setOnFocusChangeListener { v, focused ->
                PTVShapes.applyFocusEffect(v, focused)
            }
        }

        val image = ImageView(context).apply {
            id = R.id.card_image
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val badge = TextView(context).apply {
            id = R.id.card_badge
            setBackgroundResource(R.drawable.tv_badge_bg)
            setTextColor(android.graphics.Color.WHITE)
            setTextSizeRes(R.dimen.tv_text_size_metadata)
            visibility = View.GONE
            setPadding(8, 2, 8, 2)
        }
        val cardWithBadge = FrameLayout(context).apply {
            id = R.id.card_artwork
            if (features.cardClipping || features.iceCardEnabled || features.imageEffects) {
                background = null
                outlineProvider = artworkOutlineProvider
                clipToOutline = features.cardClipping || features.iceCardEnabled
                foreground = if (features.imageEffects) {
                    AppCompatResources.getDrawable(context, R.drawable.tv_ice_artwork_gloss)
                } else {
                    null
                }
            }
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(badge, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.END).apply {
                topMargin = 8
                marginEnd = 8
            })
        }
        card.addView(cardWithBadge, LinearLayout.LayoutParams(size.first, size.second))
        // The one cached RecyclerView overlay now follows the entire ice-card body, including
        // title, metadata and progress, while retaining scale=1/elevation=0.
        TvFocusIndicator.registerArtwork(card, card)

        val title = TextView(context).apply {
            id = R.id.card_title
            setTextColor(PTVColors.textPrimary)
            setTextSizeRes(R.dimen.tv_text_size_card_title)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_spacing_small), 0)
        }
        card.addView(title, LinearLayout.LayoutParams(size.first, -2))

        val metaLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(context.dim(R.dimen.tv_spacing_small), 0, context.dim(R.dimen.tv_spacing_small), 0)
        }

        val star = ImageView(context).apply {
            id = R.id.card_rating_star
            setImageResource(R.drawable.ic_rating_star)
            visibility = View.GONE
        }
        val ratingText = TextView(context).apply {
            id = R.id.card_rating_text
            setTextColor(PTVColors.textSecondary)
            setTextSizeRes(R.dimen.tv_text_size_metadata)
            visibility = View.GONE
        }
        val subtitle = TextView(context).apply {
            id = R.id.card_subtitle
            setTextColor(PTVColors.textSecondary)
            setTextSizeRes(R.dimen.tv_text_size_metadata)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(8, 0, 0, 0)
        }

        metaLayout.addView(star, LinearLayout.LayoutParams(context.dim(R.dimen.tv_rating_icon_size_small), context.dim(R.dimen.tv_rating_icon_size_small)))
        metaLayout.addView(ratingText, LinearLayout.LayoutParams(-2, -2).apply { marginStart = 4 })
        metaLayout.addView(subtitle, LinearLayout.LayoutParams(0, -2, 1f))

        card.addView(metaLayout)

        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            id = R.id.card_progress
            max = 1000
            visibility = View.INVISIBLE
            if (features.progressGradientEnabled) {
                progressDrawable = AppCompatResources.getDrawable(context, R.drawable.tv_card_progress_gradient)
            }
            setPadding(context.dim(R.dimen.tv_spacing_small), 0, context.dim(R.dimen.tv_spacing_small), 0)
        }
        card.addView(progress, LinearLayout.LayoutParams(size.first, context.dim(R.dimen.tv_spacing_small)))

        return card
    }

    fun bindView(
        holder: MediaCardHolder,
        item: MediaItem,
        presentation: MediaCardPresentation,
        session: NativeSession,
        api: JellyfinNativeApi,
        fallbackArtworkItems: List<MediaItem> = emptyList(),
        onImageReady: (() -> Unit)? = null
    ) {
        resetBoundState(holder)
        // Details navigation stores the focused media id from the focusable card root. Keeping
        // the id only on the child ImageView made every back-stack focus snapshot null.
        holder.view.tag = item.id
        holder.image.tag = item.id
        if (item.type == "ViewMore") {
            bindViewMore(holder, item)
            return
        }

        holder.title.text = item.title

        val subtitle = when (item.type) {
            "Episode" -> item.seriesName ?: ""
            "Series" -> item.officialRating ?: ""
            "Movie" -> item.officialRating ?: item.year ?: ""
            "BoxSet" -> "Collection"
            else -> item.officialRating ?: ""
        }
        holder.subtitle.text = subtitle

        if (item.type == "BoxSet") {
            holder.badge.text = "COLLECTION"
            holder.badge.visibility = View.VISIBLE
        } else if (item.playbackPositionTicks > 0 && item.runtimeTicks > 0) {
            holder.badge.text = "RESUME"
            holder.badge.visibility = View.VISIBLE
        } else if (item.isPlayed) {
            holder.badge.text = "WATCHED"
            holder.badge.visibility = View.VISIBLE
        }

        val rating = item.communityRating
        if (rating != null && rating > 0) {
            holder.rating.text = String.format(java.util.Locale.getDefault(), "%.1f", rating)
            holder.ratingStar.visibility = View.VISIBLE
            holder.rating.visibility = View.VISIBLE
        }

        val regularSize = when (presentation) {
            MediaCardPresentation.POSTER -> holder.itemView.context.dim(R.dimen.tv_poster_width) to holder.itemView.context.dim(R.dimen.tv_poster_height)
            MediaCardPresentation.LANDSCAPE -> holder.itemView.context.dim(R.dimen.tv_landscape_width) to holder.itemView.context.dim(R.dimen.tv_landscape_height)
            MediaCardPresentation.SQUARE -> holder.itemView.context.dim(R.dimen.tv_square_width) to holder.itemView.context.dim(R.dimen.tv_square_height)
        }

        val targetWidth = regularSize.first.coerceAtLeast(1)
        val targetHeight = regularSize.second.coerceAtLeast(1)

        val progressFraction = com.piggie.tv.data.playback.PlaybackProgress.fraction(item.playbackPositionTicks, item.runtimeTicks)
        holder.progress?.let {
            if (progressFraction > 0f) {
                it.progress = (progressFraction * 1000).toInt()
                it.visibility = View.VISIBLE
            }
        }

        val description = listOf(item.title, subtitle).filter(String::isNotBlank).joinToString(", ")
        holder.view.contentDescription = description
        holder.image.contentDescription = contextArtworkDescription(item.title)

        if (item.imageTag.isNullOrBlank()) {
            return
        }

        holder.image.load(api.imageUrl(session, item, presentation)) {
            crossfade(false)
            placeholder(holder.placeholder)
            error(holder.placeholder)
            size(targetWidth, targetHeight)
            setParameter(
                PtvCoilEventListenerFactory.CATEGORY_PARAMETER,
                PtvCoilEventListenerFactory.CATEGORY_CARD,
                null
            )
            allowRgb565(TvRenderingRuntime.features().rgb565Posters)
            if (onImageReady != null) {
                listener(onSuccess = { _, _ -> onImageReady.invoke() })
            }
        }
    }

    private fun bindViewMore(holder: MediaCardHolder, item: MediaItem) {
        holder.title.text = "View More"
        holder.subtitle.text = item.title
        holder.subtitle.visibility = View.VISIBLE
        holder.image.scaleType = ImageView.ScaleType.CENTER_INSIDE
        holder.image.setImageResource(R.drawable.ic_view_more_arrow)
        holder.image.setBackgroundColor(0x33FFFFFF)
        holder.image.contentDescription = "View more"
        holder.view.contentDescription = listOf("View More", item.title)
            .filter(String::isNotBlank)
            .joinToString(", ")
    }

    /**
     * Cancels any prior Coil target and restores every mutable card property before each bind.
     * The holder retains its static surface/progress drawable instances, so recycling performs no
     * shader or drawable inflation on the hot focus/bind path.
     */
    private fun resetBoundState(holder: MediaCardHolder) {
        holder.image.dispose()

        holder.view.clearAnimation()
        holder.view.background = holder.cardSurface
        holder.view.foreground = holder.cardForeground
        holder.view.alpha = 1f
        holder.view.scaleX = 1f
        holder.view.scaleY = 1f
        holder.view.elevation = 0f
        holder.view.translationX = 0f
        holder.view.translationY = 0f
        holder.view.translationZ = 0f
        holder.view.isActivated = false
        holder.view.isSelected = false
        holder.view.isPressed = false

        holder.artwork.background = holder.artworkSurface
        holder.artwork.foreground = holder.artworkHighlight
        holder.artwork.alpha = 1f
        holder.artwork.isActivated = false
        holder.artwork.isSelected = false

        holder.title.text = ""
        holder.title.visibility = View.VISIBLE
        holder.title.alpha = 1f
        holder.subtitle.text = ""
        holder.subtitle.visibility = View.VISIBLE
        holder.subtitle.alpha = 1f

        holder.badge.text = ""
        holder.badge.visibility = View.GONE
        holder.badge.alpha = 1f
        holder.rating.text = ""
        holder.rating.visibility = View.GONE
        holder.rating.alpha = 1f
        holder.ratingStar.visibility = View.GONE
        holder.ratingStar.alpha = 1f

        holder.progress?.apply {
            visibility = View.INVISIBLE
            progress = 0
            secondaryProgress = 0
            isIndeterminate = false
            progressDrawable = holder.progressStyle
            alpha = 1f
            clearAnimation()
        }

        holder.image.tag = null
        holder.view.tag = null
        holder.image.scaleType = ImageView.ScaleType.CENTER_CROP
        holder.image.background = null
        holder.image.alpha = 1f
        holder.image.visibility = View.VISIBLE
        holder.image.contentDescription = null
        holder.image.setImageDrawable(holder.placeholder)
        holder.view.contentDescription = null

        // Keep the overlay state synchronized with actual focus when RecyclerView rebinds the
        // currently focused holder; no scale/elevation/alpha mutation is introduced.
        PTVShapes.applyFocusEffect(holder.view, holder.view.hasFocus())
    }

    fun recycleView(holder: MediaCardHolder) {
        PTVShapes.applyFocusEffect(holder.view, false)
        holder.view.clearFocus()
        resetBoundState(holder)
    }

    private fun contextArtworkDescription(title: String): String = "$title artwork"
}
