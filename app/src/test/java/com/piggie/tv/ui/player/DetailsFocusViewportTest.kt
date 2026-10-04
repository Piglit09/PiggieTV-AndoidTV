package com.piggie.tv.ui.player

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-xhdpi")
class DetailsFocusViewportTest {
    @Test
    fun focusedShelfCardKeepsBottomEdgeInsideTheDetailsViewport() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val scroll = ScrollView(activity).apply { isSmoothScrollingEnabled = false }
        val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        page.addView(View(activity), LinearLayout.LayoutParams(800, 600))
        val focusedCard = View(activity)
        page.addView(focusedCard, LinearLayout.LayoutParams(200, 360))
        page.addView(View(activity), LinearLayout.LayoutParams(800, 160))
        scroll.addView(page)
        activity.setContentView(scroll)
        scroll.measure(exact(800), exact(960))
        scroll.layout(0, 0, 800, 960)

        DetailsFocusViewport.ensureBottomClearance(scroll, focusedCard, clearancePx = 64)

        val scrollPosition = IntArray(2)
        val cardPosition = IntArray(2)
        scroll.getLocationOnScreen(scrollPosition)
        focusedCard.getLocationOnScreen(cardPosition)
        assertEquals(64, scroll.scrollY)
        assertTrue(cardPosition[1] + focusedCard.height <= scrollPosition[1] + scroll.height - 64)
        activity.finish()
    }

    @Test
    fun alreadyVisibleShelfCardDoesNotTriggerExtraScrolling() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val scroll = ScrollView(activity).apply { isSmoothScrollingEnabled = false }
        val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        page.addView(View(activity), LinearLayout.LayoutParams(800, 240))
        val focusedCard = View(activity)
        page.addView(focusedCard, LinearLayout.LayoutParams(200, 360))
        page.addView(View(activity), LinearLayout.LayoutParams(800, 460))
        scroll.addView(page)
        activity.setContentView(scroll)
        scroll.measure(exact(800), exact(960))
        scroll.layout(0, 0, 800, 960)

        DetailsFocusViewport.ensureBottomClearance(scroll, focusedCard, clearancePx = 64)

        assertEquals(0, scroll.scrollY)
        activity.finish()
    }

    private fun exact(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
