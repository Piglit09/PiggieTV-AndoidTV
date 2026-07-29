package com.piggie.tv.data.playback

data class BoundedMusicQueue<T>(
    val items: List<T>,
    val startIndex: Int
)

/** Bounds retained DTOs and Media3 queue construction while keeping the selected track in view. */
object MusicQueuePolicy {
    const val MAX_QUEUE_ITEMS = 200

    fun <T> bound(items: List<T>, startIndex: Int): BoundedMusicQueue<T>? {
        if (items.isEmpty()) return null
        val selected = startIndex.coerceIn(items.indices)
        if (items.size <= MAX_QUEUE_ITEMS) {
            return BoundedMusicQueue(items.toList(), selected)
        }
        val windowStart = (selected - MAX_QUEUE_ITEMS / 2)
            .coerceIn(0, items.size - MAX_QUEUE_ITEMS)
        return BoundedMusicQueue(
            items = items.subList(windowStart, windowStart + MAX_QUEUE_ITEMS).toList(),
            startIndex = selected - windowStart
        )
    }
}
