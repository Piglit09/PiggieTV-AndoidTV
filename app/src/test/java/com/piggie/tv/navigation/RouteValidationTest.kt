package com.piggie.tv.navigation

import org.junit.Assert.*
import org.junit.Test

class RouteValidationTest {

    @Test
    fun testRouteLabels() {
        assertEquals("Reading", NativeRoute.READING.label)
        assertEquals("Search", NativeRoute.SEARCH.label)
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
}
