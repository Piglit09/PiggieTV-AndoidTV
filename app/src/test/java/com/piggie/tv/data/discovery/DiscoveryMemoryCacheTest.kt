package com.piggie.tv.data.discovery

import com.piggie.tv.data.models.MediaCardPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class DiscoveryMemoryCacheTest {
    @Test
    fun hitMissTtlAndByteEvictionAreBounded() {
        val cache = DiscoveryMemoryCache(maxEntries = 3, maxBytes = 10, ttlMs = 100)
        val shelf = shelf("one")
        assertNull(cache.get("server|user|one", 0))
        cache.put("server|user|one", shelf, estimatedBytes = 6, nowMs = 10)
        assertEquals(40L, cache.get("server|user|one", 50)?.ageMs)
        cache.put("server|user|two", shelf("two"), estimatedBytes = 6, nowMs = 50)
        assertNull(cache.get("server|user|one", 50))
        assertEquals(1, cache.stats().entries)
        assertNull(cache.get("server|user|two", 151))
    }

    @Test
    fun exactTtlBoundaryExpiresAndClockIsInjectable() {
        var now = 10L
        val cache = DiscoveryMemoryCache(ttlMs = 100, clockMs = { now })
        cache.put("key", shelf("one"), 1)
        now = 109
        assertTrue(cache.get("key") != null)
        now = 110
        assertNull(cache.get("key"))
    }

    @Test
    fun serverAndUserKeysRemainIsolatedAndRouteReturnHits() {
        val cache = DiscoveryMemoryCache()
        val shelf = shelf("home")
        cache.put("server-a|user-a|home", shelf, 1, 100)
        assertSame(shelf, cache.get("server-a|user-a|home", 101)?.entry?.shelf)
        assertNull(cache.get("server-a|user-b|home", 101))
        assertNull(cache.get("server-b|user-a|home", 101))
    }

    @Test
    fun concurrentConsumersCoalesceOneInFlightValue() {
        val cache = DiscoveryMemoryCache()
        val owner = cache.acquireFlight("key")
        val consumer = cache.acquireFlight("key")
        assertTrue(owner.owner)
        assertFalse(consumer.owner)
        val shelf = shelf("shared")
        cache.completeFlight("key", shelf)
        assertSame(shelf, consumer.future.get())
        assertEquals(0, cache.stats().inFlight)
    }

    @Test
    fun clearingCacheCancelsAndRemovesInFlightValues() {
        val cache = DiscoveryMemoryCache()
        val flight = cache.acquireFlight("key")
        cache.clear()
        assertEquals(0, cache.stats().inFlight)
        assertThrows(java.util.concurrent.CancellationException::class.java) { flight.future.get() }
    }

    @Test
    fun memoryTrimEvictsLruEntriesButDoesNotCancelActiveRequests() {
        val cache = DiscoveryMemoryCache(maxEntries = 4, maxBytes = 100, ttlMs = 1_000)
        cache.put("one", shelf("one"), 20, 0)
        cache.put("two", shelf("two"), 20, 0)
        cache.put("three", shelf("three"), 20, 0)
        cache.put("four", shelf("four"), 20, 0)
        assertTrue(cache.get("one", 1) != null) // "two" is now the least-recently used entry.
        val flight = cache.acquireFlight("loading")

        val stats = cache.trimToPercent(50, nowMs = 2)

        assertEquals(2, stats.entries)
        assertEquals(40L, stats.estimatedBytes)
        assertEquals(1, stats.inFlight)
        assertFalse(flight.future.isDone)
        assertNull(cache.get("two", 2))
        assertNull(cache.get("three", 2))
        assertTrue(cache.get("four", 2) != null)
        assertTrue(cache.get("one", 2) != null)
    }

    @Test
    fun criticalTrimEvictsEntriesWithoutCancelingFlight() {
        val cache = DiscoveryMemoryCache(maxEntries = 2, maxBytes = 100)
        cache.put("one", shelf("one"), 20, 0)
        val flight = cache.acquireFlight("loading")

        val stats = cache.trimToPercent(0, nowMs = 1)

        assertEquals(0, stats.entries)
        assertEquals(0L, stats.estimatedBytes)
        assertEquals(1, stats.inFlight)
        assertFalse(flight.future.isDone)
    }

    private fun shelf(id: String): DiscoveryShelf {
        val definition = ShelfDefinition(
            id, DiscoveryShelfType.RECENTLY_ADDED, id, MediaCardPresentation.POSTER,
            listOf("Movie"), filter = DiscoveryFilter(DiscoveryFilterType.RECENTLY_ADDED)
        )
        return DiscoveryShelf(
            definition, emptyList(), ShelfStatus.EMPTY,
            ShelfDiagnostic(id, id, DiscoveryPage.HOME, ShelfStatus.EMPTY, null, 0, 0, 0, elapsedMs = 0, httpMs = 0, httpStatus = 200, cacheHit = false, retryCount = 0)
        )
    }
}
