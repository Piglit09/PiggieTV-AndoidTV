package com.piggie.tv.ui.layout

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Keeps left/right D-pad navigation within a horizontal shelf.
 *
 * RecyclerView's default FocusFinder can choose a card in an adjacent row while the requested
 * horizontal child is still being attached. This class scrolls/attaches that child and holds
 * focus in the current row for the interim frame.
 */
class TvHorizontalRecyclerView(context: Context) : RecyclerView(context) {
    override fun focusSearch(focused: View?, direction: Int): View? {
        if (focused != null && (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT)) {
            val itemView = findContainingItemView(focused)
            val current = itemView?.let(::getChildAdapterPosition) ?: NO_POSITION
            val count = adapter?.itemCount ?: 0
            if (current != NO_POSITION && count > 0) {
                val delta = if (direction == View.FOCUS_RIGHT) 1 else -1
                val target = current + delta
                if (target !in 0 until count) return focused
                findViewHolderForAdapterPosition(target)?.itemView?.let { return it }

                (layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(target, paddingLeft)
                post {
                    findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                }
                return focused
            }
        }
        return super.focusSearch(focused, direction)
    }
}
