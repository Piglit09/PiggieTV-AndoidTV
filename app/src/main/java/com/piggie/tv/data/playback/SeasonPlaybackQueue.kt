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
}
