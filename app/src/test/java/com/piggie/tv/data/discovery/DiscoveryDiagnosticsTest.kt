package com.piggie.tv.data.discovery

import com.piggie.tv.data.models.MediaCardPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryDiagnosticsTest {
    private val definitions = (1..8).map { index ->
        ShelfDefinition(
            id = "home.$index",
            type = DiscoveryShelfType.RECENTLY_ADDED,
            title = "Shelf $index",
            presentation = MediaCardPresentation.POSTER,
            itemTypes = listOf("Movie"),
            filter = DiscoveryFilter(DiscoveryFilterType.RECENTLY_ADDED)
        )
    }
    private val manifest = PageManifest(DiscoveryPage.HOME, 8, definitions)

    @Test
    fun allEightContentShelvesCountExactlyOnce() {
        val samples = definitions.map { sample(it.id, 4, DiscoveryFinalState.CONTENT, adapter = 3) }
        val value = DiscoveryDiagnostics.aggregate(manifest, 4, samples)
        assertEquals(8, value.content)
        assertEquals(8, value.renderedShelves)
        assertEquals(24, value.renderedCards)
    }

    @Test
    fun mixedTerminalStatesSeparateFailureAndCancellation() {
        val states = listOf(
            DiscoveryFinalState.CONTENT, DiscoveryFinalState.CONTENT,
            DiscoveryFinalState.EMPTY, DiscoveryFinalState.TIMEOUT,
            DiscoveryFinalState.HTTP_ERROR, DiscoveryFinalState.MISSING_LIBRARY,
            DiscoveryFinalState.CANCELED_HIDDEN_ROUTE, DiscoveryFinalState.QUEUED
        )
        val value = DiscoveryDiagnostics.aggregate(
            manifest, 7, definitions.zip(states).map { (definition, state) -> sample(definition.id, 7, state) }
        )
        assertEquals(2, value.content)
        assertEquals(1, value.empty)
        assertEquals(3, value.failed)
        assertEquals(1, value.canceled)
        assertEquals(1, value.queued)
    }

    @Test
    fun latestRetryReplacesTimeoutAndOldGenerationIsIgnored() {
        val id = definitions.first().id
        val samples = listOf(
            sample(id, 8, DiscoveryFinalState.CONTENT, attempt = 9),
            sample(id, 9, DiscoveryFinalState.TIMEOUT, attempt = 0),
            sample(id, 9, DiscoveryFinalState.CONTENT, attempt = 1, adapter = 5)
        )
        val value = DiscoveryDiagnostics.aggregate(manifest, 9, samples)
        assertEquals(1, value.content)
        assertEquals(0, value.failed)
        assertEquals(5, value.renderedCards)
    }

    @Test
    fun cachedCardsRemainRepresentedWhenNewerRefreshAttemptFails() {
        val id = definitions.first().id
        val samples = listOf(
            sample(id, 10, DiscoveryFinalState.CONTENT, attempt = 0, adapter = 4, cacheHit = true),
            sample(id, 10, DiscoveryFinalState.TIMEOUT, attempt = 1)
        )
        val aggregate = DiscoveryDiagnostics.aggregate(manifest, 10, samples, mapOf(id to 4))
        assertEquals(1, aggregate.failed)
        assertEquals(0, aggregate.content)
        assertEquals(1, aggregate.renderedShelves)
        assertEquals(4, aggregate.renderedCards)
    }

    @Test
    fun classificationsPartitionManifestAndRetryLoadingReplacesPriorTimeout() {
        val id = definitions.first().id
        val retryLoading = sample(id, 11, DiscoveryFinalState.LOADING, attempt = 1).copy(
            networkRequestStarted = true
        )
        val aggregate = DiscoveryDiagnostics.aggregate(
            manifest,
            11,
            listOf(sample(id, 11, DiscoveryFinalState.TIMEOUT), retryLoading)
        )
        assertEquals(1, aggregate.loading)
        assertEquals(0, aggregate.failed)
        assertEquals(1, aggregate.requested)
        assertEquals(
            aggregate.expected,
            aggregate.notStarted + aggregate.queued + aggregate.loading + aggregate.content +
                aggregate.empty + aggregate.failed + aggregate.canceled
        )
    }

    @Test
    fun cancellationBeforeNetworkStartIsCanceledButNotRequested() {
        val canceled = sample(definitions.first().id, 12, DiscoveryFinalState.CANCELED_HIDDEN_ROUTE)
            .copy(networkRequestStarted = false)
        val aggregate = DiscoveryDiagnostics.aggregate(manifest, 12, listOf(canceled))
        assertEquals(1, aggregate.canceled)
        assertEquals(0, aggregate.requested)
    }

    @Test
    fun manifestFormattingUsesSafeUnicodeSeparatorAndNamedCounts() {
        val aggregate = DiscoveryDiagnostics.aggregate(manifest, 1, emptyList())
        val formatted = DiscoveryDiagnostics.formatManifest(aggregate)
        assertFalse(formatted.contains("Ã"))
        assertEquals(11, formatted.count { it == '\u2022' })
        assertTrue(formatted.contains("not started 8"))
        val counts = DiscoveryDiagnostics.formatCounts(sample(definitions.first().id, 1, DiscoveryFinalState.CONTENT))
        assertEquals("raw=3 eligible=3 deduped=3 adapter=0 visible=0", counts)
    }

    private fun sample(
        shelfId: String,
        generation: Long,
        state: DiscoveryFinalState,
        attempt: Int = 0,
        adapter: Int = 0,
        cacheHit: Boolean = false
    ) = ShelfDiagnostic(
        shelfId = shelfId,
        shelfTitle = shelfId,
        page = DiscoveryPage.HOME,
        status = if (state == DiscoveryFinalState.CONTENT) ShelfStatus.READY else ShelfStatus.LOADING,
        query = null,
        resultCount = 3,
        deduplicatedCount = 3,
        renderCount = adapter,
        filteredCount = 3,
        elapsedMs = 1,
        httpMs = 1,
        httpStatus = 200,
        cacheHit = cacheHit,
        retryCount = attempt,
        finalState = state,
        generationId = generation,
        attemptId = attempt,
        adapterPresent = adapter > 0
        ,networkRequestStarted = !cacheHit
    )
}
