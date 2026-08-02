package com.piggie.tv.ui.layout

import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import com.piggie.tv.ui.discovery.DiscoveryPageAdapter
import com.piggie.tv.ui.rendering.TvFocusIndicator

object ShelfAnchorPolicy {
    const val ACTIVE_SHELF_ANCHOR = 0.45f

    fun requestedRowTop(
        viewportHeight: Int,
        artworkCenterOffsetInRow: Int,
        anchor: Float = ACTIVE_SHELF_ANCHOR
    ): Int =
        (viewportHeight * anchor.coerceIn(0f, 1f)).toInt() - artworkCenterOffsetInRow
}

/**
 * Moves the vertical page only when focus enters a different shelf. Horizontal D-pad movement
 * therefore remains entirely owned by the nested shelf RecyclerView.
 */
class TvShelfScrollCoordinator(
    private val page: RecyclerView,
    private val headerOffset: Int,
    private val shelfIdAt: (Int) -> String?,
    private val route: String
) {
    constructor(
        page: RecyclerView,
        adapter: DiscoveryPageAdapter,
        route: String
    ) : this(page, adapter.headerOffset, adapter::shelfIdAt, route)

    private var activeAdapterPosition = RecyclerView.NO_POSITION
    private var attached = false

    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, focused ->
        focused ?: return@OnGlobalFocusChangeListener
        val holder = page.findContainingViewHolder(focused) ?: return@OnGlobalFocusChangeListener
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION || position < headerOffset) return@OnGlobalFocusChangeListener
        if (position == activeAdapterPosition) return@OnGlobalFocusChangeListener
        activeAdapterPosition = position
        centerShelf(position, holder.itemView, focused)
    }

    fun attach() {
        if (attached) return
        attached = true
        page.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
    }

    fun detach() {
        if (!attached) return
        attached = false
        if (page.viewTreeObserver.isAlive) {
            page.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        }
    }

    private fun centerShelf(position: Int, row: View, focused: View) {
        val manager = page.layoutManager as? TvLinearLayoutManager ?: return
        val firstShelfPosition = headerOffset
        if (position == firstShelfPosition && headerOffset > 0) {
            if (manager.findFirstVisibleItemPosition() != 0) {
                manager.scrollToPositionWithOffset(0, 0)
            }
            record(position, 0, focused)
            return
        }

        val rowLocation = IntArray(2)
        row.getLocationOnScreen(rowLocation)
        val artworkBounds = TvFocusIndicator.artworkBoundsOnScreen(focused)
        val artworkCenterOffset = artworkBounds.centerY() - rowLocation[1]
        val requestedOffset = ShelfAnchorPolicy.requestedRowTop(
            page.height,
            artworkCenterOffset
        )
        manager.scrollToPositionWithOffset(position, requestedOffset)
        record(position, requestedOffset, focused)
    }

    private fun record(position: Int, requestedOffset: Int, focused: View) {
        if (!PtvDiagnosticsManager.shouldCollectShelfTrace()) return
        page.post {
            val bounds = TvFocusIndicator.artworkBoundsOnScreen(focused)
            PtvDiagnosticsManager.event(
                "focus",
                "shelf_center",
                mapOf(
                    "route" to route,
                    "activeShelfId" to (shelfIdAt(position) ?: "unknown"),
                    "adapterIndex" to position.toString(),
                    "requestedOffsetPx" to requestedOffset.toString(),
                    "resultingCardBounds" to
                        "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
                )
            )
        }
    }
}
