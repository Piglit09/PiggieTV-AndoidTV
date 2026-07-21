package com.piggie.tv.data.playback

import com.piggie.tv.data.models.MediaItem
import org.junit.Assert.*
import org.junit.Test

class MusicQueueManagerTest {

    private fun mockItem(id: String) = MediaItem(
        id = id, title = "T", type = "Audio", year = null, imageTag = null, 
        seriesName = null, episodeLabel = null, playbackPositionTicks = 0L, runtimeTicks = 0L
    )

    @Test
    fun testQueueNavigation() {
        val manager = MusicQueueManager()
        val items = listOf(mockItem("1"), mockItem("2"), mockItem("3"))
        
        manager.setQueue(items, 1)
        assertEquals("2", manager.getCurrentItem()?.id)
        
        assertEquals(2, manager.nextIndex())
        assertEquals(0, manager.previousIndex())
        
        manager.advance()
        assertEquals("3", manager.getCurrentItem()?.id)
        assertEquals(-1, manager.nextIndex())
    }

    @Test
    fun testRepeatAll() {
        val manager = MusicQueueManager()
        val items = listOf(mockItem("1"), mockItem("2"))
        
        manager.setQueue(items, 1)
        manager.repeatMode = MusicQueueManager.RepeatMode.ALL
        
        assertEquals(0, manager.nextIndex())
        manager.advance()
        assertEquals("1", manager.getCurrentItem()?.id)
        
        assertEquals(1, manager.previousIndex())
    }

    @Test
    fun testRepeatOne() {
        val manager = MusicQueueManager()
        val items = listOf(mockItem("1"), mockItem("2"))
        
        manager.setQueue(items, 0)
        manager.repeatMode = MusicQueueManager.RepeatMode.ONE
        
        assertEquals(0, manager.nextIndex())
        assertEquals(0, manager.previousIndex())
    }

    @Test
    fun testEmptyQueue() {
        val manager = MusicQueueManager()
        manager.setQueue(emptyList())
        assertNull(manager.getCurrentItem())
        assertEquals(-1, manager.nextIndex())
        assertEquals(-1, manager.previousIndex())
    }

    @Test
    fun testQueueItemsRetrieval() {
        val manager = MusicQueueManager()
        val items = listOf(mockItem("1"), mockItem("2"))
        manager.setQueue(items)
        assertEquals(2, manager.getItems().size)
    }

    @Test
    fun testCurrentIndexState() {
        val manager = MusicQueueManager()
        manager.setQueue(listOf(mockItem("1")), 0)
        assertEquals(0, manager.getCurrentIndex())
    }
}
