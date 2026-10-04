package com.piggie.tv.theme

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.piggie.tv.R
import com.piggie.tv.navigation.NativeRailMaterialPolicy
import com.piggie.tv.navigation.NativeRoute
import com.piggie.tv.ui.rendering.RendererFeaturePolicy
import com.piggie.tv.ui.rendering.TvRenderingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PremiumMaterialTokensTest {
    @Test
    fun glassTiersHaveSemanticColorsAndIncreaseProtectionWithElevation() {
        val surfaceAlpha = (0..4).map { tier -> alpha("ptv_glass_${tier}_surface") }
        val fallbackAlpha = (0..4).map { tier -> alpha("ptv_glass_${tier}_fallback") }

        assertEquals(0, surfaceAlpha.first())
        assertEquals(0, fallbackAlpha.first())
        assertTrue("glass tiers should become more protective", surfaceAlpha.zipWithNext().all { (a, b) -> a < b })
        assertTrue("static fallbacks must never be less readable", (1..4).all { fallbackAlpha[it] >= surfaceAlpha[it] })
        assertTrue("light glass must leave artwork visible", surfaceAlpha[1] <= 96)
        assertTrue("modal glass must protect critical text", surfaceAlpha[4] >= 210)
    }

    @Test
    fun fireTvTierKeepsBlurAndContinuousEffectsOff() {
        val fire = RendererFeaturePolicy.fireTvPerformance

        assertTrue(fire.staticGlassEnabled)
        assertFalse(fire.blurEnabled)
        assertFalse(fire.animatedEffectsEnabled)
        assertFalse(fire.expensiveEffectsEnabled)
    }

    @Test
    fun compactNavigationHasLightTintAndAnExplicitStaticFireFallback() {
        val context = RuntimeEnvironment.getApplication()
        val rich = context.getDrawable(R.drawable.tv_nav_rail_premium) as GradientDrawable
        val fallbackId = context.resources.getIdentifier(
            "tv_nav_rail_performance", "drawable", context.packageName
        )

        assertTrue("Home rail should let the scene remain visible", Color.alpha(rich.color!!.defaultColor) <= 64)
        assertTrue("Fire TV needs a static readable rail", fallbackId != 0)
        val fallback = context.getDrawable(fallbackId) as GradientDrawable
        assertTrue("Fire TV rail should retain readable static translucency", Color.alpha(fallback.color!!.defaultColor) <= 120)
    }

    @Test
    fun premiumBrowsingAndSearchReuseTheAcceptedFloatingRailMaterial() {
        assertEquals(
            R.drawable.tv_nav_rail_premium,
            NativeRailMaterialPolicy.background(NativeRoute.HOME, TvRenderingProfile.RICH)
        )
        assertEquals(
            R.drawable.tv_nav_rail_performance,
            NativeRailMaterialPolicy.background(NativeRoute.HOME, TvRenderingProfile.FIRE_TV_PERFORMANCE)
        )
        for (route in listOf(NativeRoute.MOVIES, NativeRoute.SHOWS, NativeRoute.SEARCH)) {
            assertEquals(R.drawable.tv_nav_rail_premium, NativeRailMaterialPolicy.background(route, TvRenderingProfile.RICH))
            assertEquals(R.drawable.tv_nav_rail_performance, NativeRailMaterialPolicy.background(route, TvRenderingProfile.FIRE_TV_PERFORMANCE))
        }
        for (route in listOf(NativeRoute.MUSIC, NativeRoute.SETTINGS)) {
            assertEquals(R.drawable.tv_nav_rail, NativeRailMaterialPolicy.background(route, TvRenderingProfile.RICH))
            assertEquals(R.drawable.tv_nav_rail, NativeRailMaterialPolicy.background(route, TvRenderingProfile.FIRE_TV_PERFORMANCE))
        }

        assertEquals(R.drawable.tv_ice_card_surface, PTVShapes.mediaCardSurfaceResources(false, true, false).first)
        assertEquals(R.drawable.tv_ice_card_border, PTVShapes.mediaCardSurfaceResources(false, true, false).second)
        assertEquals(R.drawable.tv_ice_card_surface_premium, PTVShapes.mediaCardSurfaceResources(true, true, false).first)
        assertEquals(R.drawable.tv_ice_card_border_premium, PTVShapes.mediaCardSurfaceResources(true, true, false).second)
        assertEquals(R.drawable.tv_media_card_surface, PTVShapes.mediaCardSurfaceResources(false, false, false).first)
        assertEquals(R.drawable.tv_media_card_surface_premium, PTVShapes.mediaCardSurfaceResources(true, false, false).first)
    }

    @Test
    fun selectedPhaseTwoDestinationsUseTheAcceptedPremiumButtonMaterial() {
        assertEquals(
            R.drawable.tv_nav_button_premium,
            NativeRailMaterialPolicy.buttonBackground(NativeRoute.HOME)
        )
        for (route in listOf(
            NativeRoute.MOVIES,
            NativeRoute.SHOWS,
            NativeRoute.SEARCH
        )) {
            assertEquals(R.drawable.tv_nav_button_premium, NativeRailMaterialPolicy.buttonBackground(route))
        }
        for (route in listOf(NativeRoute.MUSIC, NativeRoute.SETTINGS)) {
            assertEquals(R.drawable.tv_nav_button, NativeRailMaterialPolicy.buttonBackground(route))
        }
    }

    @Test
    fun movieAndSeriesDetailsRetainPremiumRailAcrossRouteRestoration() {
        assertEquals(
            R.drawable.tv_nav_rail_premium,
            NativeRailMaterialPolicy.background(NativeRoute.MOVIES, TvRenderingProfile.RICH, "Movie")
        )
        assertEquals(
            R.drawable.tv_nav_rail_performance,
            NativeRailMaterialPolicy.background(NativeRoute.SHOWS, TvRenderingProfile.FIRE_TV_PERFORMANCE, "Series")
        )
        assertEquals(
            R.drawable.tv_nav_button_premium,
            NativeRailMaterialPolicy.buttonBackground(NativeRoute.MOVIES, "Movie")
        )
        assertEquals(R.drawable.tv_nav_rail_premium, NativeRailMaterialPolicy.background(NativeRoute.MOVIES, TvRenderingProfile.RICH, "Episode"))
        assertEquals(R.drawable.tv_nav_button_premium, NativeRailMaterialPolicy.buttonBackground(NativeRoute.SHOWS, "Season"))
        assertEquals(R.drawable.tv_nav_rail_premium, NativeRailMaterialPolicy.background(NativeRoute.MOVIES, TvRenderingProfile.RICH))
    }

    private fun alpha(name: String): Int {
        val context = RuntimeEnvironment.getApplication()
        val id = context.resources.getIdentifier(name, "color", context.packageName)
        assertTrue("missing material color $name", id != 0)
        return Color.alpha(context.getColor(id))
    }
}
