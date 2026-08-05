package com.piggie.tv.ui.player

import com.piggie.tv.data.models.MediaItem
import java.util.LinkedHashMap

/**
 * Bridges the short eventual-consistency window between a natural player completion and the
 * following Details refresh.
 *
 * The player reports its stop asynchronously so navigation is never held behind a network
 * timeout. During that handoff, Jellyfin can briefly return the previous resume position. Only
 * the two completion-owned UserData fields are protected here; all descriptive metadata remains
 * authoritative from the refreshed server item.
 */
object NaturalCompletionDetailsGuard {
    const val STALE_SERVER_WINDOW_MS = 2L * 60L * 1_000L

    private const val MAX_PENDING_ITEMS = 8

    private val pendingUntilByItemId = LinkedHashMap<String, Long>(
        MAX_PENDING_ITEMS,
        0.75f,
        true
    )

    /** Records a natural completion and returns the normalized seed to show immediately. */
    @Synchronized
    fun markCompleted(
        item: MediaItem,
        nowMs: Long = System.currentTimeMillis()
    ): MediaItem {
        val normalized = item.copy(
            playbackPositionTicks = 0L,
            isPlayed = true
        )
        val itemId = item.id.trim()
        if (itemId.isEmpty()) return normalized

        removeExpired(nowMs)
        pendingUntilByItemId[itemId] = nowMs + STALE_SERVER_WINDOW_MS
        while (pendingUntilByItemId.size > MAX_PENDING_ITEMS) {
            pendingUntilByItemId.entries.firstOrNull()?.key?.let(pendingUntilByItemId::remove)
        }
        return normalized
    }

    /**
     * Merges an item returned by Details. A server-acknowledged completion retires the guard;
     * otherwise the stale resume fields stay completed for the bounded handoff window.
     */
    @Synchronized
    fun mergeServerDetails(
        details: MediaItem,
        nowMs: Long = System.currentTimeMillis()
    ): MediaItem {
        removeExpired(nowMs)
        val itemId = details.id.trim()
        if (itemId.isEmpty() || pendingUntilByItemId[itemId] == null) return details

        if (details.isPlayed && details.playbackPositionTicks == 0L) {
            pendingUntilByItemId.remove(itemId)
            return details
        }
        return details.copy(
            playbackPositionTicks = 0L,
            isPlayed = true
        )
    }

    /** Clears the handoff when the user explicitly replays or changes played state. */
    @Synchronized
    fun clear(itemId: String) {
        pendingUntilByItemId.remove(itemId.trim())
    }

    private fun removeExpired(nowMs: Long) {
        val expired = pendingUntilByItemId
            .filterValues { expiresAtMs -> expiresAtMs <= nowMs }
            .keys
            .toList()
        expired.forEach(pendingUntilByItemId::remove)
    }
}
