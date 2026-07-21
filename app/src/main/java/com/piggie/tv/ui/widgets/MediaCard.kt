package com.piggie.tv.ui.widgets

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.playback.PlaybackProgress
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.theme.PTVShapes
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes

class MediaCardHolder(val view: View) : RecyclerView.ViewHolder(view) {
    val image: ImageView = view.findViewById(R.id.card_image)
    val title: TextView = view.findViewById(R.id.card_title)
    val progress: ProgressBar? = view.findViewById(R.id.card_progress)
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
            setBackgroundResource(R.drawable.tv_media_card_background)
            setPadding(context.dim(R.dimen.tv_card_padding), context.dim(R.dimen.tv_card_padding), context.dim(R.dimen.tv_card_padding), context.dim(R.dimen.tv_card_padding))
            
            setOnFocusChangeListener { v, focused ->
                PTVShapes.applyFocusEffect(v, focused)
            }
        }

        val image = ImageView(context).apply {
            id = R.id.card_image
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        card.addView(image, LinearLayout.LayoutParams(size.first, size.second))

        val title = TextView(context).apply {
            id = R.id.card_title
            setTextColor(PTVColors.textPrimary)
            setTextSizeRes(R.dimen.tv_text_size_card_title)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_spacing_small), 0)
        }
        card.addView(title, LinearLayout.LayoutParams(size.first, -2))

        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            id = R.id.card_progress
            max = 1000
            visibility = View.GONE
            setPadding(context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_spacing_small), context.dim(R.dimen.tv_spacing_small), 0)
        }
        card.addView(progress, LinearLayout.LayoutParams(size.first, context.dim(R.dimen.tv_spacing_small)))

        return card
    }

    fun bindView(holder: MediaCardHolder, item: MediaItem, presentation: MediaCardPresentation, session: NativeSession, api: JellyfinNativeApi) {
        holder.title.text = item.title
        
        val progressFraction = PlaybackProgress.fraction(item.playbackPositionTicks, item.runtimeTicks)
        holder.progress?.let {
            it.visibility = if (progressFraction > 0f) View.VISIBLE else View.GONE
            it.progress = (progressFraction * 1000).toInt()
        }
        
        holder.image.load(api.imageUrl(session, item, presentation)) {
            crossfade(true)
            placeholder(ColorDrawable(PTVColors.cardBackground))
        }
    }
}
