package com.piggie.tv.ui.hero

import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.ui.layout.TvCompactTargets
import com.piggie.tv.ui.rendering.TvRenderingProfile
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import org.robolectric.annotation.GraphicsMode

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
    fun floatingNavigationLeavesHeroCopyAtItsFormerScreenPositionWhileArtworkReachesTheTop() {
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

        val content = hero.getChildAt(hero.childCount - 1) as LinearLayout
        // The old shell began the 215dp hero below a 60dp header. The floating rail overlays
        // the hero, so copy keeps the same screen Y and artwork gains the released 60dp.
        assertEquals(550, hero.minimumHeight)
        assertEquals(152, content.paddingTop)

        hero.measure(
            View.MeasureSpec.makeMeasureSpec(1_880, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(550, View.MeasureSpec.EXACTLY)
        )
        hero.layout(0, 0, 1_880, 550)
        val artwork = hero.getChildAt(0)
        assertEquals(0, artwork.top)
        assertEquals(550, artwork.bottom)
        activity.finish()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun homeHeroDissolvesIntoPageFloorWithoutObscuringUpperArtwork() {
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

        val floorBlend = requireNotNull(hero.getChildAt(2).background) {
            "Home needs a static floor blend behind its controls"
        }
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        floorBlend.setBounds(0, 0, 200, 100)
        floorBlend.draw(Canvas(bitmap))
        assertTrue("upper art should remain visible", Color.alpha(bitmap.getPixel(100, 4)) < 40)
        val bottom = bitmap.getPixel(100, 98)
        assertTrue("bottom should dissolve into the Home page", Color.alpha(bottom) > 235)
        assertTrue(
            kotlin.math.abs(Color.red(com.piggie.tv.theme.PTVColors.cinemaInk) - Color.red(bottom)) <= 2
        )
        bitmap.recycle()
        activity.finish()
    }

    @Test
    fun homeHeroControlsUseBoundedStaticFocusScale() {
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
        val content = hero.getChildAt(hero.childCount - 1) as LinearLayout
        val actions = content.getChildAt(content.childCount - 1) as LinearLayout
        val details = actions.getChildAt(1) as Button

        details.onFocusChangeListener.onFocusChange(details, true)
        assertTrue(details.scaleX in 1.03f..1.05f)
        assertEquals(details.scaleX, details.scaleY)
        assertNull(details.animation)
        details.onFocusChangeListener.onFocusChange(details, false)
        assertEquals(1f, details.scaleX)
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
