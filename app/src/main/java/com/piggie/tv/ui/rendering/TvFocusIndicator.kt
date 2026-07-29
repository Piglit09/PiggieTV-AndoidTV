package com.piggie.tv.ui.rendering

import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.piggie.tv.R
import com.piggie.tv.diagnostics.PtvFocusGeometryBounds
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import java.lang.ref.WeakReference
import java.util.WeakHashMap

data class FocusOverlayBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

class FocusOverlayState {
    var focusedKey: Long? = null
        private set
    var bounds: FocusOverlayBounds? = null
        private set

    fun focus(key: Long, newBounds: FocusOverlayBounds) {
        focusedKey = key
        bounds = newBounds
    }

    fun clear(key: Long) {
        if (focusedKey == key) {
            focusedKey = null
            bounds = null
        }
    }
}

/**
 * A single border drawable per RecyclerView. Focusable cards register their artwork descendant;
 * titles and metadata remain outside the border without adding a drawable or animation per card.
 */
object TvFocusIndicator {
    private val overlayControllers = WeakHashMap<RecyclerView, OverlayController>()

    fun registerArtwork(focusable: View, artwork: View) {
        focusable.setTag(R.id.focus_artwork_view, artwork)
    }

    fun onFocusChanged(view: View, focused: Boolean) {
        val recycler = findRecyclerAncestor(view) ?: return
        val controller = overlayControllers.getOrPut(recycler) { OverlayController(recycler) }
        if (focused) controller.focus(view) else controller.clear(view)
    }

    fun artworkBoundsOnScreen(view: View): Rect {
        val artwork = registeredArtwork(view)
        val target = artwork.takeIf {
            it.isAttachedToWindow && it.width > 0 && it.height > 0
        } ?: view
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        return Rect(
            location[0],
            location[1],
            location[0] + target.width,
            location[1] + target.height
        )
    }

    private class OverlayController(recycler: RecyclerView) {
        private val recyclerRef = WeakReference(recycler)
        private val state = FocusOverlayState()
        private var focusedView: View? = null
        private var focusedArtwork: View? = null
        private var lastBounds = Rect()
        private val border = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(
                recycler.resources.getDimensionPixelSize(R.dimen.tv_focus_border_width),
                recycler.context.getColor(R.color.tv_focus_ring)
            )
            cornerRadius = 0f
        }
        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            focusedView?.let(::updateBounds)
        }

        init {
            recycler.overlay.add(border)
            recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    focusedView?.let(::updateBounds)
                }
            })
        }

        fun focus(view: View) {
            removeLayoutListeners()
            focusedView = view
            focusedArtwork = registeredArtwork(view)
            view.addOnLayoutChangeListener(layoutListener)
            if (focusedArtwork !== view) focusedArtwork?.addOnLayoutChangeListener(layoutListener)
            updateBounds(view)
            diagnose(view)
        }

        fun clear(view: View) {
            state.clear(viewKey(view))
            if (focusedView === view) {
                removeLayoutListeners()
                focusedView = null
                focusedArtwork = null
                val recycler = recyclerRef.get() ?: return
                val invalid = Rect(lastBounds)
                lastBounds.setEmpty()
                border.bounds = EMPTY_RECT
                recycler.invalidate(invalid)
            }
        }

        private fun updateBounds(view: View) {
            val recycler = recyclerRef.get() ?: return
            val artwork = registeredArtwork(view)
            if (!artwork.isAttachedToWindow || artwork.width <= 0 || artwork.height <= 0) return
            val bounds = Rect(0, 0, artwork.width, artwork.height)
            recycler.offsetDescendantRectToMyCoords(artwork, bounds)
            state.focus(
                viewKey(view),
                FocusOverlayBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
            )
            val invalid = Rect(lastBounds)
            invalid.union(bounds)
            lastBounds = Rect(bounds)
            border.bounds = bounds
            recycler.invalidate(invalid)
            if (PtvDiagnosticsManager.isEnabled()) {
                val card = Rect(0, 0, view.width, view.height)
                recycler.offsetDescendantRectToMyCoords(view, card)
                PtvDiagnosticsManager.updateFocusGeometry(
                    focusedViewId = viewId(view),
                    cardBounds = card.asGeometryBounds(),
                    artworkBounds = bounds.asGeometryBounds(),
                    indicatorBounds = border.bounds.asGeometryBounds()
                )
            }
        }

        private fun diagnose(view: View) {
            if (!PtvDiagnosticsManager.isEnabled()) return
            val recycler = recyclerRef.get() ?: return
            val card = Rect(0, 0, view.width, view.height)
            recycler.offsetDescendantRectToMyCoords(view, card)
            val artwork = registeredArtwork(view)
            val art = Rect(0, 0, artwork.width, artwork.height)
            recycler.offsetDescendantRectToMyCoords(artwork, art)
            PtvDiagnosticsManager.event(
                "focus",
                "artwork_bounds",
                mapOf(
                    "focusedViewId" to viewId(view),
                    "cardBounds" to card.asDiagnostic(),
                    "artworkBounds" to art.asDiagnostic(),
                    "indicatorBounds" to art.asDiagnostic()
                )
            )
        }

        private fun removeLayoutListeners() {
            focusedView?.removeOnLayoutChangeListener(layoutListener)
            focusedArtwork?.takeIf { it !== focusedView }
                ?.removeOnLayoutChangeListener(layoutListener)
        }
    }

    private fun registeredArtwork(view: View): View {
        var current: View? = view
        while (current != null) {
            (current.getTag(R.id.focus_artwork_view) as? View)?.let { return it }
            current = current.parent as? View
        }
        return view
    }

    private fun findRecyclerAncestor(view: View): RecyclerView? {
        var parent = view.parent
        while (parent is ViewGroup) {
            if (parent is RecyclerView) return parent
            parent = parent.parent
        }
        return null
    }

    private fun viewKey(view: View): Long =
        (view.id.takeIf { it != View.NO_ID } ?: System.identityHashCode(view)).toLong()

    private fun viewId(view: View): String =
        view.id.takeIf { it != View.NO_ID }
            ?.let { runCatching { view.resources.getResourceEntryName(it) }.getOrNull() }
            ?: view.javaClass.simpleName

    private fun Rect.asDiagnostic() = "$left,$top,$right,$bottom"

    private fun Rect.asGeometryBounds() =
        PtvFocusGeometryBounds(left, top, right, bottom)

    private val EMPTY_RECT = Rect()
}
