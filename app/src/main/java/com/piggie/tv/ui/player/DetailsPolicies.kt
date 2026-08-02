package com.piggie.tv.ui.player

import com.piggie.tv.data.models.MediaItem
import java.util.LinkedHashMap

enum class DetailsArtworkKind {
    PRIMARY,
    THUMB,
    BACKDROP,
    LOGO
}

data class DetailsArtworkRef(
    val itemId: String,
    val kind: DetailsArtworkKind,
    val tag: String?,
    val blurred: Boolean = false
)

/**
 * Resolves artwork without making the UI understand Jellyfin's parent-artwork fields.
 * The order is deliberately cheap: use a concrete server image before a branded fallback.
 */
object DetailsArtworkPolicy {
    fun posterCandidates(item: MediaItem): List<DetailsArtworkRef> = buildList {
        if (item.type == "Episode") {
            item.parentPrimaryImageTag?.let {
                add(DetailsArtworkRef(item.parentPrimaryImageItemId ?: item.seriesId ?: item.id, DetailsArtworkKind.PRIMARY, it))
            }
            item.seriesPrimaryImageTag?.let {
                add(DetailsArtworkRef(item.seriesId ?: item.id, DetailsArtworkKind.PRIMARY, it))
            }
        } else {
            item.imageTag?.let { add(DetailsArtworkRef(item.id, DetailsArtworkKind.PRIMARY, it)) }
            item.thumbImageTag?.let { add(DetailsArtworkRef(item.id, DetailsArtworkKind.THUMB, it)) }
            item.parentPrimaryImageTag?.let {
                add(DetailsArtworkRef(item.parentPrimaryImageItemId ?: item.id, DetailsArtworkKind.PRIMARY, it))
            }
        }
    }.distinct()

    fun poster(item: MediaItem): DetailsArtworkRef? = posterCandidates(item).firstOrNull()

    fun episodeThumbnailCandidates(item: MediaItem): List<DetailsArtworkRef> = buildList {
        item.thumbImageTag?.let { add(DetailsArtworkRef(item.id, DetailsArtworkKind.THUMB, it)) }
        item.imageTag?.let { add(DetailsArtworkRef(item.id, DetailsArtworkKind.PRIMARY, it)) }
        item.parentThumbImageTag?.let {
            add(DetailsArtworkRef(item.parentThumbItemId ?: item.seriesId ?: item.id, DetailsArtworkKind.THUMB, it))
        }
    }.distinct()

    fun episodeThumbnail(item: MediaItem): DetailsArtworkRef? =
        episodeThumbnailCandidates(item).firstOrNull()

    fun logoCandidates(item: MediaItem): List<DetailsArtworkRef> = buildList {
        if (item.type == "Episode") {
            item.parentLogoImageTag?.let {
                add(DetailsArtworkRef(item.parentLogoItemId ?: item.seriesId ?: item.id, DetailsArtworkKind.LOGO, it))
            }
        }
        item.logoTag?.let { add(DetailsArtworkRef(item.id, DetailsArtworkKind.LOGO, it)) }
        if (item.type != "Episode") {
            item.parentLogoImageTag?.let {
                add(DetailsArtworkRef(item.parentLogoItemId ?: item.seriesId ?: item.id, DetailsArtworkKind.LOGO, it))
            }
        }
    }.distinct()

    fun logo(item: MediaItem): DetailsArtworkRef? = logoCandidates(item).firstOrNull()

    fun backdropCandidates(item: MediaItem): List<DetailsArtworkRef> = buildList {
        val ownTag = item.backdropImageTags.firstOrNull() ?: item.backdropTag
        val parentTag = item.parentBackdropImageTags.firstOrNull()
        if (item.type == "Episode") {
            parentTag?.let {
                add(DetailsArtworkRef(item.parentBackdropItemId ?: item.seriesId ?: item.id, DetailsArtworkKind.BACKDROP, it))
            }
            ownTag?.let { add(DetailsArtworkRef(item.id, DetailsArtworkKind.BACKDROP, it)) }
        } else {
            ownTag?.let { add(DetailsArtworkRef(item.id, DetailsArtworkKind.BACKDROP, it)) }
            parentTag?.let {
                add(DetailsArtworkRef(item.parentBackdropItemId ?: item.seriesId ?: item.id, DetailsArtworkKind.BACKDROP, it))
            }
        }
        val posterFallbacks = if (item.type == "Episode") {
            posterCandidates(item) + episodeThumbnailCandidates(item)
        } else {
            posterCandidates(item)
        }
        addAll(posterFallbacks.map { it.copy(blurred = true) })
    }.distinct()

    fun backdrop(item: MediaItem): DetailsArtworkRef? = backdropCandidates(item).firstOrNull()
}

object RelatedContentPolicy {
    const val INITIAL_LIMIT = 16

    fun filter(currentId: String, items: List<MediaItem>): List<MediaItem> =
        items.asSequence()
            .filterNot { it.id == currentId }
            .distinctBy { it.id }
            .take(INITIAL_LIMIT)
            .toList()
}

object SeasonCardPolicy {
    fun label(season: MediaItem): String = season.title.takeIf(String::isNotBlank)
        ?: when (season.indexNumber) {
            0 -> "Specials"
            null -> "Season"
            else -> "Season ${season.indexNumber}"
        }

    fun episodeCount(season: MediaItem): Int? =
        sequenceOf(season.episodeCount, season.childCount, season.recursiveItemCount)
            .filterNotNull()
            .firstOrNull { it >= 0 }
}

enum class DetailsFocusRegion {
    ACTIONS,
    SEASONS,
    EPISODES
}

enum class DetailsFocusDirection {
    UP,
    DOWN
}

object SeasonFocusPolicy {
    fun target(
        region: DetailsFocusRegion,
        direction: DetailsFocusDirection,
        hasSeasons: Boolean
    ): DetailsFocusRegion? = when (region to direction) {
        DetailsFocusRegion.ACTIONS to DetailsFocusDirection.DOWN ->
            if (hasSeasons) DetailsFocusRegion.SEASONS else null
        DetailsFocusRegion.SEASONS to DetailsFocusDirection.UP -> DetailsFocusRegion.ACTIONS
        DetailsFocusRegion.SEASONS to DetailsFocusDirection.DOWN -> DetailsFocusRegion.EPISODES
        DetailsFocusRegion.EPISODES to DetailsFocusDirection.UP ->
            if (hasSeasons) DetailsFocusRegion.SEASONS else DetailsFocusRegion.ACTIONS
        else -> null
    }
}

object DetailsInitialFocusPolicy {
    /** Details owns focus once opened; global shell navigation is outside this focus scope. */
    fun shouldRequestPrimary(hasFocusInsideDetails: Boolean): Boolean =
        !hasFocusInsideDetails
}

enum class SeasonEpisodeEntryDecision {
    MOVE_TO_EPISODES,
    WAIT_FOR_EPISODES,
    PASS_THROUGH
}

object SeasonEpisodeEntryPolicy {
    fun decide(hasFocusableEpisode: Boolean, episodesLoading: Boolean): SeasonEpisodeEntryDecision =
        when {
            hasFocusableEpisode -> SeasonEpisodeEntryDecision.MOVE_TO_EPISODES
            episodesLoading -> SeasonEpisodeEntryDecision.WAIT_FOR_EPISODES
            else -> SeasonEpisodeEntryDecision.PASS_THROUGH
        }
}

enum class AsyncShelfState {
    LOADING,
    NON_EMPTY,
    EMPTY_OR_ERROR
}

enum class ActionSecondaryFocusDecision {
    MOVE_TO_SEASONS,
    MOVE_TO_RELATED,
    WAIT,
    PASS_THROUGH
}

/**
 * Prevents action-row focus from escaping while the preferred season shelf is unresolved.
 * Related content is eligible only after seasons have definitively settled empty or failed.
 */
object ActionSecondaryFocusPolicy {
    fun decide(
        hasFocusableSeason: Boolean,
        seasonState: AsyncShelfState,
        hasFocusableRelated: Boolean,
        relatedState: AsyncShelfState
    ): ActionSecondaryFocusDecision = when {
        hasFocusableSeason -> ActionSecondaryFocusDecision.MOVE_TO_SEASONS
        seasonState == AsyncShelfState.LOADING ||
            seasonState == AsyncShelfState.NON_EMPTY -> ActionSecondaryFocusDecision.WAIT
        hasFocusableRelated -> ActionSecondaryFocusDecision.MOVE_TO_RELATED
        relatedState == AsyncShelfState.LOADING ||
            relatedState == AsyncShelfState.NON_EMPTY -> ActionSecondaryFocusDecision.WAIT
        else -> ActionSecondaryFocusDecision.PASS_THROUGH
    }
}

/**
 * Unlike the former ordinal state machine, a full item always replaces a lightweight seed.
 */
data class DetailsContentState(
    val item: MediaItem? = null,
    val hasFullDetails: Boolean = false
) {
    fun withSeed(seed: MediaItem): DetailsContentState =
        if (item == null) copy(item = seed) else this

    fun withFullDetails(details: MediaItem): DetailsContentState =
        copy(item = details, hasFullDetails = true)
}

object MediaDetailsSeedStore {
    private const val LIMIT = 32
    private const val MAX_BYTES = 1024L * 1024L
    private const val TTL_MS = 15L * 60L * 1000L

    data class Stats(val entries: Int, val estimatedBytes: Long)

    private data class Entry(
        val item: MediaItem,
        val estimatedBytes: Long,
        val expiresAtMs: Long
    )

    private val entries = LinkedHashMap<String, Entry>(LIMIT, 0.75f, true)
    private var estimatedBytes = 0L

    @Synchronized
    fun put(item: MediaItem, nowMs: Long = System.currentTimeMillis()) {
        removeExpired(nowMs)
        entries.remove(item.id)?.let { estimatedBytes -= it.estimatedBytes }
        val bytes = estimateBytes(item)
        entries[item.id] = Entry(item, bytes, nowMs + TTL_MS)
        estimatedBytes += bytes
        trimToBounds()
    }

    @Synchronized
    fun getItem(itemId: String, nowMs: Long = System.currentTimeMillis()): MediaItem? {
        removeExpired(nowMs)
        return entries[itemId]?.item
    }

    @Synchronized
    fun size(nowMs: Long = System.currentTimeMillis()): Int {
        removeExpired(nowMs)
        return entries.size
    }

    @Synchronized
    fun stats(nowMs: Long = System.currentTimeMillis()): Stats {
        removeExpired(nowMs)
        return Stats(entries.size, estimatedBytes)
    }

    @Synchronized
    fun trimToPercent(percent: Int) {
        val targetBytes = MAX_BYTES * percent.coerceIn(0, 100) / 100L
        val targetEntries = LIMIT * percent.coerceIn(0, 100) / 100
        while (entries.size > targetEntries || estimatedBytes > targetBytes) removeEldest()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        estimatedBytes = 0L
    }

    private fun trimToBounds() {
        while (entries.size > LIMIT || estimatedBytes > MAX_BYTES) removeEldest()
    }

    private fun removeExpired(nowMs: Long) {
        val expired = entries.filterValues { it.expiresAtMs <= nowMs }.keys.toList()
        expired.forEach { key ->
            entries.remove(key)?.let { estimatedBytes -= it.estimatedBytes }
        }
    }

    private fun removeEldest() {
        val eldest = entries.entries.firstOrNull() ?: return
        estimatedBytes -= eldest.value.estimatedBytes
        entries.remove(eldest.key)
    }

    private fun estimateBytes(item: MediaItem): Long {
        fun strings(values: Iterable<String?>): Long = values.filterNotNull().sumOf {
            40L + it.length * 2L
        }
        return 512L + strings(
            listOf(
                item.id, item.title, item.type, item.year, item.imageTag, item.backdropTag,
                item.logoTag, item.seriesName, item.episodeLabel, item.overview,
                item.officialRating, item.container, item.seriesId, item.seasonId, item.album,
                item.albumArtist, item.albumId, item.artistId, item.director,
                item.lastPlayedDate, item.dateCreated, item.thumbImageTag,
                item.parentBackdropItemId, item.parentLogoItemId, item.parentLogoImageTag,
                item.parentPrimaryImageItemId, item.parentPrimaryImageTag,
                item.parentThumbItemId, item.parentThumbImageTag, item.seriesPrimaryImageTag
            )
        ) + strings(item.genres) + strings(item.studios) + strings(item.artists) +
            strings(item.backdropImageTags) + strings(item.parentBackdropImageTags) +
            item.people.sumOf { person ->
                96L + strings(
                    listOf(person.id, person.name, person.role, person.type, person.primaryImageTag)
                )
            } + item.audioTracks.size * 160L + item.subtitleTracks.size * 160L
    }
}

object DetailsNavigationPolicy {
    fun architecture(features: com.piggie.tv.ui.rendering.RendererFeatures, hostAvailable: Boolean): com.piggie.tv.ui.rendering.DetailsArchitecture =
        if (hostAvailable && features.detailsArchitecture == com.piggie.tv.ui.rendering.DetailsArchitecture.IN_HOST_FRAGMENT) {
            com.piggie.tv.ui.rendering.DetailsArchitecture.IN_HOST_FRAGMENT
        } else {
            com.piggie.tv.ui.rendering.DetailsArchitecture.ACTIVITY
        }
}
