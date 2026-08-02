package com.piggie.tv.data.discovery

import com.piggie.tv.data.models.MediaItem

data class PreparedShelfItems(
    val rawCount: Int,
    val eligibleCount: Int,
    val deduplicatedCount: Int,
    val items: List<MediaItem>
)

object DiscoveryShelfResultPolicy {
    fun prepare(raw: List<MediaItem>, definition: ShelfDefinition): PreparedShelfItems {
        val eligible = eligible(raw, definition)
        val deduplicated = deduplicate(eligible)
        return PreparedShelfItems(raw.size, eligible.size, deduplicated.size, deduplicated.take(definition.limit))
    }

    fun eligible(raw: List<MediaItem>, definition: ShelfDefinition): List<MediaItem> =
        raw.filter { item ->
            item.id.isNotBlank() && item.title.isNotBlank() &&
                (definition.itemTypes.isEmpty() || definition.itemTypes.any { it.equals(item.type, true) })
        }

    fun deduplicate(eligible: List<MediaItem>): List<MediaItem> = eligible.distinctBy(MediaItem::id)
}
