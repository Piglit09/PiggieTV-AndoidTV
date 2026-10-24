package com.piggie.tv.ui.discovery

import android.view.View
import android.widget.FrameLayout
import com.piggie.tv.data.discovery.DiscoveryFilter
import com.piggie.tv.data.discovery.DiscoveryFilterType
import com.piggie.tv.data.discovery.DiscoveryShelfType
import com.piggie.tv.data.discovery.ShelfDefinition
import com.piggie.tv.data.models.MediaCardPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PremiumHomeRowTest {
    @Test
    fun homeShelfIsOpenWhileOtherDiscoveryShelvesKeepTheirSurfaceAndIdentity() {
        val context = RuntimeEnvironment.getApplication()
        val parent = FrameLayout(context)
        val definition = ShelfDefinition(
            id = "home.nextup",
            type = DiscoveryShelfType.NEXT_UP,
            title = "Next Up",
            presentation = MediaCardPresentation.LANDSCAPE,
            itemTypes = listOf("Episode"),
            filter = DiscoveryFilter(DiscoveryFilterType.NEXT_UP)
        )
        fun adapter(open: Boolean) = DiscoveryPageAdapter(
            definitions = listOf(definition),
            createShelfContent = { View(context) },
            onRetry = {},
            openRowSurface = open
        )

        val home = adapter(open = true)
        val other = adapter(open = false)
        val homeRow = home.onCreateViewHolder(parent, home.getItemViewType(0)).itemView
        val otherRow = other.onCreateViewHolder(parent, other.getItemViewType(0)).itemView

        assertNull("Home artwork should remain visible behind its shelf", homeRow.background)
        assertNotNull("non-Home discovery keeps its established tray", otherRow.background)
        assertEquals(other.getItemId(0), home.getItemId(0))
    }
}
