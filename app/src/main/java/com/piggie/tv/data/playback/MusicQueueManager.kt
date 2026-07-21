package com.piggie.tv.data.playback

import com.piggie.tv.data.models.MediaItem

class MusicQueueManager {
    private val queue = mutableListOf<MediaItem>()
    private var currentIndex = -1
    var shuffle = false
    var repeatMode = RepeatMode.NONE

    enum class RepeatMode { NONE, ALL, ONE }

    fun setQueue(items: List<MediaItem>, startIndex: Int = 0) {
        queue.clear()
        queue.addAll(items)
        currentIndex = if (items.isNotEmpty()) startIndex.coerceIn(0, items.lastIndex) else -1
    }

    fun getCurrentItem(): MediaItem? = if (currentIndex in queue.indices) queue[currentIndex] else null

    fun nextIndex(): Int {
        if (queue.isEmpty()) return -1
        if (repeatMode == RepeatMode.ONE) return currentIndex
        
        val next = currentIndex + 1
        return if (next < queue.size) {
            next
        } else {
            if (repeatMode == RepeatMode.ALL) 0 else -1
        }
    }

    fun previousIndex(): Int {
        if (queue.isEmpty()) return -1
        if (repeatMode == RepeatMode.ONE) return currentIndex
        
        val prev = currentIndex - 1
        return if (prev >= 0) {
            prev
        } else {
            if (repeatMode == RepeatMode.ALL) queue.lastIndex else -1
        }
    }

    fun advance() {
        val next = nextIndex()
        if (next != -1) currentIndex = next
    }

    fun rewind() {
        val prev = previousIndex()
        if (prev != -1) currentIndex = prev
    }

    fun getItems(): List<MediaItem> = queue.toList()
    fun getCurrentIndex(): Int = currentIndex
}
