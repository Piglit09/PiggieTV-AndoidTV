package com.piggie.tv.navigation

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import com.piggie.tv.ui.widgets.PtvWaveProgressView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                addAll(descendants(root.getChildAt(index)))
            }
        }
    }
}
