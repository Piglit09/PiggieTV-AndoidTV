package com.piggie.tv.ui.layout

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class TvCardSpacingDecoration(
    private val spacingPx: Int
) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        if (parent.getChildAdapterPosition(view) != RecyclerView.NO_POSITION) {
            outRect.right = spacingPx
        }
    }
}
