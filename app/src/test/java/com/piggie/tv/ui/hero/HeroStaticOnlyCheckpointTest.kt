package com.piggie.tv.ui.hero

import android.app.Activity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class HeroStaticOnlyCheckpointTest {
    @Test
    fun adaptiveHomeKeepsStaticArtworkAndOnlyItsAcceptedActions() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try {
            val hero = HeroRowView(
                context = activity,
                route = HeroRoute.HOME,
                session = NativeSession("token", "server", "user", "Test", "https://example.test"),
                api = JellyfinNativeApi(activity),
                reduceMotion = { true },
                onPrimary = {},
                onDetails = {},
                onControlsFocusChanged = {},
                onImageResult = { _, _, _ -> }
            )

            val content = hero.getChildAt(hero.childCount - 1) as LinearLayout
            val actions = content.getChildAt(content.childCount - 1) as LinearLayout
            assertEquals("Only Play and Details belong to this checkpoint", 2, actions.childCount)
            assertFalse("No video backdrop may be mounted", hero.descendants().any { it is TextureView })
        } finally {
            activity.finish()
        }
    }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (index in 0 until childCount) {
                yieldAll(getChildAt(index).descendants())
            }
        }
    }
}
