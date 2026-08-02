package com.piggie.tv.navigation

import org.junit.Assert.*
import org.junit.Test

class RouteValidationTest {

    @Test
    fun testRouteLabels() {
        assertEquals("Search", NativeRoute.SEARCH.label)
        assertFalse(NativeRoute.entries.any { it.name == "READING" || it.label == "Reading" })
    }

    @Test
    fun testNavigationBackTargetExhaustiveness() {
        for (route in NativeRoute.entries) {
            val target = NativeRouteNavigator.backTarget(route)
            if (route == NativeRoute.HOME) {
                assertNull(target)
            } else {
                assertEquals(NativeRoute.HOME, target)
            }
        }
    }

    @Test
    fun removedOrUnknownSavedRoutesRestoreHome() {
        assertEquals(NativeRoute.HOME, NativeRouteNavigator.restoreTarget("READING"))
        assertEquals(NativeRoute.HOME, NativeRouteNavigator.restoreTarget("UNKNOWN"))
        assertEquals(NativeRoute.SEARCH, NativeRouteNavigator.restoreTarget("SEARCH"))
    }

    @Test
    fun coldStartNeverTreatsTwoMissingFragmentsAsAnExistingRoute() {
        assertFalse(
            NativeRouteNavigator.canRefreshVisibleRoute(
                current = NativeRoute.HOME,
                target = NativeRoute.HOME,
                detailsOpen = false,
                visibleFragmentPresent = false,
                visibleTagMatches = false,
                cachedFragmentMatchesVisible = true
            )
        )
        assertTrue(
            NativeRouteNavigator.canRefreshVisibleRoute(
                current = NativeRoute.HOME,
                target = NativeRoute.HOME,
                detailsOpen = false,
                visibleFragmentPresent = true,
                visibleTagMatches = true,
                cachedFragmentMatchesVisible = false
            )
        )
    }

    @Test
    fun evictionRetainsTargetAndOutgoingRouteEvenWhenOutgoingIsLeastRecentlyUsed() {
        assertEquals(
            NativeRoute.MOVIES,
            NativeRouteNavigator.evictionCandidate(
                lruRoutes = listOf(NativeRoute.HOME, NativeRoute.MOVIES, NativeRoute.SHOWS),
                target = NativeRoute.SHOWS,
                outgoing = NativeRoute.HOME
            )
        )
    }

    @Test
    fun evictionUsesLeastRecentRouteOutsideTheNormalPreviousAndTargetPair() {
        assertEquals(
            NativeRoute.HOME,
            NativeRouteNavigator.evictionCandidate(
                lruRoutes = listOf(NativeRoute.HOME, NativeRoute.MOVIES, NativeRoute.SHOWS),
                target = NativeRoute.SHOWS,
                outgoing = NativeRoute.MOVIES
            )
        )
    }
}
