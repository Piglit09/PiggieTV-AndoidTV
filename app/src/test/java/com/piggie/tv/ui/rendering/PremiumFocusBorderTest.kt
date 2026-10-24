package com.piggie.tv.ui.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.assertEquals
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
class PremiumFocusBorderTest {
    @Test
    fun cardFocusDrawsPaleInnerEdgeWithoutFillingArtwork() {
        val bitmap = Bitmap.createBitmap(120, 72, Bitmap.Config.ARGB_8888)
        val drawable = FocusBorderDrawable(RuntimeEnvironment.getApplication().resources, premium = true)
        drawable.bounds = Rect(0, 0, bitmap.width, bitmap.height)

        drawable.draw(Canvas(bitmap))

        val halo = bitmap.getPixel(bitmap.width / 2, 2)
        val innerEdge = bitmap.getPixel(bitmap.width / 2, 5)
        val clearInside = bitmap.getPixel(bitmap.width / 2, 10)
        assertTrue("halo should be restrained rather than an opaque white outline", Color.alpha(halo) in 40..160)
        assertTrue("focus needs a visible non-color-only inner edge", Color.alpha(innerEdge) >= 190)
        assertTrue("inner edge should read pale from couch distance", Color.red(innerEdge) >= 200)
        assertTrue("inner edge should read pale from couch distance", Color.green(innerEdge) >= 200)
        assertTrue("inner edge should read pale from couch distance", Color.blue(innerEdge) >= 200)
        assertEquals("premium focus edge should stay thin", 0, Color.alpha(clearInside))
        assertEquals("focus must leave artwork visible", 0, Color.alpha(bitmap.getPixel(60, 36)))
    }

    @Test
    fun legacyRoutesKeepTheirOriginalSingleStrokeFocusBorder() {
        val bitmap = Bitmap.createBitmap(120, 72, Bitmap.Config.ARGB_8888)
        val drawable = FocusBorderDrawable(RuntimeEnvironment.getApplication().resources)
        drawable.bounds = Rect(0, 0, bitmap.width, bitmap.height)

        drawable.draw(Canvas(bitmap))

        assertEquals("unrelated routes must not inherit the new inner pale stroke", 0, Color.alpha(bitmap.getPixel(60, 7)))
        assertTrue("legacy gradient stroke remains visible", Color.alpha(bitmap.getPixel(60, 3)) > 0)
    }
}
