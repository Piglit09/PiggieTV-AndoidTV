package com.piggie.tv.data.playback

import java.util.LinkedHashMap

enum class SubtitleSelectionMode {
    DEFAULT,
    OFF,
    TRACK
}

data class PendingPlaybackPreference(
    val audioIndex: Int? = null,
    val subtitleIndex: Int? = null,
    val subtitleMode: SubtitleSelectionMode = SubtitleSelectionMode.DEFAULT
)

/**
 * Per-item, in-memory selections made on Details. Entries are intentionally bounded and are
 * consumed by the player without becoming an accidental global language preference.
 */
object PendingPlaybackPreferences {
    private const val LIMIT = 32
    private val entries = object : LinkedHashMap<String, PendingPlaybackPreference>(
        LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, PendingPlaybackPreference>?
        ): Boolean = size > LIMIT
    }

    @Synchronized
    fun get(itemId: String): PendingPlaybackPreference =
        entries[itemId] ?: PendingPlaybackPreference()

    @Synchronized
    fun setAudio(itemId: String, index: Int?) {
        entries[itemId] = get(itemId).copy(audioIndex = index)
    }

    @Synchronized
    fun setSubtitle(
        itemId: String,
        mode: SubtitleSelectionMode,
        index: Int? = null
    ) {
        entries[itemId] = get(itemId).copy(
            subtitleIndex = index.takeIf { mode == SubtitleSelectionMode.TRACK },
            subtitleMode = mode
        )
    }

    @Synchronized
    fun clear(itemId: String) {
        entries.remove(itemId)
    }

    @Synchronized
    internal fun clearAllForTests() {
        entries.clear()
    }
}

