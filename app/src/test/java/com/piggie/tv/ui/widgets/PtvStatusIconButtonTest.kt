package com.piggie.tv.ui.widgets

import android.app.Activity
import android.widget.ImageView
import com.piggie.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-xhdpi")
class PtvStatusIconButtonTest {
    @Test
    fun `status icon is centered with symmetric compact geometry`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val button = PtvStatusIconButton(
            activity,
            R.drawable.ic_action_favorite,
            "Add to favorites",
            selected = false
        ) {}

        assertEquals(ImageView.ScaleType.CENTER_INSIDE, button.scaleType)
        assertEquals(button.paddingLeft, button.paddingRight)
        assertEquals(button.paddingTop, button.paddingBottom)
        assertEquals(0, button.minimumWidth)
        assertEquals(0, button.minimumHeight)
        assertEquals(0f, button.elevation)
        assertNull(button.stateListAnimator)
        assertTrue(button.contentDescription.isNotBlank())
    }
}
