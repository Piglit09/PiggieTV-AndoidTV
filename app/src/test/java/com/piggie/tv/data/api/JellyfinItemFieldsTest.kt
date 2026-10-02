package com.piggie.tv.data.api

import com.piggie.tv.data.discovery.DiscoveryQueryPlanner
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinItemFieldsTest {
    @Test
    fun `all PiggieTV field groups contain only Jellyfin ItemFields values`() {
        val groups = listOf(
            JellyfinItemFields.CARD,
            JellyfinItemFields.CARD_WITH_PEOPLE,
            JellyfinItemFields.RECOMMENDATION,
            JellyfinItemFields.ITEM_DETAILS,
            JellyfinItemFields.SERIES_DETAILS,
            JellyfinItemFields.EPISODE_DETAILS,
            JellyfinItemFields.QUEUE,
            JellyfinItemFields.MUSIC,
            JellyfinItemFields.MUSIC_RECOMMENDATION,
            JellyfinItemFields.READING,
            DiscoveryQueryPlanner.CARD_FIELDS,
            DiscoveryQueryPlanner.RECOMMENDATION_FIELDS
        )

        groups.forEach { fields ->
            assertTrue(
                "unsupported Jellyfin ItemFields in $fields: ${JellyfinItemFields.unsupported(fields)}",
                JellyfinItemFields.unsupported(fields).isEmpty()
            )
        }
    }

    @Test
    fun `base item properties are rejected from the Fields query`() {
        val requested =
            "PrimaryImageAspectRatio,ImageTags,SeriesId,SeasonId,IndexNumber," +
                "ParentIndexNumber,UserData,RunTimeTicks,Genres"

        assertTrue(JellyfinItemFields.unsupported(requested).isNotEmpty())
        assertTrue(
            JellyfinItemFields.normalize(requested) == "PrimaryImageAspectRatio,Genres"
        )
    }
}
