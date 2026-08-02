package com.piggie.tv.ui.discovery

import com.piggie.tv.data.discovery.DiscoveryFilter
import com.piggie.tv.data.discovery.DiscoveryFilterType
import com.piggie.tv.data.discovery.DiscoveryPage
import com.piggie.tv.data.discovery.DiscoveryShelf
import com.piggie.tv.data.discovery.DiscoveryShelfType
import com.piggie.tv.data.discovery.ShelfDefinition
import com.piggie.tv.data.discovery.ShelfDiagnostic
import com.piggie.tv.data.discovery.ShelfStatus
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryShelfUiContentPolicyTest {
    @Test
    fun diagnosticsOnlyGenerationChangeDoesNotRebindShelf() {
        val previous = shelf(generation = 1)
        val refreshed = previous.copy(
            diagnostic = previous.diagnostic.copy(
                generationId = 2,
                cacheHit = true,
                elapsedMs = 0
            )
        )

        assertTrue(DiscoveryShelfUiContentPolicy.matches(previous, refreshed))
    }

    @Test
    fun visibleContentOrTerminalStateChangeDoesRebindShelf() {
        val previous = shelf(generation = 1)

        assertFalse(
            DiscoveryShelfUiContentPolicy.matches(
                previous,
                previous.copy(items = previous.items + item("two"))
            )
        )
        assertFalse(
            DiscoveryShelfUiContentPolicy.matches(
                previous,
                previous.copy(status = ShelfStatus.TIMEOUT, message = "Timed out")
            )
        )
    }

    private fun shelf(generation: Long): DiscoveryShelf {
        val definition = ShelfDefinition(
            id = "home.test",
            type = DiscoveryShelfType.RECENTLY_ADDED,
            title = "Test shelf",
            presentation = MediaCardPresentation.POSTER,
            filter = DiscoveryFilter(DiscoveryFilterType.RECENTLY_ADDED)
        )
        val diagnostic = ShelfDiagnostic(
            shelfId = definition.id,
            shelfTitle = definition.title,
            page = DiscoveryPage.HOME,
            status = ShelfStatus.READY,
            query = null,
            resultCount = 1,
            deduplicatedCount = 1,
            renderCount = 1,
            elapsedMs = 10,
            httpMs = 10,
            httpStatus = 200,
            cacheHit = false,
            retryCount = 0,
            generationId = generation
        )
        return DiscoveryShelf(definition, listOf(item("one")), ShelfStatus.READY, diagnostic)
    }

    private fun item(id: String) = MediaItem(
        id = id,
        title = id,
        type = "Movie",
        year = "2026",
        imageTag = "image-$id",
        seriesName = null,
        episodeLabel = null,
        playbackPositionTicks = 0,
        runtimeTicks = 1
    )
}
