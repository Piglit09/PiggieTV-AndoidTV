package com.piggie.tv.data.playback

import com.piggie.tv.data.models.MediaItem
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeasonPlaybackQueuePolicyTest {
    @Test fun `play all preserves Jellyfin season order and rejects non episodes`() {
        val queue = SeasonPlaybackQueuePolicy.build(
            listOf(
                episode("s1e1"),
                episode("s1e2"),
                episode("duplicate", id = "s1e2"),
                episode("movie", id = "movie", type = "Movie"),
                episode("blank", id = "  ")
            ),
            SeasonPlaybackMode.PLAY_ALL
        )

        assertEquals(listOf("s1e1", "s1e2"), queue)
    }

    @Test fun `shuffle all contains every episode once and changes a multi item order`() {
        val source = listOf(episode("one"), episode("two"), episode("three"), episode("four"))

        val queue = SeasonPlaybackQueuePolicy.build(
            source,
            SeasonPlaybackMode.SHUFFLE_ALL,
            Random(17)
        )

        assertEquals(source.map { it.id }.toSet(), queue.toSet())
        assertEquals(source.size, queue.size)
        assertNotEquals(source.map { it.id }, queue)
    }

    @Test fun `explicit queue stops at season boundary`() {
        val queue = listOf("s1e1", "s1e2")

        assertEquals("s1e2", SeasonPlaybackQueuePolicy.nextId(queue, "s1e1"))
        assertNull(SeasonPlaybackQueuePolicy.nextId(queue, "s1e2"))
        assertNull(SeasonPlaybackQueuePolicy.nextId(queue, "not-in-this-season"))
    }

    private fun episode(
        title: String,
        id: String = title,
        type: String = "Episode"
    ) = MediaItem(
        id = id,
        title = title,
        type = type,
        year = null,
        imageTag = null,
        seriesName = "Series",
        episodeLabel = null,
        playbackPositionTicks = 0,
        runtimeTicks = 1
    )
}
