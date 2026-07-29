package com.piggie.tv.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.piggie.tv.R
import com.piggie.tv.data.playback.MusicProgressSnapshot
import com.piggie.tv.data.playback.WaveProgressModel
import kotlin.math.min

/**
 * A static waveform-inspired progress view. It owns no animator or timer; its
 * caller supplies bounded playback snapshots.
 */
class PtvWaveProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val barCount = WaveProgressModel.DEFAULT_BAR_COUNT
    private var playedColor = context.getColor(R.color.tv_button)
    private var remainingColor = context.getColor(R.color.tv_text_muted)

    var progressFraction: Float = 0f
        set(value) {
            val normalized = value.coerceIn(0f, 1f)
            if (field == normalized) return
            field = normalized
            invalidate()
        }

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun render(snapshot: MusicProgressSnapshot) {
        progressFraction = snapshot.playedFraction
    }

    fun setProgress(positionMs: Long, durationMs: Long) {
        progressFraction = WaveProgressModel.fraction(positionMs, durationMs)
    }

    fun setWaveColors(played: Int, remaining: Int) {
        if (playedColor == played && remainingColor == remaining) return
        playedColor = played
        remainingColor = remaining
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (180f * density).toInt() + paddingLeft + paddingRight
        val desiredHeight = (24f * density).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        val availableHeight = (height - paddingTop - paddingBottom).toFloat()
        if (availableWidth <= 0f || availableHeight <= 0f) return

        val gap = min(2f * density, availableWidth / (barCount * 3f))
        val barWidth = (
            (availableWidth - gap * (barCount - 1)) / barCount
            ).coerceAtLeast(1f)
        val playedBars = WaveProgressModel.playedBarCount(progressFraction, barCount)
        val centerY = paddingTop + availableHeight / 2f
        val radius = min(barWidth / 2f, 2f * density)

        repeat(barCount) { index ->
            val barHeight = availableHeight * WaveProgressModel.barHeightFraction(index)
            val left = paddingLeft + index * (barWidth + gap)
            val top = centerY - barHeight / 2f
            paint.color = if (index < playedBars) playedColor else remainingColor
            canvas.drawRoundRect(left, top, left + barWidth, top + barHeight, radius, radius, paint)
        }
    }
}
