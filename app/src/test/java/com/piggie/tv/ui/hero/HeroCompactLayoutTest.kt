package com.piggie.tv.ui.hero

import com.piggie.tv.R
import com.piggie.tv.ui.layout.TvCompactTargets
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-xhdpi")
class HeroCompactLayoutTest {
    @Test
    fun compactProfileHeroResourceIsExactly170Dp() {
        val resources = RuntimeEnvironment.getApplication().resources
        val px = resources.getDimensionPixelSize(R.dimen.tv_hero_height)
        assertEquals(340, px)
        assertEquals(TvCompactTargets.HERO_HEIGHT_DP, (px / resources.displayMetrics.density).roundToInt())
        assertEquals("tv_hero_height", resources.getResourceEntryName(R.dimen.tv_hero_height))
    }

    @Test
    fun discoveryWrapperAndHeroMeasureToExactly340PxWithoutVerticalSpacing() {
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

        assertEquals(340, wrapper.measuredHeight)
        assertEquals(340, hero.measuredHeight)
        assertEquals(0, wrapper.paddingTop)
        assertEquals(0, wrapper.paddingBottom)
        val wrapperMargins = wrapper.layoutParams as ViewGroup.MarginLayoutParams
        val heroMargins = hero.layoutParams as ViewGroup.MarginLayoutParams
        assertEquals(0, wrapperMargins.topMargin)
        assertEquals(0, wrapperMargins.bottomMargin)
        assertEquals(0, heroMargins.topMargin)
        assertEquals(0, heroMargins.bottomMargin)
    }
}
