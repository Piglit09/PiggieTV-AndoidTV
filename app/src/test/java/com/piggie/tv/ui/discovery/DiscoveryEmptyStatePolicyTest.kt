package com.piggie.tv.ui.discovery

import android.app.Activity
import android.os.Build
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.piggie.tv.data.discovery.DiscoveryFilter
import com.piggie.tv.data.discovery.DiscoveryFilterType
import com.piggie.tv.data.discovery.DiscoveryShelfType
import com.piggie.tv.data.discovery.ShelfDefinition
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.discovery.ShelfStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DiscoveryEmptyStatePolicyTest {
    @Test fun legitimateEmptyDoesNotOfferFailureRetry() {
        assertFalse(DiscoveryShelfActionPolicy.canRetry(ShelfStatus.EMPTY))
        assertFalse(DiscoveryShelfActionPolicy.canRetry(ShelfStatus.NO_ITEMS))
    }

    @Test fun failuresOfferRetry() {
        assertTrue(DiscoveryShelfActionPolicy.canRetry(ShelfStatus.TIMEOUT))
        assertTrue(DiscoveryShelfActionPolicy.canRetry(ShelfStatus.HTTP_ERROR))
        assertTrue(DiscoveryShelfActionPolicy.canRetry(ShelfStatus.INVALID_QUERY))
        assertTrue(DiscoveryShelfActionPolicy.canRetry(ShelfStatus.MISSING_LIBRARY))
        assertTrue(DiscoveryShelfActionPolicy.canRetry(ShelfStatus.RENDER_ERROR))
    }

    @Test fun legitimateNextUpEmptyRendersMessageWithoutRetryButton() {
        val slot = DiscoveryShelfSlotView(RuntimeEnvironment.getApplication(), definition())
        slot.showEmpty("Next Up", "Nothing is up next right now.")
        assertTrue(descendants(slot).filterIsInstance<TextView>().any { it.text == "Nothing is up next right now." })
        assertTrue(descendants(slot).none { it is Button })
    }

    @Test fun fireTvLoadingShelfHasNoContinuouslyAnimatingProgressDrawable() {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        try {
            ShadowBuild.setManufacturer("Amazon")
            ShadowBuild.setModel("AFTKM")
            com.piggie.tv.ui.rendering.TvRenderingRuntime.configureDebugExperiment(null)

            val slot = DiscoveryShelfSlotView(RuntimeEnvironment.getApplication(), definition())

            assertTrue(descendants(slot).none { it is ProgressBar })
            assertTrue(descendants(slot).filterIsInstance<TextView>().any { it.text == "Loading…" })
        } finally {
            ShadowBuild.setManufacturer(manufacturer)
            ShadowBuild.setModel(model)
        }
    }

    @Test fun failureRetryIsFocusableAndFocusMovesIntoRecoveredContent() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = activity
        val slot = DiscoveryShelfSlotView(context, definition())
        activity.setContentView(slot)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        slot.showFailure("Next Up", ShelfStatus.TIMEOUT, "Timed out") {
            slot.showLoading("Next Up", preserveFocus = true)
        }
        val retry = descendants(slot).filterIsInstance<Button>().single()
        assertTrue(retry.requestFocus())
        retry.performClick()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(slot.hasFocus())

        val recovered = Button(context).apply {
            text = "Recovered card"
            isFocusableInTouchMode = true
        }
        slot.showContent(FrameLayout(context).apply { addView(recovered) })
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(recovered.hasFocus())
        assertEquals("Recovered card", recovered.text)
    }

    private fun definition() = ShelfDefinition(
        "home.nextup",
        DiscoveryShelfType.NEXT_UP,
        "Next Up",
        MediaCardPresentation.LANDSCAPE,
        listOf("Episode"),
        filter = DiscoveryFilter(DiscoveryFilterType.NEXT_UP)
    )

    private fun descendants(root: ViewGroup): List<android.view.View> = buildList {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            add(child)
            if (child is ViewGroup) addAll(descendants(child))
        }
    }
}
