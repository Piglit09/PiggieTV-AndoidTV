package com.piggie.tv.fixture

import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.piggie.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FixtureAdaptiveAuditTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun heroImageCountersCountRequestsAndDecodesIndependentOfTraceRing() {
        FixtureAdaptiveAudit.resetForTest()
        FixtureAdaptiveAudit.recordImageEvent("hero", "request_start")
        FixtureAdaptiveAudit.recordImageEvent("hero", "decode_start")
        FixtureAdaptiveAudit.recordImageEvent("hero", "memory_cache_hit")
        FixtureAdaptiveAudit.recordImageEvent("card", "request_start")
        val values = FixtureAdaptiveAudit.imageSnapshot()
        assertEquals(1, values["request_start"])
        assertEquals(1, values["decode_start"])
        assertEquals(1, values["memory_cache_hit"])
        assertEquals(3, values.values.sum())
    }

    @Test
    fun structureSnapshotExposesHeroPageAdapterAndCardIdentity() {
        val root = FrameLayout(context)
        val page = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        }
        root.addView(page)
        val hero = FrameLayout(context).apply { id = R.id.hero_row }
        val card = View(context).apply {
            setTag(R.id.ptv_discovery_item_id, "11111111111111111111111111111111")
        }
        root.addView(hero)
        root.addView(card)
        root.addView(FrameLayout(context).apply {
            visibility = View.GONE
            addView(View(context).apply {
                setTag(R.id.ptv_discovery_item_id, "22222222222222222222222222222222")
            })
        })

        val snapshot = FixtureAdaptiveAudit.captureStructure(root)
        assertEquals(System.identityHashCode(hero), snapshot.heroViewIdentity)
        assertEquals(System.identityHashCode(page), snapshot.pageViewIdentity)
        assertTrue(snapshot.visibleCardIdentities.contains(System.identityHashCode(card)))
        assertEquals(listOf("11111111111111111111111111111111"), snapshot.visibleCardIds)
        assertFalse(snapshot.heroBounds.isEmpty())
        assertNotEquals(snapshot.heroViewIdentity, snapshot.pageViewIdentity)
    }
}
