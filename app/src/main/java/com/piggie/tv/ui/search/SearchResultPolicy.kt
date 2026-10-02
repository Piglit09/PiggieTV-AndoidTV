package com.piggie.tv.ui.search

import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.MediaShelf
import com.piggie.tv.data.discovery.DiscoveryBrowseRequest
import com.piggie.tv.data.discovery.DiscoveryFilter
import com.piggie.tv.data.discovery.DiscoveryFilterType
import kotlin.math.roundToInt

/**
 * Android TV search deliberately exposes only playable/browsable TV media.
 * Keeping this policy outside the Fragment makes Book exclusion deterministic
 * even if a server returns types that were included by an older query.
 */
object SearchResultPolicy {
    const val COMPACT_HORIZONTAL_SAFE_MARGIN_DP = 20

    enum class Scope(
        val label: String,
        val itemTypes: List<String>,
        val includeGenres: Boolean = false,
        val includeStudios: Boolean = false
    ) {
        ALL(
            label = "All",
            itemTypes = listOf(
                "Movie",
                "Series",
                "MusicArtist",
                "MusicAlbum",
                "Audio",
                "Person"
            ),
            includeGenres = true,
            includeStudios = true
        ),
        MOVIES("Movies", listOf("Movie")),
        SERIES("Series", listOf("Series")),
        MUSIC("Music", listOf("MusicArtist", "MusicAlbum", "Audio")),
        PEOPLE("People", listOf("Person")),
        GENRES("Genres", emptyList(), includeGenres = true),
        STUDIOS("Studios", emptyList(), includeStudios = true);

        val controlKey: String
            get() = "filter:${name.lowercase()}"

        companion object {
            fun fromStored(value: String?): Scope = entries.firstOrNull {
                it.name.equals(value, ignoreCase = true)
            } ?: ALL

            fun fromControlKey(value: String?): Scope? {
                val name = value?.substringAfter("filter:", missingDelimiterValue = "")
                    ?.takeIf(String::isNotBlank)
                    ?: return null
                return entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            }
        }
    }

    private data class GroupDefinition(
        val types: Set<String>,
        val title: String,
        val presentation: MediaCardPresentation,
        val scopes: Set<Scope>
    )

    private val definitions = listOf(
        GroupDefinition(setOf("Movie"), "Movies", MediaCardPresentation.POSTER, setOf(Scope.ALL, Scope.MOVIES)),
        GroupDefinition(setOf("Series"), "Series", MediaCardPresentation.POSTER, setOf(Scope.ALL, Scope.SERIES)),
        GroupDefinition(setOf("MusicArtist"), "Artists", MediaCardPresentation.SQUARE, setOf(Scope.ALL, Scope.MUSIC)),
        GroupDefinition(setOf("MusicAlbum"), "Albums", MediaCardPresentation.LANDSCAPE, setOf(Scope.ALL, Scope.MUSIC)),
        GroupDefinition(setOf("Audio"), "Songs", MediaCardPresentation.LANDSCAPE, setOf(Scope.ALL, Scope.MUSIC)),
        GroupDefinition(setOf("Person"), "People", MediaCardPresentation.SQUARE, setOf(Scope.ALL, Scope.PEOPLE)),
        GroupDefinition(setOf("Genre", "MusicGenre"), "Genres", MediaCardPresentation.SQUARE, setOf(Scope.ALL, Scope.GENRES)),
        GroupDefinition(setOf("Studio"), "Studios", MediaCardPresentation.SQUARE, setOf(Scope.ALL, Scope.STUDIOS))
    )

    private val supportedTypes = definitions.flatMapTo(hashSetOf()) { it.types }

    fun normalizeQuery(query: String): String = query.trim()

    fun compactSafeMarginPx(density: Float): Int =
        (COMPACT_HORIZONTAL_SAFE_MARGIN_DP * density.coerceAtLeast(0f)).roundToInt()

    fun isSupported(item: MediaItem): Boolean = item.type in supportedTypes

    fun shelves(items: List<MediaItem>, scope: Scope = Scope.ALL): List<MediaShelf> {
        val supportedByType = items
            .asSequence()
            .filter(::isSupported)
            .groupBy { it.type }

        return definitions.asSequence()
            .filter { scope in it.scopes }
            .mapNotNull { definition ->
                definition.types
                    .flatMap { supportedByType[it].orEmpty() }
                    .distinctBy { "${it.type}:${it.id}" }
                    .takeIf(List<MediaItem>::isNotEmpty)
                    ?.let { typeItems ->
                        MediaShelf(
                            title = definition.title,
                            items = typeItems,
                            presentation = definition.presentation
                        )
                    }
            }
            .toList()
    }

    fun categoryBrowseRequest(item: MediaItem): DiscoveryBrowseRequest? = when (item.type) {
        "Genre" -> DiscoveryBrowseRequest(
            title = item.title,
            filter = DiscoveryFilter(DiscoveryFilterType.GENRE, item.title),
            itemTypes = listOf("Movie", "Series")
        )
        "MusicGenre" -> DiscoveryBrowseRequest(
            title = item.title,
            filter = DiscoveryFilter(DiscoveryFilterType.GENRE, item.title),
            itemTypes = listOf("MusicAlbum")
        )
        "Studio" -> DiscoveryBrowseRequest(
            title = item.title,
            filter = DiscoveryFilter(DiscoveryFilterType.STUDIO, item.title),
            itemTypes = listOf("Movie", "Series")
        )
        else -> null
    }
}
