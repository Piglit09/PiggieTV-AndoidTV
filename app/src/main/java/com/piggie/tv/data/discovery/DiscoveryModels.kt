package com.piggie.tv.data.discovery

import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem

enum class DiscoveryPage {
    HOME,
    MOVIES,
    SHOWS
}

enum class ShelfStatus {
    READY,
    EMPTY,
    LOADING,
    TIMEOUT,
    HTTP_ERROR,
    INVALID_QUERY,
    MISSING_LIBRARY,
    NO_ITEMS,
    DUPLICATE,
    FAILED_RENDER
    ,CANCELED_HIDDEN_ROUTE
    ,CANCELED_REPLACED_REQUEST
    ,RENDER_ERROR
}

enum class DiscoveryFinalState {
    NOT_STARTED, QUEUED, LOADING, CONTENT, EMPTY, TIMEOUT, HTTP_ERROR, INVALID_QUERY, MISSING_LIBRARY,
    CANCELED_HIDDEN_ROUTE, CANCELED_REPLACED_REQUEST, RENDER_ERROR
}

fun ShelfStatus.toFinalState(): DiscoveryFinalState = when (this) {
    ShelfStatus.READY -> DiscoveryFinalState.CONTENT
    ShelfStatus.EMPTY, ShelfStatus.NO_ITEMS -> DiscoveryFinalState.EMPTY
    ShelfStatus.LOADING -> DiscoveryFinalState.LOADING
    ShelfStatus.TIMEOUT -> DiscoveryFinalState.TIMEOUT
    ShelfStatus.HTTP_ERROR -> DiscoveryFinalState.HTTP_ERROR
    ShelfStatus.INVALID_QUERY -> DiscoveryFinalState.INVALID_QUERY
    ShelfStatus.MISSING_LIBRARY -> DiscoveryFinalState.MISSING_LIBRARY
    ShelfStatus.CANCELED_HIDDEN_ROUTE -> DiscoveryFinalState.CANCELED_HIDDEN_ROUTE
    ShelfStatus.CANCELED_REPLACED_REQUEST, ShelfStatus.DUPLICATE -> DiscoveryFinalState.CANCELED_REPLACED_REQUEST
    ShelfStatus.FAILED_RENDER, ShelfStatus.RENDER_ERROR -> DiscoveryFinalState.RENDER_ERROR
}

enum class DiscoveryShelfType {
    CONTINUE_WATCHING,
    NEXT_UP,
    RECENTLY_ADDED,
    RECOMMENDED,
    TRENDING,
    LATEST_RELEASES,
    POPULAR,
    RANDOM_GENRE,
    RANDOM_STUDIO,
    LIBRARY_SPECIFIC,
    LATEST_EPISODES,
    YOU_MAY_ALSO_LIKE
}

enum class DiscoveryDataSource {
    USER_ITEMS,
    RESUME,
    NEXT_UP,
    SUGGESTIONS,
    LIBRARY_ITEMS
}

/** First-class browser/query filters. Do not overload a library title to imply one. */
enum class DiscoveryFilterType {
    CONTINUE_WATCHING,
    NEXT_UP,
    RECENTLY_ADDED,
    LATEST_RELEASES,
    POPULAR,
    RECOMMENDED,
    RANDOM_GENRE,
    RANDOM_STUDIO,
    LIBRARY,
    GENRE,
    STUDIO,
    ACTOR,
    DIRECTOR,
    COLLECTION,
    TAG,
    YEAR,
    DECADE
}

data class DiscoveryFilter(
    val type: DiscoveryFilterType,
    val value: String? = null
)

data class DiscoveryBrowseRequest(
    val title: String,
    val filter: DiscoveryFilter,
    val itemTypes: List<String> = listOf("Movie", "Series"),
    val libraryId: String? = null
)

data class ShelfDefinition(
    val id: String,
    val type: DiscoveryShelfType,
    val title: String,
    val presentation: MediaCardPresentation,
    val itemTypes: List<String> = emptyList(),
    val dataSource: DiscoveryDataSource = DiscoveryDataSource.USER_ITEMS,
    val filter: DiscoveryFilter,
    val parentId: String? = null,
    val libraryName: String? = null,
    val limit: Int = 16,
    val priority: Int = 100
)

data class PageManifest(
    val page: DiscoveryPage,
    val expectedShelves: Int,
    val shelves: List<ShelfDefinition>
) {
    init {
        require(expectedShelves == shelves.size) {
            "${page.name} expected $expectedShelves shelves but defines ${shelves.size}"
        }
        require(shelves.map(ShelfDefinition::id).distinct().size == shelves.size) {
            "${page.name} contains duplicate shelf IDs"
        }
    }
}

data class DiscoverySession(
    val id: String,
    val timestamp: Long,
    val scopeKey: String,
    val randomGenres: MutableList<String> = mutableListOf(),
    val randomStudios: MutableList<String> = mutableListOf(),
    internal val genreByShelf: MutableMap<String, String> = mutableMapOf(),
    internal val studioByShelf: MutableMap<String, String> = mutableMapOf(),
    internal val genreCountsByItemType: MutableMap<String, MutableMap<String, Int>> = mutableMapOf(),
    internal val studioCountsByItemType: MutableMap<String, MutableMap<String, Int>> = mutableMapOf(),
    internal var facetsLoaded: Boolean = false
)

data class PageDefinition(
    val route: String,
    val shelves: List<ShelfDefinition>
)

data class ShelfQueryManifest(
    val shelfId: String,
    val dataSource: DiscoveryDataSource,
    val endpoint: String,
    val safeHeaders: String,
    val library: String?,
    val filters: Map<String, String>,
    val sort: String?,
    val limit: Int
) {
    fun summary(): String = buildString {
        append(endpoint)
        if (filters.isNotEmpty()) {
            append('?')
            append(filters.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" })
        }
    }
}

data class ShelfDiagnostic(
    val shelfId: String,
    val shelfTitle: String,
    val page: DiscoveryPage,
    val status: ShelfStatus,
    val query: ShelfQueryManifest?,
    val resultCount: Int,
    val deduplicatedCount: Int,
    val renderCount: Int,
    val filteredCount: Int = resultCount,
    val elapsedMs: Long,
    val httpMs: Long?,
    val httpStatus: Int?,
    val cacheHit: Boolean,
    val retryCount: Int,
    val failure: String? = null,
    val responseBody: String? = null,
    val visibleCards: Int? = null,
    val firstPosterMs: Long? = null,
    val renderCompletedMs: Long? = null,
    val timestampMs: Long = System.currentTimeMillis()
    ,val generationStartedMs: Long? = null
    ,val requestStartedMs: Long? = null
    ,val requestFinishedMs: Long? = null
    ,val cacheAgeMs: Long? = null
    ,val finalState: DiscoveryFinalState = status.toFinalState()
    ,val generationId: Long = 0
    ,val attemptId: Int = retryCount
    ,val queueWaitMs: Long? = null
    ,val dnsMs: Long? = null
    ,val connectMs: Long? = null
    ,val tlsMs: Long? = null
    ,val requestWriteMs: Long? = null
    ,val timeToFirstByteMs: Long? = null
    ,val responseReadMs: Long? = null
    ,val responseBytes: Long? = null
    ,val parseMs: Long? = null
    ,val filteringMs: Long? = null
    ,val scoringMs: Long? = null
    ,val dedupeMs: Long? = null
    ,val adapterBuildMs: Long? = null
    ,val firstCardSubmitMs: Long? = null
    ,val requestAvoided: Boolean = false
    ,val cacheTtlRemainingMs: Long? = null
    ,val cacheEntryBytes: Long? = null
    ,val cacheKeyCategory: String? = null
    ,val inFlightCoalesced: Boolean = false
    ,val adapterPresent: Boolean = false
    ,val networkRequestStarted: Boolean = false
    ,val transitionOrdinal: Long = 0
    ,val faultInjectionDelayMs: Long? = null
    ,val cacheSourceShelfId: String? = null
    ,val cacheRefreshBypassed: Boolean = false
)

val ShelfDiagnostic.rawCount: Int get() = resultCount
val ShelfDiagnostic.eligibleCount: Int get() = filteredCount
val ShelfDiagnostic.adapterCount: Int get() = renderCount

data class DiscoveryManifestAggregate(
    val page: DiscoveryPage,
    val generationId: Long,
    val expected: Int,
    val defined: Int,
    val notStarted: Int,
    val queued: Int,
    val requested: Int,
    val loading: Int,
    val content: Int,
    val empty: Int,
    val failed: Int,
    val canceled: Int,
    val renderedShelves: Int,
    val renderedCards: Int
)

data class DiscoveryPageDiagnostics(
    val generationId: Long,
    val shelves: List<ShelfDiagnostic>,
    val aggregate: DiscoveryManifestAggregate
)

data class DiscoveryShelf(
    val definition: ShelfDefinition,
    val items: List<MediaItem>,
    val status: ShelfStatus,
    val diagnostic: ShelfDiagnostic,
    val viewMoreItem: MediaItem? = null,
    val browseRequest: DiscoveryBrowseRequest? = null,
    val message: String? = null
)

data class DiscoveryBrowserResult(
    val status: ShelfStatus,
    val items: List<MediaItem> = emptyList(),
    val message: String? = null
)

interface DiscoveryPageRequest {
    fun cancel()
    fun retryShelf(shelfId: String)
}
