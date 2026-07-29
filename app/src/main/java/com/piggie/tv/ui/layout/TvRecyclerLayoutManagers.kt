package com.piggie.tv.ui.layout

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * TV focus should reveal its destination in the same frame. RecyclerView's default smooth
 * child-rectangle scroll produces a series of full-surface frames for a single D-pad press,
 * which is disproportionately expensive on Fire TV's Skia/OpenGL pipeline.
 */
class TvGridLayoutManager(context: Context, spanCount: Int) : GridLayoutManager(context, spanCount) {
    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean
    ): Boolean = super.requestChildRectangleOnScreen(parent, child, rect, true, focusedChildVisible)
}

class TvLinearLayoutManager(
    context: Context,
    orientation: Int,
    reverseLayout: Boolean
) : LinearLayoutManager(context, orientation, reverseLayout) {
    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean
    ): Boolean = super.requestChildRectangleOnScreen(parent, child, rect, true, focusedChildVisible)
}
