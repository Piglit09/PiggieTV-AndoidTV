package com.piggie.tv.ui.player

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DetailsActionFocusPolicyTest {
    @Test
    fun shelfCardDescriptionDoesNotBecomeActionRestoreTarget() {
        val context = RuntimeEnvironment.getApplication()
        val actions = LinearLayout(context)
        val shelf = FrameLayout(context)
        val card = View(context).apply { contentDescription = "Blue Meridian, PG-13" }
        shelf.addView(card)

        assertNull(DetailsActionFocusPolicy.capture(card, actions))
    }

    @Test
    fun actionRetainsItsDescriptionAndPosition() {
        val context = RuntimeEnvironment.getApplication()
        val actions = LinearLayout(context)
        val play = View(context).apply { contentDescription = "Play movie" }
        actions.addView(play)

        assertEquals(ActionFocusSnapshot("Play movie", 0), DetailsActionFocusPolicy.capture(play, actions))
    }

    @Test
    fun delayedActionRestoreDoesNotStealShelfFocus() {
        val context = RuntimeEnvironment.getApplication()
        val actions = LinearLayout(context)
        val play = View(context)
        actions.addView(play)
        val shelf = FrameLayout(context)
        val card = View(context)
        shelf.addView(card)

        assertFalse(DetailsActionFocusPolicy.mayRestore(card, actions))
        assertTrue(DetailsActionFocusPolicy.mayRestore(play, actions))
        assertTrue(DetailsActionFocusPolicy.mayRestore(null, actions))
    }
}
