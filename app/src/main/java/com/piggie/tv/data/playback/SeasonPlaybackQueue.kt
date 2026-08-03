package com.piggie.tv.data.playback

import com.piggie.tv.data.models.MediaItem
import kotlin.random.Random

enum class SeasonPlaybackMode {
    PLAY_ALL,
    SHUFFLE_ALL
}

/** Builds and advances the explicit queue requested from a season's episode shelf. */
object SeasonPlaybackQueuePolicy {
    fun build(
        episodes: List<MediaItem>,
        mode: SeasonPlaybackMode,
        random: Random = Random.Default
    ): List<String> {
        val episodeIds = episodes
            .asSequence()
            .filter { it.type.equals("Episode", ignoreCase = true) }
            .map { it.id.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (mode == SeasonPlaybackMode.PLAY_ALL || episodeIds.size < 2) return episodeIds

        val shuffled = episodeIds.shuffled(random)
        // A shuffle action should produce an observable order change when there is a choice.
        return if (shuffled == episodeIds) shuffled.drop(1) + shuffled.first() else shuffled
    }

    fun nextId(queue: List<String>, currentItemId: String): String? {
        val currentIndex = queue.indexOf(currentItemId)
        if (currentIndex < 0) return null
        return queue.getOrNull(currentIndex + 1)
    }

    /**
     * Resolves only the next item named by the explicit season queue. A hydrated server result
     * wins, but the launch-time episode remains a safe fallback when metadata prefetch times out.
     */
    fun resolveNext(
        queue: List<String>,
        currentItemId: String,
        hydratedItem: MediaItem?,
        launchItems: Map<String, MediaItem>
    ): MediaItem? {
        val expectedId = nextId(queue, currentItemId) ?: return null
        return hydratedItem
            ?.takeIf { it.id == expectedId && it.type.equals("Episode", ignoreCase = true) }
            ?: launchItems[expectedId]
                ?.takeIf { it.id == expectedId && it.type.equals("Episode", ignoreCase = true) }
    }
}

/**
 * Process-local handoff for the episode DTOs already loaded by the season details screen.
 * Only one season can own the video player, so replacing one immutable snapshot bounds memory
 * while the queue IDs in the Intent remain the authority after process recreation.
 */
object SeasonPlaybackQueueHandoff {
    private data class Snapshot(
        val queue: List<String>,
        val items: Map<String, MediaItem>
    )

    private var snapshot = Snapshot(emptyList(), emptyMap())

    @Synchronized
    fun publish(queue: List<String>, episodes: List<MediaItem>) {
        val queueCopy = queue.toList()
        val allowedIds = queueCopy.toHashSet()
        val items = episodes.asSequence()
            .filter { it.type.equals("Episode", ignoreCase = true) }
            .filter { it.id in allowedIds }
            .distinctBy(MediaItem::id)
            .associateBy(MediaItem::id)
        snapshot = Snapshot(queueCopy, items)
    }

    @Synchronized
    fun itemsFor(queue: List<String>): Map<String, MediaItem> =
        if (snapshot.queue == queue) snapshot.items.toMap() else emptyMap()

    @Synchronized
    fun clear() {
        snapshot = Snapshot(emptyList(), emptyMap())
    }
}
