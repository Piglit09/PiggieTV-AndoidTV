package com.piggie.tv.fixture

import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.piggie.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FixtureCardAuditProviderTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun visibleTreeReportsDistinctExistingCardTagsForSelectedAnimeRoute() {
        val root = FrameLayout(context)
        val rail = LinearLayout(context).apply { id = R.id.ptv_nav_rail }
        rail.addView(Button(context).apply { text = "Shows"; isSelected = true })
        root.addView(rail)
        root.addView(Button(context).apply {
            id = R.id.ptv_shows_tab_anime
            text = "Anime"
            isSelected = true
        })
        val firstId = "70000000000000000000000000000004"
        val secondId = "70000000000000000000000000000005"
        root.addView(View(context).apply { setTag(R.id.ptv_discovery_item_id, firstId) })
        root.addView(View(context).apply { setTag(R.id.ptv_discovery_item_id, secondId) })
        root.addView(View(context).apply { setTag(R.id.ptv_discovery_item_id, firstId) })
        root.addView(View(context).apply {
            visibility = View.GONE
            setTag(R.id.ptv_discovery_item_id, "70000000000000000000000000000007")
        })
        root.addView(View(context).apply { setTag(R.id.ptv_discovery_item_id, "invalid") })

        val snapshot = FixtureCardAuditProvider.captureVisibleTree(root)

        assertEquals("Anime", snapshot.route)
        assertEquals(listOf(firstId, secondId), snapshot.itemIds)
    }

    @Test
    fun missingOrAmbiguousSelectedNavigationFailsClosed() {
        val root = FrameLayout(context)
        root.addView(View(context).apply {
            setTag(R.id.ptv_discovery_item_id, "11111111111111111111111111111111")
        })
        assertNull(FixtureCardAuditProvider.captureVisibleTree(root).route)

        val rail = LinearLayout(context).apply { id = R.id.ptv_nav_rail }
        rail.addView(Button(context).apply { text = "Movies"; isSelected = true })
        rail.addView(Button(context).apply { text = "Shows"; isSelected = true })
        root.addView(rail)
        assertNull(FixtureCardAuditProvider.captureVisibleTree(root).route)
    }

    @Test
    fun libraryBrowserIntentCarriesExactLibraryAuthorityWithoutCredentialFields() {
        val intent = Intent().apply {
            putExtra("lib_id", "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1")
            putExtra("library_name", "Anime")
            putExtra("server_url", "must-not-export")
            putExtra("token", "must-not-export")
        }

        val scope = FixtureCardAuditProvider.browserScope(intent)

        assertEquals("Anime", scope?.first)
        assertEquals("a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1", scope?.second)
    }

    @Test
    fun libraryBrowserNameRemainsInspectableWhileItsRootIsBeingResolved() {
        val intent = Intent().apply { putExtra("library_name", "Anime") }

        val scope = FixtureCardAuditProvider.browserScope(intent)

        assertEquals("Anime", scope?.first)
        assertNull(scope?.second)
    }
}
