package com.piggie.tv.ui.hero

import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.ui.layout.TvCompactTargets
import com.piggie.tv.ui.rendering.TvRenderingProfile
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-xhdpi")
class HeroCompactLayoutTest {
    @Test
    fun compactProfileHeroResourceIsExactly215Dp() {
        val resources = RuntimeEnvironment.getApplication().resources
        val px = resources.getDimensionPixelSize(R.dimen.tv_hero_height)
        assertEquals(430, px)
        assertEquals(TvCompactTargets.HERO_HEIGHT_DP, (px / resources.displayMetrics.density).roundToInt())
        assertEquals("tv_hero_height", resources.getResourceEntryName(R.dimen.tv_hero_height))
    }

    @Test
    fun discoveryWrapperAndHeroMeasureToExactly430PxWithoutVerticalSpacing() {
        val context = RuntimeEnvironment.getApplication()
        val height = context.resources.getDimensionPixelSize(R.dimen.tv_hero_height)
        val wrapper = FrameLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(1_880, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val hero = View(context)

        HeroRowLayoutContract.bind(wrapper, hero, height)

        assertEquals(height, wrapper.minimumHeight)
        assertEquals(height, wrapper.layoutParams.height)
        assertEquals(height, hero.layoutParams.height)
        wrapper.measure(
            View.MeasureSpec.makeMeasureSpec(1_880, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        wrapper.layout(0, 0, 1_880, height)

        assertEquals(430, wrapper.measuredHeight)
        assertEquals(430, hero.measuredHeight)
        assertEquals(0, wrapper.paddingTop)
        assertEquals(0, wrapper.paddingBottom)
        val wrapperMargins = wrapper.layoutParams as ViewGroup.MarginLayoutParams
        val heroMargins = hero.layoutParams as ViewGroup.MarginLayoutParams
        assertEquals(0, wrapperMargins.topMargin)
        assertEquals(0, wrapperMargins.bottomMargin)
        assertEquals(0, heroMargins.topMargin)
        assertEquals(0, heroMargins.bottomMargin)
    }

    @Test
    fun heroUsesOneStaticRoundedOutline() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val hero = HeroRowView(
            context = activity,
            route = HeroRoute.HOME,
            session = NativeSession("token", "server", "user", "Codex", "https://example.test"),
            api = JellyfinNativeApi(activity),
            reduceMotion = { true },
            onPrimary = {},
            onDetails = {},
            onControlsFocusChanged = {},
            onImageResult = { _, _, _ -> }
        )

        assertTrue(hero.clipToOutline)
        assertSame(HeroRoundedOutlineProvider, hero.outlineProvider)
        assertNull(hero.animation)
        activity.finish()
    }

    @Test
    fun fireHeroUsesCornerMaskOnlyWhenItsRootIsOpaque() {
        assertTrue(
            HeroRoundingPolicy.useStaticCornerMask(
                TvRenderingProfile.FIRE_TV_PERFORMANCE,
                opaqueRoot = true
            )
        )
        assertFalse(
            HeroRoundingPolicy.useStaticCornerMask(
                TvRenderingProfile.FIRE_TV_PERFORMANCE,
                opaqueRoot = false
            )
        )
        assertFalse(
            HeroRoundingPolicy.useStaticCornerMask(
                TvRenderingProfile.RICH,
                opaqueRoot = true
            )
        )
    }
}
