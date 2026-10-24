package com.piggie.tv.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.piggie.tv.R
import com.piggie.tv.util.dim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailsVisualStyleTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun movieAndSeriesKeepBackdropVisibleWhileProtectingCopy() {
        for (type in listOf("Movie", "Series")) {
            val scrim = View(context)
            val poster = ImageView(context)
            val overview = TextView(context)

            DetailsVisualStyle.applyScene(type, scrim, poster, overview)

            val left = pixel(scrim.background, 8, 20)
            val right = pixel(scrim.background, 192, 20)
            assertTrue("$type copy edge is darker than artwork edge: left=$left right=$right", luminance(left) < luminance(right) * 0.65)
            assertTrue("$type artwork edge still shows through", luminance(right) > 180)
            assertNotNull("$type poster receives a bounded edge", poster.foreground)
            assertEquals(context.dim(R.dimen.tv_details_overview_max_width), overview.maxWidth)
            assertEquals(context.resources.getDimension(R.dimen.tv_text_size_hero_body), overview.textSize, 0.5f)
        }
    }

    @Test
    fun changingToSeasonOrEpisodeRestoresOriginalSceneAndCopy() {
        for (type in listOf("Season", "Episode")) {
            val scrim = View(context)
            val poster = ImageView(context)
            val overview = TextView(context)
            DetailsVisualStyle.applyScene("Movie", scrim, poster, overview)

            DetailsVisualStyle.applyScene(type, scrim, poster, overview)

            val legacy = AppCompatResources.getDrawable(context, R.drawable.hero_gradient_overlay)!!
            assertEquals(pixel(legacy, 8, 20), pixel(scrim.background, 8, 20))
            assertEquals(pixel(legacy, 192, 20), pixel(scrim.background, 192, 20))
            assertEquals(null, poster.foreground)
            assertEquals(Int.MAX_VALUE, overview.maxWidth)
            assertEquals(context.resources.getDimension(R.dimen.tv_text_size_body), overview.textSize, 0.5f)
        }
    }

    @Test
    fun movieAndSeriesActionsUseLocalizedGlassWithPaleFocusEdge() {
        for (type in listOf("Movie", "Series")) {
            val primary = Button(context)
            val secondary = Button(context)
            val icon = ImageView(context)
            val row = LinearLayout(context).apply {
                addView(primary)
                addView(secondary)
                addView(icon)
            }

            DetailsVisualStyle.applyActions(type, row, primary)

            val primaryBackground = primary.background as StateListDrawable
            val secondaryBackground = secondary.background as StateListDrawable
            val iconBackground = icon.background as StateListDrawable
            val primaryNormal = fillColor(primaryBackground, focused = false)
            val secondaryNormal = fillColor(secondaryBackground, focused = false)
            assertTrue(Color.alpha(primaryNormal) > Color.alpha(secondaryNormal))
            assertEquals(secondaryNormal, fillColor(iconBackground, focused = false))
            val focusedEdge = pixelForState(primaryBackground, focused = true, x = 1)
            val focusedCenter = pixelForState(primaryBackground, focused = true, x = 100)
            assertTrue("$type focus edge: edge=$focusedEdge center=$focusedCenter", luminance(focusedEdge) > luminance(focusedCenter))
        }
    }

    @Test
    fun seasonAndEpisodeActionBackgroundsRemainUntouched() {
        for (type in listOf("Season", "Episode")) {
            val action = Button(context)
            val original = AppCompatResources.getDrawable(context, R.drawable.tv_button_primary)
            action.background = original
            val row = LinearLayout(context).apply { addView(action) }

            DetailsVisualStyle.applyActions(type, row, action)

            assertSame(original, action.background)
            assertFalse(action.background is GradientDrawable)
        }
    }

    @Test
    fun movieAndSeriesTitlesLeadWhileNestedDetailsRestoreCompactType() {
        val title = TextView(context)
        DetailsVisualStyle.applyIdentity("Movie", title)
        val movieSize = title.textSize
        assertTrue(movieSize > context.resources.getDimension(R.dimen.tv_text_size_hero_title))

        DetailsVisualStyle.applyIdentity("Series", title)
        assertEquals(movieSize, title.textSize, 0.5f)

        DetailsVisualStyle.applyIdentity("Episode", title)
        assertEquals(context.resources.getDimension(R.dimen.tv_text_size_hero_title), title.textSize, 0.5f)
    }

    private fun fillColor(drawable: StateListDrawable, focused: Boolean): Int {
        drawable.state = state(focused)
        return (drawable.current as GradientDrawable).color!!.defaultColor
    }

    private fun pixelForState(drawable: StateListDrawable, focused: Boolean, x: Int): Int {
        drawable.state = state(focused)
        return pixel(drawable, x, 20)
    }

    private fun state(focused: Boolean): IntArray = if (focused) {
        intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused)
    } else {
        intArrayOf(android.R.attr.state_enabled)
    }

    private fun pixel(drawable: Drawable, x: Int, y: Int): Int {
        val bitmap = Bitmap.createBitmap(200, 40, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        drawable.setBounds(0, 0, 200, 40)
        drawable.draw(canvas)
        val result = bitmap.getPixel(x, y)
        bitmap.recycle()
        return result
    }

    private fun luminance(color: Int): Double =
        0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)
}
