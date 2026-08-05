package com.piggie.tv.ui.player

import com.piggie.tv.data.models.PlaybackSkipSegment
import com.piggie.tv.data.models.PlaybackSkipSegmentType
import java.util.concurrent.ConcurrentHashMap

enum class PlaybackLaunchOrigin {
    DEFAULT,
    CONTINUE_WATCHING
}

internal enum class PlaybackExitDestination {
    BACK_STACK,
    HOME,
    DETAILS
}

internal object PlaybackExitPolicy {
    fun afterNaturalCompletion(origin: PlaybackLaunchOrigin): PlaybackExitDestination =
        if (origin == PlaybackLaunchOrigin.CONTINUE_WATCHING) {
            PlaybackExitDestination.DETAILS
        } else {
            PlaybackExitDestination.BACK_STACK
        }

    fun afterUserBack(origin: PlaybackLaunchOrigin): PlaybackExitDestination =
        if (origin == PlaybackLaunchOrigin.CONTINUE_WATCHING) {
            PlaybackExitDestination.HOME
        } else {
            PlaybackExitDestination.BACK_STACK
        }
}

internal class PlaybackNaturalCompletionState {
    private val handled = java.util.concurrent.atomic.AtomicBoolean(false)

    fun claim(): Boolean = handled.compareAndSet(false, true)

    fun reset() {
        handled.set(false)
    }
}

internal object PlayerSkipSegmentPolicy {
    fun activeSegment(
        segments: List<PlaybackSkipSegment>,
        positionMs: Long
    ): PlaybackSkipSegment? {
        val positionTicks = PlayerRestartPolicy.positionTicks(positionMs)
        return segments
            .asSequence()
            .filter(PlaybackSkipSegment::isValid)
            .filter { positionTicks >= it.startTicks && positionTicks < it.endTicks }
            .minByOrNull(PlaybackSkipSegment::endTicks)
    }

    fun targetPositionMs(segment: PlaybackSkipSegment, durationMs: Long): Long? {
        if (!segment.isValid) return null
        val targetMs = segment.endTicks / PlayerRestartPolicy.TICKS_PER_MILLISECOND
        if (targetMs <= 0L) return null
        return if (durationMs > 0L) targetMs.coerceAtMost(durationMs) else targetMs
    }

    fun label(segment: PlaybackSkipSegment): String = when (segment.type) {
        PlaybackSkipSegmentType.INTRO -> "Skip Intro"
        PlaybackSkipSegmentType.OUTRO -> "Skip Outro"
    }
}

object PlayerRestartPolicy {
    const val TICKS_PER_MILLISECOND = 10_000L

    fun positionTicks(positionMs: Long): Long =
        positionMs.coerceAtLeast(0L) * TICKS_PER_MILLISECOND
}

internal data class PlaybackStopReport(
    val itemId: String,
    val playSessionId: String,
    val positionTicks: Long
)

internal sealed interface PlaybackStopReportDecision {
    data class Claimed(val report: PlaybackStopReport) : PlaybackStopReportDecision
    data object InFlight : PlaybackStopReportDecision
    data object Complete : PlaybackStopReportDecision
    data object NoActiveSession : PlaybackStopReportDecision
}

/**
 * Coordinates stop reports by Jellyfin play session, rather than by media item.
 *
 * A dialog-triggered restart creates a new play session for the same item and therefore needs a
 * new eventual stop report. Conversely, natural-end, autoplay, and user-exit paths can converge
 * on the same session; only the first path may send its report.
 */
internal class PlaybackStopReportState {
    private data class SessionKey(val itemId: String, val playSessionId: String)
    private enum class Status { IN_FLIGHT, COMPLETE }

    private val sessions = ConcurrentHashMap<SessionKey, Status>()

    fun begin(itemId: String, playSessionId: String, positionTicks: Long): PlaybackStopReportDecision {
        if (itemId.isBlank() || playSessionId.isBlank()) {
            return PlaybackStopReportDecision.NoActiveSession
        }

        val key = SessionKey(itemId, playSessionId)
        return when (sessions.putIfAbsent(key, Status.IN_FLIGHT)) {
            null -> PlaybackStopReportDecision.Claimed(
                PlaybackStopReport(itemId, playSessionId, positionTicks.coerceAtLeast(0L))
            )
            Status.IN_FLIGHT -> PlaybackStopReportDecision.InFlight
            Status.COMPLETE -> PlaybackStopReportDecision.Complete
        }
    }

    fun complete(report: PlaybackStopReport) {
        sessions.replace(
            SessionKey(report.itemId, report.playSessionId),
            Status.IN_FLIGHT,
            Status.COMPLETE
        )
    }

    fun failed(report: PlaybackStopReport) {
        sessions.remove(
            SessionKey(report.itemId, report.playSessionId),
            Status.IN_FLIGHT
        )
    }
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
