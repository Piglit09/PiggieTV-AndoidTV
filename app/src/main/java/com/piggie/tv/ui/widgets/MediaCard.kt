package com.piggie.tv.ui.widgets

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import com.piggie.tv.diagnostics.PtvImageTrace
import com.piggie.tv.theme.PTVShapes
import com.piggie.tv.ui.rendering.TvRenderingRuntime
import com.piggie.tv.ui.rendering.TvFocusIndicator
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes

class MediaCardHolder(val view: View) : RecyclerView.ViewHolder(view) {
    val image: ImageView = view.findViewById(R.id.card_image)
    val artwork: View = view.findViewById(R.id.card_artwork)
    val title: TextView = view.findViewById(R.id.card_title)
    val progress: ProgressBar? = view.findViewById(R.id.card_progress)
    val placeholder = ColorDrawable(PTVColors.cardBackground)
}

object MediaCardFactory {
    fun createView(parent: ViewGroup, presentation: MediaCardPresentation): View {
        val context = parent.context
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
            val padding = if (TvRenderingRuntime.features().flatRectangularCards) 0 else context.dim(R.dimen.tv_card_padding)
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
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(badge, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.END).apply {
                topMargin = 8
                marginEnd = 8
            })
        }
        card.addView(cardWithBadge, LinearLayout.LayoutParams(size.first, size.second))
        TvFocusIndicator.registerArtwork(card, cardWithBadge)

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
            visibility = View.GONE
            setPadding(context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_spacing_small), 0)
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
        fallbackArtworkItems: List<MediaItem> = emptyList()
    ) {
        holder.image.tag = item.id
        if (item.type == "ViewMore") {
            bindViewMore(holder, item)
            return
        }

        if (holder.title.text.toString() != item.title) holder.title.text = item.title

        val subtitleView = holder.view.findViewById<TextView>(R.id.card_subtitle)
        val subtitle = when (item.type) {
            "Episode" -> item.seriesName ?: ""
            "Series" -> item.officialRating ?: ""
            "Movie" -> item.officialRating ?: item.year ?: ""
            "BoxSet" -> "Collection"
            else -> item.officialRating ?: ""
        }
        subtitleView?.text = subtitle

        holder.view.findViewById<TextView>(R.id.card_badge)?.let { badge ->
            if (item.type == "BoxSet") {
                badge.text = "COLLECTION"
                badge.visibility = View.VISIBLE
            } else if (item.playbackPositionTicks > 0 && item.runtimeTicks > 0) {
                 badge.text = "RESUME"
                 badge.visibility = View.VISIBLE
            } else {
                badge.visibility = View.GONE
            }
        }

        holder.view.findViewById<TextView>(R.id.card_rating_text)?.let { ratingText ->
            val rating = item.communityRating
            if (rating != null && rating > 0) {
                ratingText.text = String.format(java.util.Locale.getDefault(), "%.1f", rating)
                holder.view.findViewById<View>(R.id.card_rating_star)?.visibility = View.VISIBLE
                ratingText.visibility = View.VISIBLE
            } else {
                ratingText.visibility = View.GONE
                holder.view.findViewById<View>(R.id.card_rating_star)?.visibility = View.GONE
            }
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
            val targetVisibility = if (progressFraction > 0f) View.VISIBLE else View.GONE
            val targetProgress = (progressFraction * 1000).toInt()
            if (it.visibility != targetVisibility) it.visibility = targetVisibility
            if (it.progress != targetProgress) it.progress = targetProgress
        }

        if (item.imageTag.isNullOrBlank()) {
            holder.image.setImageDrawable(holder.placeholder)
            return
        }

        val diagnostics = PtvDiagnosticsManager.isEnabled()
        val startedAt = if (diagnostics) SystemClock.elapsedRealtime() else 0L
        
        holder.image.load(api.imageUrl(session, item, presentation)) {
            crossfade(false)
            placeholder(holder.placeholder)
            error(holder.placeholder)
            size(targetWidth, targetHeight)
            allowRgb565(TvRenderingRuntime.features().rgb565Posters)
        }
    }

    private fun bindViewMore(holder: MediaCardHolder, item: MediaItem) {
        holder.title.text = "View More"
        holder.view.findViewById<TextView>(R.id.card_subtitle)?.apply {
            text = item.title
            visibility = View.VISIBLE
        }
        holder.image.scaleType = ImageView.ScaleType.CENTER_INSIDE
        holder.image.setImageResource(R.drawable.ic_view_more_arrow)
        holder.image.setBackgroundColor(0x33FFFFFF)
        holder.progress?.visibility = View.GONE
    }
}
