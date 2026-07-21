package com.piggie.tv.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRouteNavigatorTest {
    @Test
    fun backFromTopLevelReturnsToHome() {
        NativeRoute.entries.forEach {
            if (it != NativeRoute.HOME) {
                assertEquals(NativeRoute.HOME, NativeRouteNavigator.backTarget(it))
            }
        }
    }

    @Test
    fun backFromHomeReturnsNull() {
        assertEquals(null, NativeRouteNavigator.backTarget(NativeRoute.HOME))
    }

    @Test
    fun testAllRoutesHaveLabels() {
        for (route in NativeRoute.entries) {
            assertTrue(route.label.isNotEmpty())
        }
    }

    @Test
    fun testSettingsNavigation() {
        assertEquals(NativeRoute.HOME, NativeRouteNavigator.backTarget(NativeRoute.SETTINGS))
    }
}
