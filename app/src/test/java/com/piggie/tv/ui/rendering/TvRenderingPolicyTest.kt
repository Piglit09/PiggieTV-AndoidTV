package com.piggie.tv.ui.rendering

import com.piggie.tv.ui.player.DetailsNavigationPolicy
import com.piggie.tv.ui.player.ProgressiveDetailsState
import com.piggie.tv.ui.player.ProgressiveDetailsStateMachine
import com.piggie.tv.ui.shared.BackdropPolicy
import com.piggie.tv.ui.shared.BackdropPurpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvRenderingPolicyTest {
    @Test
    fun `browsing backdrops are disabled while Hero remains eligible`() {
        val features = RendererFeaturePolicy.fireTvPerformance
        assertFalse(BackdropPolicy.shouldUpdate(BackdropPurpose.BROWSING, features))
        assertTrue(BackdropPolicy.shouldUpdate(BackdropPurpose.HERO, features))
    }

    @Test
    fun `single focus overlay tracks and conditionally clears one card`() {
        val state = FocusOverlayState()
        val bounds = FocusOverlayBounds(1, 2, 30, 40)
        state.focus(7L, bounds)
        state.clear(8L)
        assertEquals(7L, state.focusedKey)
        assertEquals(bounds, state.bounds)
        state.clear(7L)
        assertNull(state.focusedKey)
        assertNull(state.bounds)
    }

    @Test
    fun `progressive details states only move forward`() {
        val state = ProgressiveDetailsStateMachine()
        assertTrue(state.advance(ProgressiveDetailsState.PRIMARY_INTERACTIVE))
        assertTrue(state.advance(ProgressiveDetailsState.CONTENT))
        assertFalse(state.advance(ProgressiveDetailsState.PRIMARY_INTERACTIVE))
        assertEquals(ProgressiveDetailsState.CONTENT, state.state)
    }

    @Test
    fun `details use host only when both policy and host allow it`() {
        val fire = RendererFeaturePolicy.fireTvPerformance
        assertEquals(DetailsArchitecture.IN_HOST_FRAGMENT, DetailsNavigationPolicy.architecture(fire, true))
        assertEquals(DetailsArchitecture.ACTIVITY, DetailsNavigationPolicy.architecture(fire, false))
        assertEquals(DetailsArchitecture.ACTIVITY, DetailsNavigationPolicy.architecture(RendererFeaturePolicy.rich, true))
    }

    @Test
    fun `prefetch policy is bounded for constrained hardware`() {
        val tuning = RecyclerRenderingPolicy.tuning(RendererFeaturePolicy.fireTvPerformance, 7)
        assertTrue(tuning.prefetchEnabled)
        assertEquals(1, tuning.initialPrefetchCount)
        assertEquals(2, tuning.itemViewCacheSize)
    }
}
