package com.piggie.tv.navigation

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/** Applies the host's route-aware policy only when Up leaves page content. */
class RouteContentFrame(
    context: Context,
    private val resolveUpExit: (focused: View, defaultTarget: View) -> View
) : FrameLayout(context) {
    override fun focusSearch(focused: View?, direction: Int): View? {
        val defaultTarget = super.focusSearch(focused, direction)
        return if (direction == View.FOCUS_UP && focused != null && defaultTarget != null) {
            resolveUpExit(focused, defaultTarget)
        } else {
            defaultTarget
        }
    }
}
