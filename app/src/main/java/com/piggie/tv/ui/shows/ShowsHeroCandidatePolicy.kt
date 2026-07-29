package com.piggie.tv.ui.shows

import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.ui.hero.HeroCandidate
import com.piggie.tv.ui.hero.HeroSource

/**
 * Plans the bounded parent lookups required before a Next Up episode becomes a hero candidate.
 * Series ids are kept in episode order so the first visible hero resolves first.
 */
internal object ShowsHeroCandidatePolicy {
    const val MAX_PARENT_SERIES_LOOKUPS = 8

    fun parentSeriesIds(
        episodes: List<MediaItem>,
        limit: Int = MAX_PARENT_SERIES_LOOKUPS
    ): List<String> = episodes
        .asSequence()
        .filter { it.type.equals("Episode", ignoreCase = true) }
        .mapNotNull(::parentSeriesId)
        .distinct()
        .take(limit.coerceIn(0, MAX_PARENT_SERIES_LOOKUPS))
        .toList()

    /**
     * A candidate with a known parent is withheld until that parent lookup has either succeeded
     * or failed. A failed lookup deliberately falls back to the parent artwork fields carried by
     * the episode; an absent map key still means "not resolved yet."
     */
    fun readyCandidates(
        episodes: List<MediaItem>,
        resolvedSeries: Map<String, MediaItem?>
    ): List<HeroCandidate> = episodes.mapNotNull { episode ->
        val parentId = parentSeriesId(episode)
        if (parentId != null && !resolvedSeries.containsKey(parentId)) {
            return@mapNotNull null
        }

        val authoritativeSeries = parentId
            ?.let(resolvedSeries::get)
            ?.takeIf {
                it.id == parentId &&
                    it.type.equals("Series", ignoreCase = true)
            }
        val inferredSeries = inferredSeriesArtwork(episode, parentId)
        val artwork = authoritativeSeries ?: inferredSeries ?: episode
        val fallbacks = buildList {
            if (authoritativeSeries != null && inferredSeries != null) add(inferredSeries)
            if (episode.id != artwork.id) add(episode)
        }

        HeroCandidate(
            item = episode,
            source = HeroSource.NEXT_UP,
            artworkItem = artwork,
            fallbackArtworkItems = fallbacks.distinctBy(MediaItem::id)
        )
    }

    private fun parentSeriesId(episode: MediaItem): String? {
        if (!episode.type.equals("Episode", ignoreCase = true)) return null
        return episode.seriesId?.takeIf(String::isNotBlank)
            ?: episode.parentBackdropItemId?.takeIf(String::isNotBlank)
    }

    private fun inferredSeriesArtwork(
        episode: MediaItem,
        parentId: String?
    ): MediaItem? = parentId?.let { seriesId ->
        episode.copy(
            id = episode.parentBackdropItemId ?: seriesId,
            title = episode.seriesName ?: episode.title,
            type = "Series",
            imageTag = episode.seriesPrimaryImageTag ?: episode.parentPrimaryImageTag,
            backdropTag = episode.parentBackdropImageTags.firstOrNull(),
            logoTag = episode.parentLogoImageTag,
            backdropImageTags = episode.parentBackdropImageTags
        )
    }
}
