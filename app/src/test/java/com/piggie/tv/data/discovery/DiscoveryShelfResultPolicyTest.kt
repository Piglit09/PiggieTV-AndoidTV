package com.piggie.tv.data.discovery

import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryShelfResultPolicyTest {
    private val definition = ShelfDefinition(
        "home.test", DiscoveryShelfType.RECENTLY_ADDED, "Test", MediaCardPresentation.POSTER,
        listOf("Movie"), filter = DiscoveryFilter(DiscoveryFilterType.RECENTLY_ADDED), limit = 16
    )

    @Test fun threeEligibleItemsAreContentNotEmpty() {
        val result = DiscoveryShelfResultPolicy.prepare((1..3).map { item("$it") }, definition)
        assertEquals(3, result.rawCount)
        assertEquals(3, result.eligibleCount)
        assertEquals(3, result.items.size)
        assertEquals(DiscoveryFinalState.CONTENT, if (result.items.isEmpty()) DiscoveryFinalState.EMPTY else DiscoveryFinalState.CONTENT)
    }

    @Test fun invalidWrongTypeDuplicatesAndLimitHaveNamedSemantics() {
        val raw = (1..20).map { item("$it") } + item("1") + item("bad", type = "Series") + item("")
        val result = DiscoveryShelfResultPolicy.prepare(raw, definition)
        assertEquals(23, result.rawCount)
        assertEquals(21, result.eligibleCount)
        assertEquals(20, result.deduplicatedCount)
        assertEquals(16, result.items.size)
    }

    private fun item(id: String, type: String = "Movie") = MediaItem(
        id = id, title = if (id.isBlank()) "" else "Title $id", type = type, year = null,
        imageTag = null, playbackPositionTicks = 0, runtimeTicks = 0,
        seriesName = null, episodeLabel = null
    )
}
