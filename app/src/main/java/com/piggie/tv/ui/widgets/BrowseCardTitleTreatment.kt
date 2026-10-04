package com.piggie.tv.ui.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.TextViewCompat
import com.piggie.tv.R
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.theme.PTVGlassTier
import com.piggie.tv.theme.PTVMaterials
import com.piggie.tv.util.dim

/** Browse-only focus behavior; adapter focus listeners remain free to remember route identity. */
internal class BrowseMediaCardView(context: Context) : LinearLayout(context) {
    var titleTreatment: BrowseCardTitleTreatment? = null

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        titleTreatment?.setFocused(gainFocus)
    }

    override fun onDetachedFromWindow() {
        titleTreatment?.clearOverlay()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) titleTreatment?.refresh() else titleTreatment?.clearOverlay()
    }
}

/**
 * Reuses the title + metadata footprint. Focus reveals two title lines over that same region;
 * a rare longer title gets one static, non-focusable panel in the activity's overlay-safe frame.
 */
// These compile-time integer strategy values are supported by TextView/StaticLayout since API 23.
@SuppressLint("InlinedApi")
internal class BrowseCardTitleTreatment(
    private val card: BrowseMediaCardView,
    private val title: TextView,
    private val metadata: LinearLayout
) {
    private var fullTitle: TextView? = null
    private var overlayHost: FrameLayout? = null
    private val updateOverlay = Runnable { revealOverflow() }

    init {
        val font = title.paint.fontMetricsInt
        val oneLineHeight = font.bottom - font.top + title.paddingTop + title.paddingBottom
        val metadataHeight = maxOf(card.context.dim(R.dimen.tv_rating_icon_size_small), font.bottom - font.top)
        val footerHeight = maxOf(oneLineHeight + metadataHeight, oneLineHeight + title.lineHeight)
        val footer = FrameLayout(card.context)
        val width = title.layoutParams.width
        card.removeView(title)
        card.removeView(metadata)
        title.breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
        title.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        footer.addView(metadata, FrameLayout.LayoutParams(width, metadataHeight, Gravity.BOTTOM))
        footer.addView(title, FrameLayout.LayoutParams(width, footerHeight))
        card.addView(footer, 1, LinearLayout.LayoutParams(width, footerHeight))
        card.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }
        setFocused(card.hasFocus())
    }

    fun setFocused(focused: Boolean) {
        title.maxLines = if (focused) 2 else 1
        title.ellipsize = TextUtils.TruncateAt.END
        metadata.visibility = if (focused) View.INVISIBLE else View.VISIBLE
        refresh()
    }

    fun refresh() {
        clearOverlay()
        if (card.hasFocus()) card.post(updateOverlay)
    }

    fun clearOverlay() {
        card.removeCallbacks(updateOverlay)
        fullTitle?.let { overlayHost?.removeView(it) }
        fullTitle = null
        overlayHost = null
    }

    private fun revealOverflow() {
        if (!card.isAttachedToWindow || !card.hasFocus() || title.text.isNullOrBlank()) return
        val textWidth = title.width - title.paddingLeft - title.paddingRight
        if (textWidth <= 0) return
        val completeLayout = StaticLayout.Builder.obtain(title.text, 0, title.text.length, title.paint, textWidth)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
        if (completeLayout.lineCount <= 2) return
        val host = card.rootView.findViewById<FrameLayout>(android.R.id.content) ?: return
        val inset = card.context.dim(R.dimen.tv_spacing_large)
        val width = minOf(dp(360), host.width - inset * 2)
        if (width <= 0) return
        val panel = AppCompatTextView(card.context).apply {
            tag = "ptv_browse_full_title"
            text = title.text
            setTextColor(PTVColors.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 8
            ellipsize = null
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 9, 12, 1, TypedValue.COMPLEX_UNIT_SP)
            breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val padding = card.context.dim(R.dimen.tv_spacing_medium)
            setPadding(padding, padding, padding, padding)
            background = PTVMaterials.drawable(card.context, PTVGlassTier.ELEVATED,
                card.context.dim(R.dimen.ptv_radius_control).toFloat())
        }
        val maxHeight = minOf(dp(180), host.height - inset * 2)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        )
        val cardBounds = Rect(0, 0, card.width, card.height)
        host.offsetDescendantRectToMyCoords(card, cardBounds)
        val left = cardBounds.left.coerceIn(inset, (host.width - width - inset).coerceAtLeast(inset))
        val gap = card.context.dim(R.dimen.tv_spacing_small)
        val below = cardBounds.bottom + gap
        val top = if (below + panel.measuredHeight <= host.height - inset) below
            else (cardBounds.top - panel.measuredHeight - gap).coerceAtLeast(inset)
        fullTitle = panel
        overlayHost = host
        host.addView(panel, FrameLayout.LayoutParams(width, panel.measuredHeight).apply {
            leftMargin = left
            topMargin = top
        })
    }

    private fun dp(value: Int) = (value * card.resources.displayMetrics.density + 0.5f).toInt()
}
