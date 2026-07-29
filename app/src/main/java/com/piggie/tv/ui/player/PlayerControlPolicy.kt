package com.piggie.tv.ui.player

object PlayerRestartPolicy {
    const val TICKS_PER_MILLISECOND = 10_000L

    fun positionTicks(positionMs: Long): Long =
        positionMs.coerceAtLeast(0L) * TICKS_PER_MILLISECOND
}

/**
 * Retains the controller timeout that was active before a selection-dialog sequence.
 *
 * A new dialog invalidates callbacks from an older dialog while preserving the original
 * pre-dialog timeout until the latest dialog's post-dismiss grace period completes.
 */
internal class PlayerDialogTimeoutState {
    private var baselineTimeoutMs: Int? = null
    private var generation = 0

    fun begin(currentTimeoutMs: Int): Int {
        if (baselineTimeoutMs == null) baselineTimeoutMs = currentTimeoutMs
        generation += 1
        return generation
    }

    fun isCurrent(token: Int): Boolean = token == generation

    fun finish(token: Int): Int? {
        if (!isCurrent(token)) return null
        val baseline = baselineTimeoutMs
        baselineTimeoutMs = null
        return baseline
    }

    fun clear() {
        generation += 1
        baselineTimeoutMs = null
    }
}
