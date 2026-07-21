package com.piggie.tv.theme

import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import com.piggie.tv.R
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
        val scale = if (focused) 1.05f else 1.0f
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(150)
            .start()
        
        if (focused) {
            view.elevation = view.context.resources.getDimension(R.dimen.tv_spacing_small)
        } else {
            view.elevation = 0f
        }
    }
}
