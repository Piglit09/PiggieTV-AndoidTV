package com.piggie.tv.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryManifestTest {
    @Test
    fun eachDiscoveryPageHasItsOwnUniqueManifest() {
        DiscoveryPage.entries.forEach { page ->
            val manifest = DiscoveryManager.manifest(page)
            assertEquals(page, manifest.page)
            val expected = when (page) {
                DiscoveryPage.HOME -> 8
                DiscoveryPage.MOVIES -> 2
                else -> 1
            }
            assertEquals(expected, manifest.expectedShelves)
            assertEquals(expected, manifest.shelves.size)
            assertEquals(expected, manifest.shelves.map { it.id }.distinct().size)
        }
    }

    @Test
    fun moviesUsesOnlyItsLibraryRoot() {
        val shelves = DiscoveryManager.manifest(DiscoveryPage.MOVIES).shelves
        assertEquals(2, shelves.size)
        assertTrue(shelves.all { it.type == DiscoveryShelfType.LIBRARY_SPECIFIC })
        assertTrue(shelves.all { it.libraryName == "Movies" })
    }

    @Test
    fun manifestsUseStableIdsAndPriorityOrderInputs() {
        val all = DiscoveryPage.entries.flatMap { DiscoveryManager.manifest(it).shelves }
        assertTrue(all.all { it.id.contains('.') })
        assertTrue(all.all { it.priority >= 0 })
        assertFalse(all.any { it.filter.value == "Random" })
    }
}
