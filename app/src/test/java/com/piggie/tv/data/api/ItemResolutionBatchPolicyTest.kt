package com.piggie.tv.data.api

import com.piggie.tv.data.models.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ItemResolutionBatchPolicyTest {
    @Test
    fun idsAreDeduplicatedAndBoundedBeforeNetworkResolution() {
        val batches = ItemResolutionBatchPolicy.batches(
            itemIds = listOf(" a ", "b", "a", "", "c"),
            batchSize = 2
        )

        assertEquals(listOf(listOf("a", "b"), listOf("c")), batches)
    }

    @Test
    fun resolvedItemsReturnInRequestedOrderAndSkipMissingIds() {
        val resolved = listOf(item("c"), item("a"))

        assertEquals(
            listOf("a", "c"),
            ItemResolutionBatchPolicy.restoreRequestOrder(listOf("a", "b", "c"), resolved)
                .map(MediaItem::id)
        )
    }

    private fun item(id: String) = MediaItem(
        id = id,
        title = id,
        type = "Audio",
        year = null,
        imageTag = null,
        seriesName = null,
        episodeLabel = null,
        playbackPositionTicks = 0L,
        runtimeTicks = 0L
    )
}
