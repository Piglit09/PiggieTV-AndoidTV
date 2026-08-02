package com.piggie.tv.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPressurePolicyTest {
    @Test
    fun runningLowTrimsCachesAndInactiveRoutesWithoutDroppingSession() {
        val actions = MemoryPressurePolicy.actions(MemoryPressurePolicy.RUNNING_LOW)

        assertEquals(25, actions.discoveryCachePercent)
        assertTrue(actions.clearImageMemoryCache)
        assertTrue(actions.releaseInactiveRoutes)
        assertFalse(actions.releaseDiscoverySession)
        assertFalse(actions.releaseCurrentScreenContent)
    }

    @Test
    fun runningCriticalEvictsCachesButRetainsCurrentScreenAndSession() {
        val actions = MemoryPressurePolicy.actions(MemoryPressurePolicy.RUNNING_CRITICAL)

        assertEquals(0, actions.discoveryCachePercent)
        assertTrue(actions.clearImageMemoryCache)
        assertTrue(actions.releaseInactiveRoutes)
        assertFalse(actions.releaseDiscoverySession)
        assertFalse(actions.releaseCurrentScreenContent)
    }

    @Test
    fun hiddenProcessPreservesCompactShelfMetadataForFastReturn() {
        val actions = MemoryPressurePolicy.actions(MemoryPressurePolicy.UI_HIDDEN)

        assertEquals(25, actions.discoveryCachePercent)
        assertTrue(actions.clearImageMemoryCache)
        assertTrue(actions.releaseInactiveRoutes)
        assertFalse(actions.releaseDiscoverySession)
    }

    @Test
    fun backgroundProcessKeepsQuarterCacheButDropsDiscoverySessionFacets() {
        val actions = MemoryPressurePolicy.actions(MemoryPressurePolicy.BACKGROUND)

        assertEquals(25, actions.discoveryCachePercent)
        assertTrue(actions.releaseDiscoverySession)
        assertFalse(actions.releaseCurrentScreenContent)
    }

    @Test
    fun completePressureReleasesCurrentScreenForLifecycleRehydration() {
        val actions = MemoryPressurePolicy.actions(MemoryPressurePolicy.COMPLETE)

        assertEquals(0, actions.discoveryCachePercent)
        assertTrue(actions.releaseDiscoverySession)
        assertTrue(actions.releaseCurrentScreenContent)
    }

    @Test
    fun unrecognizedLowerLevelDoesNoWork() {
        assertFalse(MemoryPressurePolicy.actions(0).hasWork)
    }
}
