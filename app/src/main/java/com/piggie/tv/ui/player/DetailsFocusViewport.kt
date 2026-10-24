package com.piggie.tv.ui.player

import android.view.View
import android.widget.ScrollView

/** One bounded correction after the platform scrolls a newly focused shelf card into view. */
internal object DetailsFocusViewport {
    fun ensureBottomClearance(scroll: ScrollView, focusedCard: View, clearancePx: Int) {
        if (clearancePx <= 0 || scroll.height <= 0 || focusedCard.height <= 0) return
        val viewport = IntArray(2)
        val card = IntArray(2)
        scroll.getLocationOnScreen(viewport)
        focusedCard.getLocationOnScreen(card)
        val safeBottom = viewport[1] + scroll.height - clearancePx
        val overflow = card[1] + focusedCard.height - safeBottom
        if (overflow > 0) scroll.scrollBy(0, overflow)
    }
}
