package com.piggie.tv.ui.hero

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.theme.PTVColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeroCornerMaskTest {
    @Test
    @Suppress("DEPRECATION")
    fun cornerMasksStayWithinFourSmallBoundsAndReusePathsAfterResize() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val hero = hero(activity)
        val width = 1_880
        val height = 550
        val radius = activity.resources.getDimension(R.dimen.tv_hero_corner_radius)

        rebuild(hero, width, height)
        val paths = cornerPaths(hero)
        assertEquals("one full-hero path creates a large GPU mask", 4, paths.size)
        val bounds = paths.map { path -> RectF().also { path.computeBounds(it, true) } }
        assertEquals(
            listOf(
                RectF(0f, 0f, radius, radius),
                RectF(width - radius, 0f, width.toFloat(), radius),
                RectF(width - radius, height - radius, width.toFloat(), height.toFloat()),
                RectF(0f, height - radius, radius, height.toFloat())
            ),
            bounds
        )

        rebuild(hero, 960, 540)
        val resizedPaths = cornerPaths(hero)
        paths.zip(resizedPaths).forEach { (before, after) -> assertSame(before, after) }
        rebuild(hero, 0, 0)
        assertTrue(cornerPaths(hero).all(Path::isEmpty))
        activity.finish()
    }

    @Test
    fun separateCornerMasksRasterizeLikeTheAcceptedCombinedContour() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val hero = hero(activity)
        val width = 160
        val height = 100
        val radius = activity.resources.getDimension(R.dimen.tv_hero_corner_radius)
        rebuild(hero, width, height)

        val expected = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val actual = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PTVColors.cinemaInk }
        Canvas(expected).drawPath(acceptedCombinedContour(width, height, radius), paint)
        val canvas = Canvas(actual)
        cornerPaths(hero).forEach { canvas.drawPath(it, paint) }

        var mismatchCount = 0
        var maxAlphaDelta = 0
        var totalAlphaDelta = 0
        var silhouetteMismatchCount = 0
        var outsideCornerMismatchCount = 0
        val cornerMismatchCounts = IntArray(4)
        var firstMismatch = "none"
        for (y in 0 until height) {
            for (x in 0 until width) {
                val expectedPixel = expected.getPixel(x, y)
                val actualPixel = actual.getPixel(x, y)
                if (expectedPixel != actualPixel) {
                    mismatchCount++
                    val corner = when {
                        x < radius && y < radius -> 0
                        x >= width - radius && y < radius -> 1
                        x >= width - radius && y >= height - radius -> 2
                        x < radius && y >= height - radius -> 3
                        else -> -1
                    }
                    if (corner < 0) outsideCornerMismatchCount++ else cornerMismatchCounts[corner]++
                    val alphaDelta = kotlin.math.abs(
                        Color.alpha(expectedPixel) - Color.alpha(actualPixel)
                    )
                    maxAlphaDelta = maxOf(maxAlphaDelta, alphaDelta)
                    totalAlphaDelta += alphaDelta
                    if ((Color.alpha(expectedPixel) >= 128) != (Color.alpha(actualPixel) >= 128)) {
                        silhouetteMismatchCount++
                    }
                    if (firstMismatch == "none") {
                        firstMismatch = "($x,$y) expected=$expectedPixel actual=$actualPixel"
                    }
                }
            }
        }
        // Separate draw calls use slightly different native edge antialiasing than one combined
        // draw. The silhouette and total edge coverage must remain within a few edge pixels.
        val edgePixelBudget = (radius * 8).toInt()
        assertTrue("changed pixels=$mismatchCount first=$firstMismatch", mismatchCount <= edgePixelBudget)
        assertTrue("maximum edge alpha difference=$maxAlphaDelta", maxAlphaDelta <= 48)
        assertTrue("aggregate edge alpha difference=$totalAlphaDelta", totalAlphaDelta <= 2_500)
        assertTrue("changed silhouette pixels=$silhouetteMismatchCount", silhouetteMismatchCount <= 16)
        assertEquals("only corners may differ: ${cornerMismatchCounts.toList()}", 0, outsideCornerMismatchCount)

        // Inspect visible color after the masks composite over a midtone backdrop. Reading RGB
        // from translucent pixels would unpremultiply them and exaggerate edge color differences.
        expected.eraseColor(Color.rgb(80, 100, 120))
        actual.eraseColor(Color.rgb(80, 100, 120))
        Canvas(expected).drawPath(acceptedCombinedContour(width, height, radius), paint)
        val displayedCanvas = Canvas(actual)
        cornerPaths(hero).forEach { displayedCanvas.drawPath(it, paint) }
        var maxDisplayedChannelDelta = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val a = expected.getPixel(x, y)
                val b = actual.getPixel(x, y)
                maxDisplayedChannelDelta = maxOf(
                    maxDisplayedChannelDelta,
                    kotlin.math.abs(Color.red(a) - Color.red(b)),
                    kotlin.math.abs(Color.green(a) - Color.green(b)),
                    kotlin.math.abs(Color.blue(a) - Color.blue(b))
                )
            }
        }
        assertTrue("maximum displayed channel difference=$maxDisplayedChannelDelta", maxDisplayedChannelDelta <= 20)
        expected.recycle()
        actual.recycle()
        activity.finish()
    }

    private fun hero(activity: Activity) = HeroRowView(
        context = activity,
        route = HeroRoute.HOME,
        session = NativeSession("token", "server", "user", "Codex", "https://example.test"),
        api = JellyfinNativeApi(activity),
        reduceMotion = { true },
        onPrimary = {},
        onDetails = {},
        onControlsFocusChanged = {},
        onImageResult = { _, _, _ -> }
    )

    private fun rebuild(hero: HeroRowView, width: Int, height: Int) {
        HeroRowView::class.java.getDeclaredMethod(
            "rebuildCornerMask",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }.invoke(hero, width, height)
    }

    private fun cornerPaths(hero: HeroRowView): List<Path> =
        HeroRowView::class.java.declaredFields
            .filter { it.name.startsWith("cornerMask") }
            .flatMap { field ->
                field.isAccessible = true
                when (val value = field.get(hero)) {
                    is Path -> listOf(value)
                    is Array<*> -> value.filterIsInstance<Path>()
                    else -> emptyList()
                }
            }

    // Reference silhouette from the accepted Fire TV corner drawing, kept independent of the
    // production path builder so four small masks cannot silently alter the approved corners.
    private fun acceptedCombinedContour(width: Int, height: Int, radius: Float): Path {
        val control = radius * 0.5522848f
        return Path().apply {
            moveTo(0f, 0f)
            lineTo(radius, 0f)
            cubicTo(radius - control, 0f, 0f, radius - control, 0f, radius)
            close()

            moveTo(width.toFloat(), 0f)
            lineTo(width - radius, 0f)
            cubicTo(width - radius + control, 0f, width.toFloat(), radius - control, width.toFloat(), radius)
            close()

            moveTo(width.toFloat(), height.toFloat())
            lineTo(width.toFloat(), height - radius)
            cubicTo(width.toFloat(), height - radius + control, width - radius + control, height.toFloat(), width - radius, height.toFloat())
            close()

            moveTo(0f, height.toFloat())
            lineTo(radius, height.toFloat())
            cubicTo(radius - control, height.toFloat(), 0f, height - radius + control, 0f, height - radius)
            close()
        }
    }
}
