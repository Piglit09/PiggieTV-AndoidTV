package com.piggie.tv.ui.widgets

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.ui.library.BrowseVisualPolishTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowseCardTitleTreatmentTest {
    private lateinit var activity: Activity
    private lateinit var parent: FrameLayout

    @Before fun prepare() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        parent = FrameLayout(activity).apply { isFocusableInTouchMode = true }
        activity.setContentView(parent)
    }

    @After fun cleanUp() { activity.finish() }

    @Test fun defaultHomeAndDetailsFactoryCallsKeepTheirOriginalSingleLineTitle() {
        val holder = create(browseTitle = false)
        bind(holder, BrowseVisualPolishTest.LONG_TITLE)
        val before = holder.view.height
        holder.view.requestFocus()
        layout()
        assertEquals(1, holder.title.maxLines)
        assertEquals(before, holder.view.height)
        assertNull(panel())
    }

    @Test fun searchPresentationsRevealCompleteTitleEvenWhenAdapterOwnsFocusListener() {
        MediaCardPresentation.entries.forEach { presentation ->
            parent.removeAllViews()
            val holder = create(presentation = presentation)
            bind(holder, "North Harbor Friends", presentation)
            val artwork = Rect(holder.artwork.left, holder.artwork.top, holder.artwork.right, holder.artwork.bottom)
            val height = holder.view.height
            holder.view.onFocusChangeListener = View.OnFocusChangeListener { _, _ -> }
            holder.view.requestFocus()
            layout()
            assertEquals(2, holder.title.maxLines)
            assertEquals(height, holder.view.height)
            assertEquals(artwork, Rect(holder.artwork.left, holder.artwork.top, holder.artwork.right, holder.artwork.bottom))
            val text = holder.title.layout
            assertEquals(holder.title.text.length, text.getLineEnd(text.lineCount - 1))
            for (line in 0 until text.lineCount) assertEquals(0, text.getEllipsisCount(line))
        }
    }

    @Test fun focusedRebindClearsStaleFullTitleAndKeepsFallbackInsideBottomRightScreenEdge() {
        val holder = create()
        holder.view.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT)
        bind(holder, BrowseVisualPolishTest.LONG_TITLE)
        holder.view.requestFocus()
        layout()
        val panel = requireNotNull(panel())
        val content = activity.findViewById<FrameLayout>(android.R.id.content)
        val bounds = Rect()
        panel.getDrawingRect(bounds)
        content.offsetDescendantRectToMyCoords(panel, bounds)
        assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= content.width && bounds.bottom <= content.height)
        assertTrue(panel.layout.lineCount <= 8)
        assertEquals(panel.text.length, panel.layout.getLineEnd(panel.layout.lineCount - 1))
        bind(holder, "Dawn")
        assertTrue(holder.view.hasFocus())
        assertEquals(2, holder.title.maxLines)
        assertNull(panel())
    }

    private fun create(browseTitle: Boolean = true, presentation: MediaCardPresentation = MediaCardPresentation.POSTER): MediaCardHolder {
        val view = MediaCardFactory.createView(parent, presentation, premiumMaterial = true, browseTitle = browseTitle)
        parent.addView(view, FrameLayout.LayoutParams(-2, -2))
        parent.requestFocus()
        layout()
        return MediaCardHolder(view)
    }

    private fun bind(holder: MediaCardHolder, title: String, presentation: MediaCardPresentation = MediaCardPresentation.POSTER) {
        MediaCardFactory.bindView(holder, MediaItem("fixture", title, "Movie", null, null,
            seriesName = null, episodeLabel = null, playbackPositionTicks = 0, runtimeTicks = 0),
            presentation, NativeSession("token", "server", "user", "User", "http://127.0.0.1"), JellyfinNativeApi(activity))
        layout()
    }

    private fun panel(): TextView? = activity.window.decorView.findViewWithTag("ptv_browse_full_title")
    private fun layout() {
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            activity.window.decorView.apply {
                measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                layout(0, 0, 1920, 1080)
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
