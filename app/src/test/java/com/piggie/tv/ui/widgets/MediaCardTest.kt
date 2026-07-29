package com.piggie.tv.ui.widgets

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.piggie.tv.R
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.ui.rendering.TvFocusIndicator
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [30],
    qualifiers = "sw540dp-w960dp-h540dp-land-xhdpi"
)
class MediaCardTest {
    private lateinit var activity: Activity
    private lateinit var parent: FrameLayout

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        parent = FrameLayout(activity)
        activity.setContentView(parent)
    }

    @After
    fun tearDown() {
        activity.finish()
    }

    @Test
    fun compactBucketUsesProductionArtworkDimensions() {
        assertArtworkSize(MediaCardPresentation.POSTER, widthDp = 100, heightDp = 150)
        assertArtworkSize(MediaCardPresentation.LANDSCAPE, widthDp = 184, heightDp = 104)
        assertArtworkSize(MediaCardPresentation.SQUARE, widthDp = 112, heightDp = 112)
    }

    @Test
    fun factoryRegistersArtworkAndKeepsTitleOutsideFocusBounds() {
        val card = MediaCardFactory.createView(parent, MediaCardPresentation.POSTER)
        parent.addView(card)
        layoutParent()

        val artwork = card.findViewById<View>(R.id.card_artwork)
        val title = card.findViewById<TextView>(R.id.card_title)
        assertSame(artwork, card.getTag(R.id.focus_artwork_view))
        assertTrue(artwork.isAttachedToWindow)

        val artworkLocation = IntArray(2)
        artwork.getLocationOnScreen(artworkLocation)
        val expectedArtworkBounds = Rect(
            artworkLocation[0],
            artworkLocation[1],
            artworkLocation[0] + artwork.width,
            artworkLocation[1] + artwork.height
        )
        assertEquals(expectedArtworkBounds, TvFocusIndicator.artworkBoundsOnScreen(card))

        val titleLocation = IntArray(2)
        title.getLocationOnScreen(titleLocation)
        assertTrue(
            "The title must begin at or below the artwork focus boundary",
            titleLocation[1] >= expectedArtworkBounds.bottom
        )
        assertEquals(1f, card.scaleX)
        assertEquals(1f, card.scaleY)
        assertEquals(0f, card.elevation)
    }

    private fun assertArtworkSize(
        presentation: MediaCardPresentation,
        widthDp: Int,
        heightDp: Int
    ) {
        val card = MediaCardFactory.createView(parent, presentation)
        val artwork = card.findViewById<View>(R.id.card_artwork)
        val density = activity.resources.displayMetrics.density

        assertEquals(widthDp, (artwork.layoutParams.width / density).roundToInt())
        assertEquals(heightDp, (artwork.layoutParams.height / density).roundToInt())
    }

    private fun layoutParent() {
        val density = activity.resources.displayMetrics.density
        val width = (960 * density).roundToInt()
        val height = (540 * density).roundToInt()
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        parent.layout(0, 0, width, height)
    }
}
