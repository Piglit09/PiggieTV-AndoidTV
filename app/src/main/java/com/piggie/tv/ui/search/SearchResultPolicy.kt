package com.piggie.tv.ui.search

import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.MediaShelf
import kotlin.math.roundToInt

/**
 * Android TV search deliberately exposes only playable/browsable TV media.
 * Keeping this policy outside the Fragment makes Book exclusion deterministic
 * even if a server returns types that were included by an older query.
 */
object SearchResultPolicy {
    const val COMPACT_HORIZONTAL_SAFE_MARGIN_DP = 20

    private data class GroupDefinition(
        val type: String,
        val title: String,
        val presentation: MediaCardPresentation
    )

    private val definitions = listOf(
        GroupDefinition("Movie", "Movies", MediaCardPresentation.POSTER),
        GroupDefinition("Series", "Series", MediaCardPresentation.POSTER),
        GroupDefinition("MusicArtist", "Artists", MediaCardPresentation.SQUARE),
        GroupDefinition("MusicAlbum", "Albums", MediaCardPresentation.LANDSCAPE),
        GroupDefinition("Audio", "Songs", MediaCardPresentation.LANDSCAPE),
        GroupDefinition("Person", "People", MediaCardPresentation.SQUARE)
    )

    private val supportedTypes = definitions.mapTo(hashSetOf()) { it.type }

    fun normalizeQuery(query: String): String = query.trim()

    fun compactSafeMarginPx(density: Float): Int =
        (COMPACT_HORIZONTAL_SAFE_MARGIN_DP * density.coerceAtLeast(0f)).roundToInt()

    fun isSupported(item: MediaItem): Boolean = item.type in supportedTypes

    fun shelves(items: List<MediaItem>): List<MediaShelf> {
        val supportedByType = items
            .asSequence()
            .filter(::isSupported)
            .groupBy { it.type }

        return definitions.mapNotNull { definition ->
            supportedByType[definition.type]
                ?.takeIf(List<MediaItem>::isNotEmpty)
                ?.let { typeItems ->
                    MediaShelf(
                        title = definition.title,
                        items = typeItems,
                        presentation = definition.presentation
                    )
                }
        }
    }
}
