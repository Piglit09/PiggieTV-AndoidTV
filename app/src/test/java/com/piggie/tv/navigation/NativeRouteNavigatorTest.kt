package com.piggie.tv.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRouteNavigatorTest {
    @Test
    fun backFromTopLevelReturnsToHome() {
        NativeRoute.entries.forEach {
            if (it != NativeRoute.HOME && it != NativeRoute.PROFILE) {
                assertEquals(NativeRoute.HOME, NativeRouteNavigator.backTarget(it))
            }
        }
    }

    @Test
    fun profileBackReturnsToSettingsAndKeepsSettingsSelected() {
        assertEquals(NativeRoute.SETTINGS, NativeRouteNavigator.backTarget(NativeRoute.PROFILE))
        assertEquals(NativeRoute.PROFILE, NativeRouteNavigator.restoreTarget("PROFILE"))
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
