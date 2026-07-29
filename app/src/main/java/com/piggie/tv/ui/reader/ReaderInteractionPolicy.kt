package com.piggie.tv.ui.reader

import kotlin.math.min

enum class ReaderFitMode {
    FIT_PAGE,
    FIT_WIDTH,
    ACTUAL_SIZE
}

data class ReaderFitResult(
    val scale: Float,
    val scaledWidth: Float,
    val scaledHeight: Float,
    val offsetX: Float,
    val offsetY: Float
)

object ReaderFitCalculator {
    fun calculate(
        mode: ReaderFitMode,
        availableWidth: Int,
        availableHeight: Int,
        pageWidth: Int,
        pageHeight: Int,
        maximumAutomaticScale: Float = 1f
    ): ReaderFitResult {
        if (availableWidth <= 0 || availableHeight <= 0 || pageWidth <= 0 || pageHeight <= 0) {
            return ReaderFitResult(1f, pageWidth.toFloat(), pageHeight.toFloat(), 0f, 0f)
        }
        val widthScale = availableWidth.toFloat() / pageWidth
        val heightScale = availableHeight.toFloat() / pageHeight
        val automatic = when (mode) {
            ReaderFitMode.FIT_PAGE -> min(widthScale, heightScale)
            ReaderFitMode.FIT_WIDTH -> widthScale
            ReaderFitMode.ACTUAL_SIZE -> 1f
        }
        val scale = if (mode == ReaderFitMode.ACTUAL_SIZE) 1f else automatic.coerceAtMost(maximumAutomaticScale)
        val scaledWidth = pageWidth * scale
        val scaledHeight = pageHeight * scale
        return ReaderFitResult(
            scale = scale,
            scaledWidth = scaledWidth,
            scaledHeight = scaledHeight,
            offsetX = (availableWidth - scaledWidth) / 2f,
            offsetY = (availableHeight - scaledHeight) / 2f
        )
    }
}

enum class ReaderHorizontalDirection { LEFT, RIGHT }
enum class ReaderPageAction { PREVIOUS, NEXT }

object ReaderNavigationPolicy {
    fun pageAction(direction: ReaderHorizontalDirection, isRtl: Boolean): ReaderPageAction = when {
        direction == ReaderHorizontalDirection.RIGHT && !isRtl -> ReaderPageAction.NEXT
        direction == ReaderHorizontalDirection.LEFT && isRtl -> ReaderPageAction.NEXT
        else -> ReaderPageAction.PREVIOUS
    }
}

/** Requires a second edge press before changing pages while zoomed. */
class ReaderEdgeTurnGate(private val timeoutMs: Long = 900L) {
    private var armedDirection: ReaderHorizontalDirection? = null
    private var armedAt: Long = 0L

    fun onEdgePress(direction: ReaderHorizontalDirection, nowMs: Long): Boolean {
        val shouldTurn = armedDirection == direction && nowMs - armedAt <= timeoutMs
        armedDirection = if (shouldTurn) null else direction
        armedAt = if (shouldTurn) 0L else nowMs
        return shouldTurn
    }

    fun reset() {
        armedDirection = null
        armedAt = 0L
    }
}
