package com.piggie.tv.ui.hero

import com.piggie.tv.data.models.MediaItem

enum class HeroRoute {
    HOME,
    MOVIES,
    SHOWS,
    MUSIC
}

enum class HeroSource {
    CONTINUE,
    NEXT_UP,
    RECOMMENDED,
    POPULAR,
    RECENT,
    LIBRARY
}

enum class HeroChangeReason(val wireName: String) {
    INITIAL("initial"),
    POOL_UPDATE("pool_update"),
    STABLE_FOCUS("stable_focus"),
    ROTATION("rotation"),
    REFRESH("refresh"),
    UNAVAILABLE("unavailable")
}

data class HeroCandidate(
    val item: MediaItem,
    val source: HeroSource,
    val artworkItem: MediaItem = item,
    val fallbackArtworkItems: List<MediaItem> = emptyList(),
    val diagnosticSelectionBasis: String? = null
)

data class HeroState(
    val route: HeroRoute,
    val pool: List<HeroCandidate> = emptyList(),
    val current: HeroCandidate? = null,
    val selectedIndex: Int = -1,
    val previousItemId: String? = null,
    val changeReason: HeroChangeReason = HeroChangeReason.INITIAL,
    val lastChangeTimestampMs: Long = 0L,
    val visiblePercent: Int = 0,
    val scrollPositionPx: Int = 0
) {
    val next: HeroCandidate?
        get() = when {
            pool.size < 2 || selectedIndex !in pool.indices -> null
            else -> pool[(selectedIndex + 1) % pool.size]
        }
}

object HeroPoolPolicy {
    const val MAX_POOL_SIZE = 12

    fun accepts(route: HeroRoute, item: MediaItem): Boolean = when (route) {
        HeroRoute.HOME -> item.type in setOf("Movie", "Series", "Episode")
        HeroRoute.MOVIES -> item.type == "Movie"
        HeroRoute.SHOWS -> item.type in setOf("Series", "Episode")
        HeroRoute.MUSIC -> item.type in setOf("MusicArtist", "MusicAlbum", "Playlist", "Audio")
    }

    fun accepts(route: HeroRoute, source: HeroSource, item: MediaItem): Boolean =
        accepts(route, item) &&
            (
                route != HeroRoute.SHOWS ||
                    item.type == "Series" ||
                    source == HeroSource.NEXT_UP ||
                    source == HeroSource.CONTINUE
                )

    fun sourcePriority(route: HeroRoute, source: HeroSource): Int = when (route) {
        HeroRoute.HOME -> when (source) {
            HeroSource.CONTINUE -> 0
            HeroSource.RECOMMENDED -> 10
            HeroSource.RECENT -> 20
            HeroSource.POPULAR -> 30
            HeroSource.NEXT_UP -> 40
            HeroSource.LIBRARY -> 50
        }
        HeroRoute.MOVIES -> when (source) {
            HeroSource.CONTINUE -> 0
            HeroSource.RECOMMENDED -> 10
            HeroSource.POPULAR -> 20
            HeroSource.RECENT -> 30
            else -> 50
        }
        HeroRoute.SHOWS -> when (source) {
            HeroSource.NEXT_UP -> 0
            HeroSource.CONTINUE -> 10
            HeroSource.RECOMMENDED -> 20
            HeroSource.POPULAR -> 30
            HeroSource.RECENT -> 40
            HeroSource.LIBRARY -> 50
        }
        HeroRoute.MUSIC -> when (source) {
            HeroSource.CONTINUE -> 0
            HeroSource.RECENT -> 10
            HeroSource.RECOMMENDED -> 20
            HeroSource.POPULAR -> 30
            else -> 50
        }
    }

    fun build(
        route: HeroRoute,
        candidatesBySource: Map<HeroSource, List<HeroCandidate>>,
        limit: Int = MAX_POOL_SIZE
    ): List<HeroCandidate> = candidatesBySource.entries
        .sortedBy { sourcePriority(route, it.key) }
        .asSequence()
        .flatMap { (_, candidates) -> candidates.asSequence() }
        .filter { accepts(route, it.source, it.item) }
        .filterNot {
            it.source == HeroSource.CONTINUE &&
                (it.item.playbackPositionTicks <= 0L || it.item.isPlayed)
        }
        .distinctBy { it.item.id }
        .take(limit.coerceAtLeast(1))
        .toList()

    fun nextIndex(currentIndex: Int, poolSize: Int): Int = when {
        poolSize <= 0 -> -1
        poolSize == 1 -> 0
        currentIndex !in 0 until poolSize -> 0
        else -> (currentIndex + 1) % poolSize
    }
}

object HeroSelectionPolicy {
    fun retainedIndex(pool: List<HeroCandidate>, currentItemId: String?): Int =
        currentItemId?.let { id -> pool.indexOfFirst { it.item.id == id } } ?: -1

    /**
     * Selects after an incremental source update.
     *
     * A newly available, strictly higher-priority source wins even when focus or
     * rotation changed the current hero while that source was still loading.
     * Re-submitting the same source set keeps the retained item, which also
     * preserves the hero across details returns and equal-priority refreshes.
     */
    fun indexAfterPoolUpdate(
        route: HeroRoute,
        previousPool: List<HeroCandidate>,
        updatedPool: List<HeroCandidate>,
        currentItemId: String?
    ): Int {
        if (updatedPool.isEmpty()) return -1
        val retained = retainedIndex(updatedPool, currentItemId)
        if (retained < 0) return 0

        val previousBestPriority = previousPool
            .minOfOrNull { HeroPoolPolicy.sourcePriority(route, it.source) }
        val updatedBestPriority = updatedPool
            .minOf { HeroPoolPolicy.sourcePriority(route, it.source) }
        val newlyHigherPrioritySource =
            previousBestPriority != null && updatedBestPriority < previousBestPriority

        return if (newlyHigherPrioritySource) 0 else retained
    }
}

object HeroRotationPolicy {
    const val FOCUS_DEBOUNCE_MS = 650L
    const val ROTATION_INTERVAL_MS = 25_000L

    fun shouldSchedule(
        poolSize: Int,
        visiblePercent: Int,
        lifecycleResumed: Boolean,
        controlsFocused: Boolean,
        reduceMotion: Boolean
    ): Boolean =
        poolSize > 1 &&
            visiblePercent > 0 &&
            lifecycleResumed &&
            !controlsFocused &&
            !reduceMotion
}
