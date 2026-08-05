package com.piggie.tv.ui.discovery

import com.piggie.tv.data.discovery.DiscoveryShelfType
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryMediaActionPolicyTest {
    @Test
    fun continueWatchingCardResumesInsteadOfOpeningDetails() {
        assertEquals(
            DiscoveryMediaAction.RESUME_PLAYBACK,
            DiscoveryMediaActionPolicy.resolve("Episode", DiscoveryShelfType.CONTINUE_WATCHING)
        )
    }

    @Test
    fun continueWatchingRejectsNonPlayableSeriesResult() {
        assertEquals(
            DiscoveryMediaAction.DETAILS,
            DiscoveryMediaActionPolicy.resolve("Series", DiscoveryShelfType.CONTINUE_WATCHING)
        )
    }

    @Test
    fun regularMediaCardStillOpensDetails() {
        assertEquals(
            DiscoveryMediaAction.DETAILS,
            DiscoveryMediaActionPolicy.resolve("Episode", DiscoveryShelfType.NEXT_UP)
        )
    }

    @Test
    fun viewMoreAlwaysKeepsBrowsePrecedence() {
        assertEquals(
            DiscoveryMediaAction.VIEW_MORE,
            DiscoveryMediaActionPolicy.resolve("ViewMore", DiscoveryShelfType.CONTINUE_WATCHING)
        )
    }
}
