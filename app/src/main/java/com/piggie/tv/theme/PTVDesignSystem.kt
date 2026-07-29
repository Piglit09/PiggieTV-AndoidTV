package com.piggie.tv.theme

import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import com.piggie.tv.R
import com.piggie.tv.ui.rendering.TvFocusIndicator
import com.piggie.tv.ui.rendering.TvRenderingRuntime
import com.piggie.tv.util.dim
import com.piggie.tv.util.dimFloat
import com.piggie.tv.util.setTextSizeRes

object PTVColors {
    val background = Color.parseColor("#0F041C")
    val backgroundStart = Color.parseColor("#1A0A2E")
    val backgroundEnd = Color.parseColor("#0F041C")
    val primary = Color.parseColor("#8E44AD")
    val primaryVariant = Color.parseColor("#9B59B6")
    val accent = Color.parseColor("#00F2FF") // Cyan focus
    val textPrimary = Color.parseColor("#FFFFFF")
    val textSecondary = Color.parseColor("#BBBBBB")
    val textMuted = Color.parseColor("#777777")
    val cardBackground = Color.parseColor("#221136")
    val buttonSecondary = Color.parseColor("#331A4D")
}

object PTVTypography {
    fun pageTitle(view: TextView) {
        view.setTextSizeRes(R.dimen.tv_text_size_page_title)
        view.setTextColor(PTVColors.textPrimary)
        view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun sectionTitle(view: TextView) {
        view.setTextSizeRes(R.dimen.tv_text_size_section_title)
        view.setTextColor(PTVColors.textPrimary)
        view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun body(view: TextView) {
        view.setTextSizeRes(R.dimen.tv_text_size_body)
        view.setTextColor(PTVColors.textSecondary)
        view.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    fun metadata(view: TextView) {
        view.setTextSizeRes(R.dimen.tv_text_size_metadata)
        view.setTextColor(PTVColors.textSecondary)
        view.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
}

object PTVShapes {
    fun applyFocusEffect(view: View, focused: Boolean) {
        view.animate().cancel()
        if (view.scaleX != 1f) view.scaleX = 1f
        if (view.scaleY != 1f) view.scaleY = 1f
        if (view.elevation != 0f) view.elevation = 0f
        TvFocusIndicator.onFocusChanged(view, focused)
    }

    fun applyMediaCardSurface(view: View) {
        val features = TvRenderingRuntime.features()
        if (features.flatRectangularCards) {
            view.background = null
        } else {
            view.setBackgroundResource(R.drawable.tv_media_card_surface)
        }
        // Focus is one RecyclerView overlay border registered against artwork bounds.
        view.foreground = null
    }
}
