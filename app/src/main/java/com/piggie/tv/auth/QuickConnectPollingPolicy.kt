package com.piggie.tv.auth

enum class QuickConnectPollOutcome { WAITING, EXPIRED, FAILED }

object QuickConnectPollingPolicy {
    const val POLL_INTERVAL_MS = 2_000L
    const val TIMEOUT_MS = 10 * 60 * 1_000L
    fun outcomeFor(status: Int): QuickConnectPollOutcome = when (status) {
        401, 403 -> QuickConnectPollOutcome.WAITING
        404 -> QuickConnectPollOutcome.EXPIRED
        else -> QuickConnectPollOutcome.FAILED
    }

    fun hasTimedOut(startedAtMs: Long, nowMs: Long): Boolean =
        startedAtMs > 0L && nowMs - startedAtMs >= TIMEOUT_MS
}
