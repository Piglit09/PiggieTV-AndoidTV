package com.piggie.tv.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicQueuePolicyTest {
    @Test
    fun emptyQueueDoesNotProducePlaybackWork() {
        assertNull(MusicQueuePolicy.bound(emptyList<String>(), 0))
    }

    @Test
    fun oversizedQueueIsBoundedAndRetainsSelectedTrack() {
        val source = (0 until 500).toList()
        val bounded = requireNotNull(MusicQueuePolicy.bound(source, 430))

        assertEquals(MusicQueuePolicy.MAX_QUEUE_ITEMS, bounded.items.size)
        assertEquals(430, bounded.items[bounded.startIndex])
        assertTrue(bounded.startIndex in bounded.items.indices)
    }

    @Test
    fun smallQueuePreservesOrderAndClampsInvalidIndex() {
        val bounded = requireNotNull(
            MusicQueuePolicy.bound(listOf("a", "b", "c"), startIndex = 99)
        )

        assertEquals(listOf("a", "b", "c"), bounded.items)
        assertEquals(2, bounded.startIndex)
    }
}
