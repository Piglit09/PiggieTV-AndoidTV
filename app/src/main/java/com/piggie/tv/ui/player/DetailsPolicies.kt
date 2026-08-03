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
            item.parentPrimaryImageTag?.takeIf(String::isNotBlank)?.let {
                add(DetailsArtworkRef(item.parentPrimaryImageItemId ?: item.seriesId ?: item.id, DetailsArtworkKind.PRIMARY, it))
            }
            item.seriesPrimaryImageTag?.takeIf(String::isNotBlank)?.let {
                add(DetailsArtworkRef(item.seriesId ?: item.id, DetailsArtworkKind.PRIMARY, it))
            }
        } else {
            item.imageTag?.takeIf(String::isNotBlank)?.let { add(DetailsArtworkRef(item.id, DetailsArtworkKind.PRIMARY, it)) }
            item.thumbImageTag?.takeIf(String::isNotBlank)?.let { add(DetailsArtworkRef(item.id, DetailsArtworkKind.THUMB, it)) }
            item.parentPrimaryImageTag?.takeIf(String::isNotBlank)?.let {
                add(DetailsArtworkRef(item.parentPrimaryImageItemId ?: item.id, DetailsArtworkKind.PRIMARY, it))
            }
        }
    }.distinct()

    fun poster(item: MediaItem): DetailsArtworkRef? = posterCandidates(item).firstOrNull()

    fun episodeThumbnailCandidates(item: MediaItem): List<DetailsArtworkRef> = buildList {
        item.thumbImageTag?.takeIf(String::isNotBlank)?.let { add(DetailsArtworkRef(item.id, DetailsArtworkKind.THUMB, it)) }
        item.imageTag?.takeIf(String::isNotBlank)?.let { add(DetailsArtworkRef(item.id, DetailsArtworkKind.PRIMARY, it)) }
        item.parentThumbImageTag?.takeIf(String::isNotBlank)?.let {
            add(DetailsArtworkRef(item.parentThumbItemId ?: item.seriesId ?: item.id, DetailsArtworkKind.THUMB, it))
        }
    }.distinct()

    fun episodeThumbnail(item: MediaItem): DetailsArtworkRef? =
        episodeThumbnailCandidates(item).firstOrNull()

    fun logoCandidates(item: MediaItem): List<DetailsArtworkRef> = buildList {
        if (item.type == "Episode") {
            item.parentLogoImageTag?.takeIf(String::isNotBlank)?.let {
                add(DetailsArtworkRef(item.parentLogoItemId ?: item.seriesId ?: item.id, DetailsArtworkKind.LOGO, it))
            }
        }
        item.logoTag?.takeIf(String::isNotBlank)?.let { add(DetailsArtworkRef(item.id, DetailsArtworkKind.LOGO, it)) }
        if (item.type != "Episode") {
            item.parentLogoImageTag?.takeIf(String::isNotBlank)?.let {
                add(DetailsArtworkRef(item.parentLogoItemId ?: item.seriesId ?: item.id, DetailsArtworkKind.LOGO, it))
            }
        }
    }.distinct()

    fun logo(item: MediaItem): DetailsArtworkRef? = logoCandidates(item).firstOrNull()

    fun backdropCandidates(item: MediaItem): List<DetailsArtworkRef> = buildList {
        val ownTag = item.backdropImageTags.firstOrNull(String::isNotBlank)
            ?: item.backdropTag?.takeIf(String::isNotBlank)
        val parentTag = item.parentBackdropImageTags.firstOrNull(String::isNotBlank)
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

enum class SeasonDetailsClickDecision {
    OPEN_DETAILS,
    IGNORE
}

/**
 * Turns a season card from a Series page into a real nested-details destination.
 *
 * Jellyfin season-list responses are intentionally lightweight and can omit the parent identity
 * and artwork needed while the full Season request is in flight. The containing Series is the
 * authoritative parent for this click, so the seed is normalized before it enters the details
 * stack. Existing Season artwork remains preferred; Series artwork only fills parent fallbacks.
 */
object SeasonDetailsNavigationPolicy {
    fun clickDecision(series: MediaItem, season: MediaItem): SeasonDetailsClickDecision =
        if (validPair(series, season)) {
            SeasonDetailsClickDecision.OPEN_DETAILS
        } else {
            SeasonDetailsClickDecision.IGNORE
        }

    fun routeItem(series: MediaItem, season: MediaItem): MediaItem {
        if (!validPair(series, season)) return season

        val seriesId = series.id.trim()
        val seasonId = season.id.trim()
        val parentBackdropTags = season.parentBackdropImageTags.nonBlankTags().ifEmpty {
            series.backdropImageTags.nonBlankTags().ifEmpty {
                listOfNotNull(series.backdropTag.nonBlank())
            }
        }
        val parentLogoTag = season.parentLogoImageTag.nonBlank() ?: series.logoTag.nonBlank()
        val parentPrimaryTag = season.parentPrimaryImageTag.nonBlank() ?: series.imageTag.nonBlank()
        val parentThumbTag = season.parentThumbImageTag.nonBlank() ?: series.thumbImageTag.nonBlank()

        return season.copy(
            id = seasonId,
            seriesId = seriesId,
            seriesName = season.seriesName.nonBlank() ?: series.title.nonBlank(),
            parentBackdropItemId = parentBackdropTags.takeIf { it.isNotEmpty() }?.let { seriesId },
            parentBackdropImageTags = parentBackdropTags,
            parentLogoItemId = parentLogoTag?.let { seriesId },
            parentLogoImageTag = parentLogoTag,
            parentPrimaryImageItemId = parentPrimaryTag?.let { seriesId },
            parentPrimaryImageTag = parentPrimaryTag,
            parentThumbItemId = parentThumbTag?.let { seriesId },
            parentThumbImageTag = parentThumbTag,
            seriesPrimaryImageTag = season.seriesPrimaryImageTag.nonBlank() ?: series.imageTag.nonBlank()
        )
    }

    private fun validPair(series: MediaItem, season: MediaItem): Boolean {
        val seriesId = series.id.trim()
        val seasonId = season.id.trim()
        return series.type.equals("Series", ignoreCase = true) &&
            season.type.equals("Season", ignoreCase = true) &&
            seriesId.isNotEmpty() &&
            seasonId.isNotEmpty() &&
            seriesId != seasonId
    }

    private fun String?.nonBlank(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private fun List<String>.nonBlankTags(): List<String> =
        map(String::trim).filter(String::isNotEmpty).distinct()
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

/**
 * Resolves the valid parent-Series destination exposed from nested Episode or Season details.
 * The established name is retained because Episode was the first supported nested item type.
 */
object EpisodeSeriesNavigationPolicy {
    fun seriesId(item: MediaItem): String? {
        if (
            !item.type.equals("Episode", ignoreCase = true) &&
            !item.type.equals("Season", ignoreCase = true)
        ) return null
        return item.seriesId
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != item.id.trim() }
    }
}

/** Only concrete video items own playback-track discovery and video actions. */
object DetailsPlaybackMetadataPolicy {
    fun needsVideoTracks(item: MediaItem): Boolean =
        item.type.equals("Movie", ignoreCase = true) ||
            item.type.equals("Episode", ignoreCase = true) ||
            item.type.equals("Video", ignoreCase = true)
}

enum class DetailsSecondaryLoadKind {
    SERIES_SEASONS,
    SEASON_EPISODES,
    RELATED_ONLY
}

data class DetailsSecondaryLoadDecision(
    val kind: DetailsSecondaryLoadKind,
    val seriesId: String? = null,
    val seasonId: String? = null
)

/**
 * Keeps secondary loading aligned with the details item currently on the stack. Series pages own
 * the season browser; Season pages own one episode list; every other page only needs related data.
 */
object DetailsSecondaryLoadingPolicy {
    fun decide(item: MediaItem): DetailsSecondaryLoadDecision {
        val itemId = item.id.trim()
        if (item.type.equals("Series", ignoreCase = true) && itemId.isNotEmpty()) {
            return DetailsSecondaryLoadDecision(
                kind = DetailsSecondaryLoadKind.SERIES_SEASONS,
                seriesId = itemId
            )
        }
        if (item.type.equals("Season", ignoreCase = true) && itemId.isNotEmpty()) {
            return DetailsSecondaryLoadDecision(
                kind = DetailsSecondaryLoadKind.SEASON_EPISODES,
                seriesId = EpisodeSeriesNavigationPolicy.seriesId(item),
                seasonId = itemId
            )
        }
        return DetailsSecondaryLoadDecision(DetailsSecondaryLoadKind.RELATED_ONLY)
    }
}

/**
 * A lightweight discovery item can contain episode ancestry that a later details response omits.
 * Keep that route identity while still allowing every value returned by full details to win.
 */
object DetailsNavigationMetadataPolicy {
    fun merge(previous: MediaItem?, details: MediaItem): MediaItem {
        if (previous?.id != details.id) return details
        return details.copy(
            imageTag = details.imageTag ?: previous.imageTag,
            backdropTag = details.backdropTag ?: previous.backdropTag,
            logoTag = details.logoTag ?: previous.logoTag,
            seriesId = details.seriesId ?: previous.seriesId,
            seasonId = details.seasonId ?: previous.seasonId,
            seriesName = details.seriesName ?: previous.seriesName,
            indexNumber = details.indexNumber ?: previous.indexNumber,
            parentIndexNumber = details.parentIndexNumber ?: previous.parentIndexNumber,
            childCount = details.childCount ?: previous.childCount,
            episodeCount = details.episodeCount ?: previous.episodeCount,
            recursiveItemCount = details.recursiveItemCount ?: previous.recursiveItemCount,
            episodeLabel = details.episodeLabel ?: previous.episodeLabel,
            backdropImageTags = details.backdropImageTags.ifEmpty { previous.backdropImageTags },
            thumbImageTag = details.thumbImageTag ?: previous.thumbImageTag,
            parentBackdropItemId = details.parentBackdropItemId ?: previous.parentBackdropItemId,
            parentBackdropImageTags = details.parentBackdropImageTags.ifEmpty { previous.parentBackdropImageTags },
            parentLogoItemId = details.parentLogoItemId ?: previous.parentLogoItemId,
            parentLogoImageTag = details.parentLogoImageTag ?: previous.parentLogoImageTag,
            parentPrimaryImageItemId = details.parentPrimaryImageItemId ?: previous.parentPrimaryImageItemId,
            parentPrimaryImageTag = details.parentPrimaryImageTag ?: previous.parentPrimaryImageTag,
            parentThumbItemId = details.parentThumbItemId ?: previous.parentThumbItemId,
            parentThumbImageTag = details.parentThumbImageTag ?: previous.parentThumbImageTag,
            seriesPrimaryImageTag = details.seriesPrimaryImageTag ?: previous.seriesPrimaryImageTag
        )
    }
}

object DetailsTimePolicy {
    private const val TICKS_PER_MINUTE = 600_000_000L
    private const val TICKS_PER_MILLISECOND = 10_000L

    fun playTime(runtimeTicks: Long): String? {
        if (runtimeTicks <= 0L) return null
        val totalMinutes = runtimeTicks / TICKS_PER_MINUTE
        return String.format(java.util.Locale.US, "%02d:%02d", totalMinutes / 60L, totalMinutes % 60L)
    }

    fun endsAtMillis(nowMillis: Long, runtimeTicks: Long, playbackPositionTicks: Long): Long? {
        if (runtimeTicks <= 0L) return null
        val remainingTicks = (runtimeTicks - playbackPositionTicks.coerceAtLeast(0L)).coerceAtLeast(0L)
        return nowMillis + remainingTicks / TICKS_PER_MILLISECOND
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
