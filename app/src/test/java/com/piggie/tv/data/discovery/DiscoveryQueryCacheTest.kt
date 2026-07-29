package com.piggie.tv.data.discovery

import com.piggie.tv.data.models.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DiscoveryQueryCacheTest {
    @Test
    fun duplicateConcurrentLoadsShareOneResult() {
        val cache = DiscoveryQueryCache(maxEntries = 4)
        val calls = AtomicInteger()
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        val loader = {
            calls.incrementAndGet()
            Thread.sleep(40)
            listOf(item("one"))
        }
        val first = workers.submit<DiscoveryCacheResult> {
            start.await()
            cache.getOrLoad("shared", loader)
        }
        val second = workers.submit<DiscoveryCacheResult> {
            start.await()
            cache.getOrLoad("shared", loader)
        }
        start.countDown()

        val results = listOf(
            first.get(2, TimeUnit.SECONDS),
            second.get(2, TimeUnit.SECONDS)
        )
        workers.shutdownNow()

        assertEquals(1, calls.get())
        assertEquals(1, results.count { it.hit })
        assertEquals(1, results.count { !it.hit })
        assertEquals(listOf("one"), results.flatMap { it.items }.map { it.id }.distinct())
    }

    @Test
    fun clearRemovesCachedValues() {
        val cache = DiscoveryQueryCache(maxEntries = 2)
        assertFalse(cache.getOrLoad("key") { listOf(item("first")) }.hit)
        assertTrue(cache.getOrLoad("key") { listOf(item("second")) }.hit)
        cache.clear()
        assertFalse(cache.getOrLoad("key") { listOf(item("second")) }.hit)
    }

    private fun item(id: String) = MediaItem(
        id = id,
        title = id,
        type = "Movie",
        year = null,
        imageTag = null,
        seriesName = null,
        episodeLabel = null,
        playbackPositionTicks = 0,
        runtimeTicks = 0
    )
}
