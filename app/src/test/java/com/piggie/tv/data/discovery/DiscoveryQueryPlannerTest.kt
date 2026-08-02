package com.piggie.tv.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryQueryPlannerTest {
    @Test
    fun everyFilterProducesAFirstClassPlan() {
        DiscoveryFilterType.entries.forEach { type ->
            val value = when (type) {
                DiscoveryFilterType.RANDOM_GENRE,
                DiscoveryFilterType.GENRE -> "Drama"
                DiscoveryFilterType.RANDOM_STUDIO,
                DiscoveryFilterType.STUDIO -> "Studio"
                DiscoveryFilterType.LIBRARY -> "library-id"
                DiscoveryFilterType.ACTOR,
                DiscoveryFilterType.DIRECTOR -> "person-id"
                DiscoveryFilterType.COLLECTION -> "collection-id"
                DiscoveryFilterType.TAG -> "tag"
                DiscoveryFilterType.YEAR -> "2025"
                DiscoveryFilterType.DECADE -> "2020"
                else -> null
            }
            val params = DiscoveryQueryPlanner.itemParameters(
                listOf("Movie"),
                DiscoveryFilter(type, value),
                16,
                libraryId = if (type == DiscoveryFilterType.LIBRARY) value else null
            )
            assertEquals("16", params["Limit"])
            assertNotNull(params["Fields"])
            assertFalse(params["SortBy"]?.contains("Random", ignoreCase = true) == true)
        }
    }

    @Test
    fun randomFacetsUseServerSupportedSorts() {
        val genre = DiscoveryQueryPlanner.itemParameters(
            listOf("Movie"),
            DiscoveryFilter(DiscoveryFilterType.RANDOM_GENRE, "Animation"),
            20
        )
        val studio = DiscoveryQueryPlanner.itemParameters(
            listOf("Movie"),
            DiscoveryFilter(DiscoveryFilterType.RANDOM_STUDIO, "Pixar"),
            20
        )
        assertEquals("CommunityRating", genre["SortBy"])
        assertEquals("CommunityRating", studio["SortBy"])
        assertEquals("Animation", genre["Genres"])
        assertEquals("Pixar", studio["Studios"])
    }

    @Test
    fun decadeHasBoundedPremiereDates() {
        val params = DiscoveryQueryPlanner.itemParameters(
            listOf("Movie"),
            DiscoveryFilter(DiscoveryFilterType.DECADE, "1990"),
            100
        )
        assertEquals("1990-01-01", params["MinPremiereDate"])
        assertEquals("1999-12-31", params["MaxPremiereDate"])
        assertTrue("Random" !in params.values)
    }

    @Test
    fun movieContinueUsesExactResumeEndpointParameters() {
        val params = DiscoveryQueryPlanner.resumeParameters(
            itemTypes = listOf("Movie"),
            limit = 32
        )

        assertEquals("Movie", params["IncludeItemTypes"])
        assertEquals("32", params["Limit"])
        assertEquals("true", params["EnableUserData"])
        assertEquals("false", params["EnableTotalRecordCount"])
        assertNotNull(params["Fields"])
        assertTrue(params.getValue("Fields").contains("BackdropImageTags"))
        assertTrue(params.getValue("Fields").contains("Overview"))
        assertTrue(params.getValue("Fields").contains("SeriesId"))
        assertTrue(params.getValue("Fields").contains("SeasonId"))
        assertTrue(params.getValue("Fields").contains("SeriesName"))
        assertTrue(params.getValue("Fields").contains("IndexNumber"))
        assertTrue(params.getValue("Fields").contains("ParentIndexNumber"))
        assertFalse(params.containsKey("Recursive"))
        assertFalse(params.containsKey("Filters"))
        assertFalse(params.containsKey("IsResumable"))
    }

    @Test
    fun homeManifestDoesNotExposeReadingMedia() {
        val home = DiscoveryManager.manifest(DiscoveryPage.HOME)

        assertTrue(home.shelves.none { shelf ->
            shelf.title.contains("reading", ignoreCase = true) ||
                shelf.itemTypes.any { it.equals("Book", ignoreCase = true) }
        })
    }
}
