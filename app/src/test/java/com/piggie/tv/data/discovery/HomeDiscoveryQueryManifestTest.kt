package com.piggie.tv.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDiscoveryQueryManifestTest {
    @Test fun allEightPlansAreBoundedAndUseTheirRealEndpointCategories() {
        val plans = DiscoveryManager.manifest(DiscoveryPage.HOME).shelves.associate { definition ->
            definition.id to DiscoveryManager.queryManifest(definition)
        }
        assertEquals(8, plans.size)
        assertEquals("/Users/[user]/Items/Resume", plans.getValue("home.continue").endpoint)
        assertEquals("/Shows/NextUp", plans.getValue("home.nextup").endpoint)
        assertEquals("/Users/[user]/Suggestions", plans.getValue("home.recommended").endpoint)
        assertTrue(plans.values.all { it.limit == 16 })
        assertTrue(plans.values.all { it.filters["Limit"] == "16" })
        assertFalse(plans.values.any { plan -> plan.filters.values.any { it.contains("Random", true) } })
        assertEquals("Movie,Series", plans.getValue("home.recommended").filters["IncludeItemTypes"])
        val home = DiscoveryManager.manifest(DiscoveryPage.HOME).shelves
        assertEquals(listOf("Horror", "Comedy"), home.takeLast(2).map(ShelfDefinition::title))
        assertTrue(home.takeLast(2).all { it.type == DiscoveryShelfType.RANDOM_GENRE })
        assertFalse(home.any { it.title.contains("Collection", true) })
    }

    @Test fun showsContinueWatchingRequestsPlayableEpisodes() {
        val definition = DiscoveryManager.manifest(DiscoveryPage.SHOWS).shelves
            .single { it.id == "shows.continue" }
        val plan = DiscoveryManager.queryManifest(definition)

        assertEquals(listOf("Episode"), definition.itemTypes)
        assertEquals("Episode", plan.filters["IncludeItemTypes"])
        assertEquals("/Users/[user]/Items/Resume", plan.endpoint)
    }
}
