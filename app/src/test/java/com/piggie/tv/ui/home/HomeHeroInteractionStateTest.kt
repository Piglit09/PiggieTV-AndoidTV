package com.piggie.tv.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeHeroInteractionStateTest {
    private enum class Action { PLAY, DETAILS }

    @Test
    fun coldHomeStartsExpandedWithPrimaryActionMemory() {
        val home = HomeHeroInteractionState(Action.PLAY)

        assertEquals(HomeHeroPresentation.EXPANDED, home.presentation)
        assertEquals(Action.PLAY, home.rememberedAction)
    }

    @Test
    fun heroDownCommitsCollapsedOnlyWhenFirstRowCanReceiveFocus() {
        val home = HomeHeroInteractionState(Action.PLAY)

        assertFalse(home.heroDown(firstRowAvailable = false))
        assertEquals(HomeHeroPresentation.EXPANDED, home.presentation)
        assertTrue(home.heroDown(firstRowAvailable = true))
        assertEquals(HomeHeroPresentation.COLLAPSED, home.presentation)
    }

    @Test
    fun firstRowUpExpandsAndRestoresMeaningfulHeroAction() {
        val home = HomeHeroInteractionState(Action.PLAY)
        home.rememberAction(Action.DETAILS)
        home.heroDown(firstRowAvailable = true)

        assertTrue(home.firstRowUp(heroAvailable = true))
        assertEquals(HomeHeroPresentation.EXPANDED, home.presentation)
        assertEquals(Action.DETAILS, home.rememberedAction)
    }

    @Test
    fun firstRowUpWithoutHeroActionKeepsBrowseFocusAndState() {
        val home = HomeHeroInteractionState(Action.PLAY)
        home.heroDown(firstRowAvailable = true)

        assertFalse(home.firstRowUp(heroAvailable = false))
        assertEquals(HomeHeroPresentation.COLLAPSED, home.presentation)
    }

    @Test
    fun lowerRowUpDoesNotExpandHero() {
        val home = HomeHeroInteractionState(Action.PLAY)
        home.heroDown(firstRowAvailable = true)

        assertFalse(home.lowerRowUp())
        assertEquals(HomeHeroPresentation.COLLAPSED, home.presentation)
    }

    @Test
    fun heroToNavigationAndDetailsBackPreserveExpandedAction() {
        val home = HomeHeroInteractionState(Action.PLAY)
        home.rememberAction(Action.DETAILS)

        // Navigation and in-host Details hide/show do not create a new Home session.
        assertEquals(HomeHeroPresentation.EXPANDED, home.presentation)
        assertEquals(Action.DETAILS, home.rememberedAction)
    }

    @Test
    fun rowDetailsBackAndRouteReentryRetainBrowseStateInSameSession() {
        val home = HomeHeroInteractionState(Action.PLAY)
        home.heroDown(firstRowAvailable = true)

        // Details and one-route round trips retain the Home fragment in the route cache.
        assertEquals(HomeHeroPresentation.COLLAPSED, home.presentation)
    }

    @Test
    fun rapidDownUpDownUpRetargetsToLastExplicitState() {
        val home = HomeHeroInteractionState(Action.PLAY)

        home.heroDown(firstRowAvailable = true)
        home.firstRowUp(heroAvailable = true)
        home.heroDown(firstRowAvailable = true)
        home.firstRowUp(heroAvailable = true)

        assertEquals(HomeHeroPresentation.EXPANDED, home.presentation)
        assertEquals(Action.PLAY, home.rememberedAction)
    }

    @Test
    fun expandedHeightUsesUsefulViewportAndCollapsedHeightKeepsNavClearance() {
        assertEquals(685, HomeHeroHeightPolicy.expanded(1_000, 100))
        assertEquals(324, HomeHeroHeightPolicy.collapsed(100, 224))
    }
}
