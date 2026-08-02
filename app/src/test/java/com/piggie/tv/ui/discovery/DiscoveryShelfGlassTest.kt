package com.piggie.tv.ui.discovery

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.widget.FrameLayout
import com.piggie.tv.R
import com.piggie.tv.data.discovery.DiscoveryFilter
import com.piggie.tv.data.discovery.DiscoveryFilterType
import com.piggie.tv.data.discovery.DiscoveryShelfType
import com.piggie.tv.data.discovery.ShelfDefinition
import com.piggie.tv.data.discovery.ShelfStatus
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.ui.rendering.TvRenderingRuntime
import com.piggie.tv.util.dim
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DiscoveryShelfGlassTest {
    private lateinit var originalManufacturer: String
    private lateinit var originalModel: String

    @Before
    fun useAftkmSafeStaticGlassProfile() {
        originalManufacturer = Build.MANUFACTURER
        originalModel = Build.MODEL
        ShadowBuild.setManufacturer("Amazon")
        ShadowBuild.setModel("AFTKM")
        TvRenderingRuntime.configureDebugExperiment(null)
    }

    @After
    fun restoreDeviceProfile() {
        TvRenderingRuntime.configureDebugExperiment(null)
        ShadowBuild.setManufacturer(originalManufacturer)
        ShadowBuild.setModel(originalModel)
    }

    @Test
    fun glassSurfacePersistsAcrossContentLoadingEmptyAndRetryStates() {
        val context = RuntimeEnvironment.getApplication()
        val slot = DiscoveryShelfSlotView(context, definition())
        val glass = slot.background

        assertNotNull(glass)
        assertEquals(1f, slot.alpha)

        slot.showLoading()
        assertSame(glass, slot.background)

        slot.showEmpty("Next Up", "Nothing is up next right now.")
        assertSame(glass, slot.background)

        slot.showFailure("Next Up", ShelfStatus.TIMEOUT, "Timed out") {}
        assertSame(glass, slot.background)

        val content = FrameLayout(context)
        slot.showContent(content)
        assertSame(glass, slot.background)
        assertSame(slot, content.parent)

        slot.showStaleFailure(FrameLayout(context), "Refresh failed") {}
        assertSame(glass, slot.background)
    }

    @Test
    fun shelfTitleCapIsTranslucentAndFullTrayFrameRemainsRounded() {
        val context = RuntimeEnvironment.getApplication()
        val layers = requireNotNull(context.getDrawable(R.drawable.tv_glass_shelf)) as LayerDrawable
        val titleCap = layers.getDrawable(0) as GradientDrawable
        val trayFrame = layers.getDrawable(1) as GradientDrawable
        val fillAlpha = Color.alpha(requireNotNull(titleCap.color).defaultColor)
        val radius = context.dim(R.dimen.tv_shelf_corner_radius).toFloat()
        val capRadii = requireNotNull(titleCap.cornerRadii)

        assertTrue("fill alpha $fillAlpha should remain within the 35-48% glass range", fillAlpha in 89..122)
        assertEquals(2, layers.numberOfLayers)
        assertEquals(context.dim(R.dimen.tv_shelf_glass_header_height), layers.getLayerHeight(0))
        assertEquals(radius, capRadii[0])
        assertEquals(radius, capRadii[2])
        assertEquals(0f, capRadii[4])
        assertEquals(0f, capRadii[6])
        assertEquals(radius, trayFrame.cornerRadius)
    }

    private fun definition() = ShelfDefinition(
        id = "home.nextup",
        type = DiscoveryShelfType.NEXT_UP,
        title = "Next Up",
        presentation = MediaCardPresentation.LANDSCAPE,
        itemTypes = listOf("Episode"),
        filter = DiscoveryFilter(DiscoveryFilterType.NEXT_UP)
    )
}
