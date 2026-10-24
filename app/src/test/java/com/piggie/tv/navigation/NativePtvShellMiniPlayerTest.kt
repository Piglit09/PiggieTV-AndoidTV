package com.piggie.tv.navigation

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.piggie.tv.R
import com.piggie.tv.ui.widgets.PtvWaveProgressView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-xhdpi")
class NativePtvShellMiniPlayerTest {
    @Test
    fun miniPlayerUsesOneStaticWaveProgressViewWithoutFocusOrAnimation() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        NativePtvShell.create(activity, NativeRoute.HOME) { }

        val waves = descendants(activity.window.decorView)
            .filterIsInstance<PtvWaveProgressView>()
        assertEquals(1, waves.size)
        assertFalse(waves.single().isFocusable)
        assertNull(waves.single().animation)
        activity.finish()
    }

    @Test
    fun headerUsesOneFloatingGlassRailInsteadOfAFullWidthSurface() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        val shell = NativePtvShell.create(activity, NativeRoute.HOME) { }

        val header = activity.findViewById<View>(R.id.ptv_header)
        val rail = activity.findViewById<LinearLayout>(R.id.ptv_nav_rail)
        assertNull(header.background)
        assertNotNull(rail.background)
        assertEquals(NativeRoute.entries.count { it != NativeRoute.PROFILE }, rail.childCount)
        assertTrue(shell.navigation.getValue(NativeRoute.HOME).isSelected)
        assertTrue(rail.children().all { it is Button })
        activity.finish()
    }

    @Test
    fun floatingHeaderOverlaysFullHeightContentInsteadOfReservingAHeaderRow() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        NativePtvShell.create(activity, NativeRoute.HOME) { }

        val root = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
        val container = root.getChildAt(0) as LinearLayout
        val header = activity.findViewById<FrameLayout>(R.id.ptv_header)
        val content = activity.findViewById<FrameLayout>(R.id.ptv_content_frame)
        assertEquals(2, root.childCount)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, container.layoutParams.height)
        assertEquals(root, header.parent)
        assertEquals(container, content.parent)
        assertEquals(2, header.childCount)
        assertTrue(header.getChildAt(1) is HorizontalScrollView)
        assertNull(header.background)
        activity.finish()
    }

    @Test
    fun profileRouteHighlightsSettingsWithoutAddingASeventhTopControl() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        val shell = NativePtvShell.create(activity, NativeRoute.PROFILE) { }

        val rail = activity.findViewById<LinearLayout>(R.id.ptv_nav_rail)
        assertEquals(6, rail.childCount)
        assertEquals(6, shell.navigation.size)
        assertFalse(shell.navigation.containsKey(NativeRoute.PROFILE))
        assertTrue(shell.navigation.getValue(NativeRoute.SETTINGS).isSelected)
        assertEquals("Settings", (rail.getChildAt(5) as Button).text.toString())
        activity.finish()
    }

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                addAll(descendants(root.getChildAt(index)))
            }
        }
    }


    private fun ViewGroup.children(): List<View> =
        (0 until childCount).map(::getChildAt)
}
