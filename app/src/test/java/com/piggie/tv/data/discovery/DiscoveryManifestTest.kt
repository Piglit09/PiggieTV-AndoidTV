package com.piggie.tv.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryManifestTest {
    @Test
    fun everyDiscoveryPageHasExactlyEightUniqueShelves() {
        DiscoveryPage.entries.forEach { page ->
            val manifest = DiscoveryManager.manifest(page)
            assertEquals(page, manifest.page)
            assertEquals(8, manifest.expectedShelves)
            assertEquals(8, manifest.shelves.size)
            assertEquals(8, manifest.shelves.map { it.id }.distinct().size)
        }
    }

    @Test
    fun moviesDefinesTwoGenresAndTwoStudios() {
        val shelves = DiscoveryManager.manifest(DiscoveryPage.MOVIES).shelves
        assertEquals(2, shelves.count { it.type == DiscoveryShelfType.RANDOM_GENRE })
        assertEquals(2, shelves.count { it.type == DiscoveryShelfType.RANDOM_STUDIO })
    }

    @Test
    fun manifestsUseStableIdsAndPriorityOrderInputs() {
        val all = DiscoveryPage.entries.flatMap { DiscoveryManager.manifest(it).shelves }
        assertTrue(all.all { it.id.contains('.') })
        assertTrue(all.all { it.priority >= 0 })
        assertFalse(all.any { it.filter.value == "Random" })
    }
}
