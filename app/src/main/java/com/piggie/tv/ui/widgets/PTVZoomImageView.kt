package com.piggie.tv.ui.widgets

import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.appcompat.widget.AppCompatImageView
import com.piggie.tv.ui.reader.ReaderFitCalculator
import com.piggie.tv.ui.reader.ReaderFitMode
import kotlin.math.abs
import kotlin.math.max

class PTVZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    private val contentInsets = Rect()
    private val workingMatrix = Matrix()
    private var fitMode = ReaderFitMode.FIT_PAGE
    private var baseScale = 1f
    private var userZoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var maxPanX = 0f
    private var maxPanY = 0f

    init {
        scaleType = ScaleType.MATRIX
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setFitMode(mode: ReaderFitMode) {
        fitMode = mode
        userZoom = 1f
        panX = 0f
        panY = 0f
        updateMatrix()
    }

    fun getFitMode(): ReaderFitMode = fitMode

    fun setContentInsets(left: Int, top: Int, right: Int, bottom: Int) {
        contentInsets.set(left, top, right, bottom)
        updateMatrix()
    }

    fun zoomIn(): Boolean {
        val old = userZoom
        userZoom = (userZoom * ZOOM_STEP).coerceAtMost(MAX_USER_ZOOM)
        updateMatrix()
        return userZoom != old
    }

    fun zoomOut(): Boolean {
        val old = userZoom
        userZoom = (userZoom / ZOOM_STEP).coerceAtLeast(1f)
        updateMatrix()
        return userZoom != old
    }

    fun resetZoom() {
        userZoom = 1f
        panX = 0f
        panY = 0f
        updateMatrix()
    }

    fun isZoomedOrPannable(): Boolean = userZoom > 1.001f || maxPanX > 0.5f || maxPanY > 0.5f

    fun hasUserTransform(): Boolean = userZoom > 1.001f || abs(panX) > 0.5f || abs(panY) > 0.5f

    /** Returns true only when the page actually moved. */
    fun panForKey(keyCode: Int): Boolean {
        val beforeX = panX
        val beforeY = panY
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> panX += PAN_STEP_PX
            KeyEvent.KEYCODE_DPAD_RIGHT -> panX -= PAN_STEP_PX
            KeyEvent.KEYCODE_DPAD_UP -> panY += PAN_STEP_PX
            KeyEvent.KEYCODE_DPAD_DOWN -> panY -= PAN_STEP_PX
            else -> return false
        }
        clampPan()
        updateMatrix()
        return abs(beforeX - panX) > 0.5f || abs(beforeY - panY) > 0.5f
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        panX = 0f
        panY = 0f
        post(::updateMatrix)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    private fun updateMatrix() {
        val drawable = drawable ?: return
        val pageWidth = drawable.intrinsicWidth
        val pageHeight = drawable.intrinsicHeight
        val availableWidth = width - contentInsets.left - contentInsets.right
        val availableHeight = height - contentInsets.top - contentInsets.bottom
        if (pageWidth <= 0 || pageHeight <= 0 || availableWidth <= 0 || availableHeight <= 0) return

        val fit = ReaderFitCalculator.calculate(
            mode = fitMode,
            availableWidth = availableWidth,
            availableHeight = availableHeight,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            maximumAutomaticScale = MAX_AUTOMATIC_SCALE
        )
        baseScale = fit.scale
        val scale = baseScale * userZoom
        val scaledWidth = pageWidth * scale
        val scaledHeight = pageHeight * scale
        maxPanX = max(0f, (scaledWidth - availableWidth) / 2f)
        maxPanY = max(0f, (scaledHeight - availableHeight) / 2f)
        clampPan()

        val left = contentInsets.left + (availableWidth - scaledWidth) / 2f + panX
        val top = contentInsets.top + (availableHeight - scaledHeight) / 2f + panY
        workingMatrix.reset()
        workingMatrix.setScale(scale, scale)
        workingMatrix.postTranslate(left, top)
        imageMatrix = workingMatrix
    }

    private fun clampPan() {
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
    }

    companion object {
        private const val MAX_AUTOMATIC_SCALE = 1f
        private const val MAX_USER_ZOOM = 4f
        private const val ZOOM_STEP = 1.25f
        private const val PAN_STEP_PX = 96f
    }
}
