package com.piggie.tv.data.playback

import java.util.Locale
import kotlin.math.roundToInt

data class MusicProgressSnapshot(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L
) {
    val playedFraction: Float
        get() = WaveProgressModel.fraction(positionMs, durationMs)

    val bufferedFraction: Float
        get() = WaveProgressModel.fraction(bufferedPositionMs, durationMs)

    val remainingMs: Long
        get() = (durationMs - positionMs).coerceAtLeast(0L)
}

object WaveProgressModel {
    const val UPDATE_INTERVAL_MS = 750L
    const val DEFAULT_BAR_COUNT = 24

    private val barPattern = floatArrayOf(
        0.38f,
        0.66f,
        0.92f,
        0.54f,
        0.78f,
        0.44f,
        0.86f,
        0.61f
    )

    fun snapshot(
        positionMs: Long,
        durationMs: Long,
        bufferedPositionMs: Long = positionMs
    ): MusicProgressSnapshot {
        val safeDuration = durationMs.coerceAtLeast(0L)
        fun clampToDuration(value: Long): Long {
            val nonNegative = value.coerceAtLeast(0L)
            return if (safeDuration > 0L) nonNegative.coerceAtMost(safeDuration) else nonNegative
        }
        return MusicProgressSnapshot(
            positionMs = clampToDuration(positionMs),
            durationMs = safeDuration,
            bufferedPositionMs = clampToDuration(bufferedPositionMs)
        )
    }

    fun fraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toDouble() / durationMs.toDouble())
            .coerceIn(0.0, 1.0)
            .toFloat()
    }

    fun playedBarCount(
        fraction: Float,
        barCount: Int = DEFAULT_BAR_COUNT
    ): Int {
        if (barCount <= 0) return 0
        return (fraction.coerceIn(0f, 1f) * barCount)
            .roundToInt()
            .coerceIn(0, barCount)
    }

    fun barHeightFraction(index: Int): Float =
        barPattern[Math.floorMod(index, barPattern.size)]

    fun formatTime(positionMs: Long): String {
        val totalSeconds = positionMs.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
