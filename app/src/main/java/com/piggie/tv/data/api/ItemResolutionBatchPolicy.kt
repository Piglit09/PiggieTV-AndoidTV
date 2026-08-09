package com.piggie.tv.data.api

import com.piggie.tv.data.models.MediaItem

internal object ItemResolutionBatchPolicy {
    fun batches(itemIds: List<String>, batchSize: Int = DEFAULT_BATCH_SIZE): List<List<String>> {
        require(batchSize > 0)
        return itemIds.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
            .chunked(batchSize)
    }

    fun restoreRequestOrder(itemIds: List<String>, resolved: List<MediaItem>): List<MediaItem> {
        val byId = resolved.associateBy(MediaItem::id)
        return itemIds.distinct().mapNotNull(byId::get)
    }

    private const val DEFAULT_BATCH_SIZE = 50
}
