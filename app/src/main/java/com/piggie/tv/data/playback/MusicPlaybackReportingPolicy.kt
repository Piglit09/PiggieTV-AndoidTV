package com.piggie.tv.data.playback

sealed interface MusicPlaybackReportEvent {
    val itemId: String
    val playSessionId: String
    val positionTicks: Long

    data class Playing(
        override val itemId: String,
        override val playSessionId: String,
        override val positionTicks: Long
    ) : MusicPlaybackReportEvent

    data class Progress(
        override val itemId: String,
        override val playSessionId: String,
        override val positionTicks: Long,
        val isPaused: Boolean
    ) : MusicPlaybackReportEvent

    data class Stopped(
        override val itemId: String,
        override val playSessionId: String,
        override val positionTicks: Long
    ) : MusicPlaybackReportEvent
}

/**
 * Pure state machine that guarantees one Playing/Stopped lifetime per active queue item.
 * Player callbacks can overlap (error followed by idle, end followed by a null transition), so
 * clearing the active item when Stopped is emitted is what prevents duplicate server reports.
 */
class MusicPlaybackReportTracker {
    private var active: ActiveReport? = null

    val activeItemId: String?
        get() = active?.itemId

    val activeEntryId: String?
        get() = active?.entryId

    fun transitionTo(
        itemId: String?,
        playSessionId: String,
        entryId: String? = null
    ): List<MusicPlaybackReportEvent> {
        val normalizedId = itemId?.takeIf(String::isNotBlank)
        val normalizedSessionId = playSessionId.takeIf(String::isNotBlank).orEmpty()
        val normalizedEntryId = normalizedId?.let {
            entryId?.takeIf(String::isNotBlank) ?: "$normalizedSessionId:$it"
        }
        val current = active
        if (
            current != null &&
            current.entryId == normalizedEntryId
        ) {
            return emptyList()
        }

        val events = mutableListOf<MusicPlaybackReportEvent>()
        current?.let {
            events += MusicPlaybackReportEvent.Stopped(
                it.itemId,
                it.playSessionId,
                it.positionTicks
            )
        }
        active = normalizedId?.let {
            ActiveReport(
                itemId = it,
                playSessionId = normalizedSessionId,
                entryId = requireNotNull(normalizedEntryId),
                positionTicks = 0L
            )
        }
        active?.let {
            events += MusicPlaybackReportEvent.Playing(
                it.itemId,
                it.playSessionId,
                it.positionTicks
            )
        }
        return events
    }

    fun progress(positionTicks: Long, isPaused: Boolean): List<MusicPlaybackReportEvent> {
        val current = active ?: return emptyList()
        val safePosition = positionTicks.coerceAtLeast(0L)
        active = current.copy(positionTicks = safePosition)
        return listOf(
            MusicPlaybackReportEvent.Progress(
                current.itemId,
                current.playSessionId,
                safePosition,
                isPaused
            )
        )
    }

    fun finish(positionTicks: Long? = null): List<MusicPlaybackReportEvent> {
        val current = active ?: return emptyList()
        active = null
        return listOf(
            MusicPlaybackReportEvent.Stopped(
                current.itemId,
                current.playSessionId,
                positionTicks?.coerceAtLeast(0L) ?: current.positionTicks
            )
        )
    }

    private data class ActiveReport(
        val itemId: String,
        val playSessionId: String,
        val entryId: String,
        val positionTicks: Long
    )
}
