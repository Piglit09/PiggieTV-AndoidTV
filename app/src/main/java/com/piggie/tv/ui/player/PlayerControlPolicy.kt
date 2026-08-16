package com.piggie.tv.ui.player

import androidx.media3.common.PlaybackException
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

/**
 * A TV player activity that is fully hidden must not retain its codec until Android happens to
 * destroy the activity. Configuration changes and picture-in-picture keep ownership in place;
 * ordinary backgrounding releases and later renegotiates from the captured position.
 */
internal object PlayerBackgroundReleasePolicy {
    fun shouldRelease(
        changingConfigurations: Boolean,
        inPictureInPicture: Boolean,
        finishing: Boolean,
        userExitRequested: Boolean
    ): Boolean = !changingConfigurations &&
        !inPictureInPicture &&
        !finishing &&
        !userExitRequested
}

/**
 * Separates transient transport recovery from format compatibility recovery.
 *
 * A different Jellyfin route cannot repair a stalled server connection. Network failures retry
 * the already installed Media3 item so they do not create a new play session or wait on another
 * PlaybackInfo request. Transcoding is reserved for failures where changing the container or
 * codecs can actually help.
 */
internal object PlayerErrorRecoveryPolicy {
    fun isTransientNetworkError(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> true
        else -> false
    }

    fun shouldAttemptTranscode(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true
        else -> false
    }
}

internal data class PlayerNetworkRetry(
    val attempt: Int,
    val delayMs: Long
)

/** Claims the bounded retry schedule for one installed playback stream. */
internal class PlayerNetworkRetryState(
    retryDelaysMs: LongArray = DEFAULT_RETRY_DELAYS_MS
) {
    private val retryDelaysMs = retryDelaysMs.copyOf()

    var attemptsUsed: Int = 0
        private set

    init {
        require(this.retryDelaysMs.all { it >= 0L }) { "Retry delays must be non-negative" }
    }

    fun claim(errorCode: Int): PlayerNetworkRetry? {
        if (!PlayerErrorRecoveryPolicy.isTransientNetworkError(errorCode)) return null
        val delayMs = retryDelaysMs.getOrNull(attemptsUsed) ?: return null
        attemptsUsed += 1
        return PlayerNetworkRetry(attempt = attemptsUsed, delayMs = delayMs)
    }

    fun reset() {
        attemptsUsed = 0
    }

    companion object {
        private val DEFAULT_RETRY_DELAYS_MS = longArrayOf(2_000L, 10_000L, 30_000L)
        val maxAttempts: Int
            get() = DEFAULT_RETRY_DELAYS_MS.size
    }
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
