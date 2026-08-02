package com.piggie.tv.ui.rendering

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.withTranslation
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

internal object MediaCardFocusBorderStyle {
    const val gradientStartColor: Int = -0x63B201
    const val gradientEndColor: Int = -0xC739
}

/**
 * Retains the diagonal shader while the overlay moves between equal-sized cards. Each shelf owns
 * one overlay, so normal horizontal traversal creates the shader once rather than once per focus.
 */
internal class FocusGradientShaderCache {
    private var width = -1
    private var height = -1
    private var shader: Shader? = null

    fun shaderFor(width: Int, height: Int): Shader? {
        if (width <= 0 || height <= 0) return null
        if (width != this.width || height != this.height || shader == null) {
            this.width = width
            this.height = height
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                MediaCardFocusBorderStyle.gradientStartColor,
                MediaCardFocusBorderStyle.gradientEndColor,
                Shader.TileMode.CLAMP
            )
        }
        return shader
    }
}

/** A transparent, border-only focus ring matching the media artwork's rounded outline. */
internal class FocusBorderDrawable(resources: Resources) : Drawable() {
    private val strokeWidth = resources.getDimensionPixelSize(R.dimen.tv_focus_border_width).toFloat()
    private val cornerRadius = resources.getDimension(R.dimen.tv_media_artwork_corner_radius)
    private val localBounds = RectF()
    private val shaderCache = FocusGradientShaderCache()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@FocusBorderDrawable.strokeWidth
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.isEmpty) {
            localBounds.setEmpty()
            return
        }
        val inset = strokeWidth / 2f
        localBounds.set(
            inset,
            inset,
            bounds.width().toFloat() - inset,
            bounds.height().toFloat() - inset
        )
        paint.shader = shaderCache.shaderFor(bounds.width(), bounds.height())
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        canvas.withTranslation(bounds.left.toFloat(), bounds.top.toFloat()) {
            drawRoundRect(localBounds, cornerRadius, cornerRadius, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

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
        private var focusedViewRef: WeakReference<View>? = null
        private var focusedArtworkRef: WeakReference<View>? = null
        private var focusedViewId: String? = null
        private val lastBounds = Rect()
        private val nextBounds = Rect()
        private val invalidBounds = Rect()
        private val border = FocusBorderDrawable(recycler.resources)
        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            focusedViewRef?.get()?.let { updateBounds(it, captureDiagnostics = false) }
        }

        init {
            recycler.overlay.add(border)
            recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    focusedViewRef?.get()?.let { updateBounds(it, captureDiagnostics = false) }
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        focusedViewRef?.get()?.let { updateBounds(it, captureDiagnostics = true) }
                    }
                }
            })
        }

        fun focus(view: View) {
            removeLayoutListeners()
            val artwork = registeredArtwork(view)
            focusedViewRef = WeakReference(view)
            focusedArtworkRef = WeakReference(artwork)
            focusedViewId = if (PtvDiagnosticsManager.shouldCaptureFocusGeometry()) viewId(view) else null
            view.addOnLayoutChangeListener(layoutListener)
            if (artwork !== view) artwork.addOnLayoutChangeListener(layoutListener)
            updateBounds(view, captureDiagnostics = true)
            diagnose(view)
        }

        fun clear(view: View) {
            state.clear(viewKey(view))
            if (focusedViewRef?.get() === view) {
                removeLayoutListeners()
                focusedViewRef = null
                focusedArtworkRef = null
                focusedViewId = null
                val recycler = recyclerRef.get() ?: return
                invalidBounds.set(lastBounds)
                lastBounds.setEmpty()
                border.bounds = EMPTY_RECT
                recycler.invalidate(invalidBounds)
            }
        }

        private fun updateBounds(view: View, captureDiagnostics: Boolean) {
            val recycler = recyclerRef.get() ?: return
            val artwork = registeredArtwork(view)
            if (!artwork.isAttachedToWindow || artwork.width <= 0 || artwork.height <= 0) return
            nextBounds.set(0, 0, artwork.width, artwork.height)
            recycler.offsetDescendantRectToMyCoords(artwork, nextBounds)
            val key = viewKey(view)
            if (state.focusedKey != key) {
                state.focus(
                    key,
                    FocusOverlayBounds(nextBounds.left, nextBounds.top, nextBounds.right, nextBounds.bottom)
                )
            }
            if (lastBounds != nextBounds) {
                invalidBounds.set(lastBounds)
                invalidBounds.union(nextBounds)
                lastBounds.set(nextBounds)
                border.setBounds(nextBounds.left, nextBounds.top, nextBounds.right, nextBounds.bottom)
                recycler.invalidate(invalidBounds)
            }
            if (captureDiagnostics && PtvDiagnosticsManager.shouldCaptureFocusGeometry()) {
                captureGeometry(recycler, view, nextBounds)
            }
        }

        private fun captureGeometry(recycler: RecyclerView, view: View, artworkBounds: Rect) {
            val card = Rect(0, 0, view.width, view.height)
            recycler.offsetDescendantRectToMyCoords(view, card)
            PtvDiagnosticsManager.updateFocusGeometry(
                focusedViewId = focusedViewId ?: viewId(view).also { focusedViewId = it },
                cardBounds = card.asGeometryBounds(),
                artworkBounds = artworkBounds.asGeometryBounds(),
                indicatorBounds = artworkBounds.asGeometryBounds()
            )
        }

        private fun diagnose(view: View) {
            if (!PtvDiagnosticsManager.shouldCollectShelfTrace()) return
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
            val focusedView = focusedViewRef?.get()
            focusedView?.removeOnLayoutChangeListener(layoutListener)
            focusedArtworkRef?.get()
                ?.takeIf { it !== focusedView }
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
