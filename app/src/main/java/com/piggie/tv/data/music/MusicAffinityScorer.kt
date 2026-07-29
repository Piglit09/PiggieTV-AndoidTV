package com.piggie.tv.data.music

enum class MusicAffinityKind {
    ARTIST,
    ALBUM
}

data class MusicAffinityCandidate(
    val id: String,
    val title: String,
    val kind: MusicAffinityKind,
    val playCount: Int = 0,
    val recentArtistPlays: Int = 0,
    val recentAlbumPlays: Int = 0,
    val completedTracks: Int = 0,
    val isFavorite: Boolean = false,
    val lastPlayedAtMs: Long? = null,
    val addedAtMs: Long? = null
)

enum class MusicAffinityReason(val diagnosticLabel: String) {
    RECENTLY_PLAYED_ARTIST("recently_played_artist"),
    FREQUENTLY_PLAYED_ARTIST("frequently_played_artist"),
    RECENTLY_PLAYED_ALBUM("recently_played_album"),
    FAVORITE_ARTIST("favorite_artist"),
    RECENTLY_ADDED_ALBUM("recently_added_album")
}

data class MusicAffinitySelection(
    val candidate: MusicAffinityCandidate,
    val reason: MusicAffinityReason,
    val score: Long
) {
    val diagnosticSummary: String
        get() = "reason=${reason.diagnosticLabel},item=${candidate.id},score=$score"
}

/**
 * Selects a Music hero using explicit priority tiers. Scores only compare
 * candidates inside a tier, so a high album play count can never outrank a
 * recently played artist.
 */
object MusicAffinityScorer {
    fun reasonFor(candidate: MusicAffinityCandidate): MusicAffinityReason? = when {
        candidate.kind == MusicAffinityKind.ARTIST &&
            candidate.recentArtistPlays > 0 ->
            MusicAffinityReason.RECENTLY_PLAYED_ARTIST
        candidate.kind == MusicAffinityKind.ARTIST &&
            (candidate.playCount > 0 || candidate.completedTracks > 0) ->
            MusicAffinityReason.FREQUENTLY_PLAYED_ARTIST
        candidate.kind == MusicAffinityKind.ALBUM &&
            candidate.recentAlbumPlays > 0 ->
            MusicAffinityReason.RECENTLY_PLAYED_ALBUM
        candidate.kind == MusicAffinityKind.ARTIST && candidate.isFavorite ->
            MusicAffinityReason.FAVORITE_ARTIST
        candidate.kind == MusicAffinityKind.ALBUM && candidate.addedAtMs != null ->
            MusicAffinityReason.RECENTLY_ADDED_ALBUM
        else -> null
    }

    fun select(candidates: List<MusicAffinityCandidate>): MusicAffinitySelection? {
        selectBest(
            candidates = candidates.filter {
                it.kind == MusicAffinityKind.ARTIST && it.recentArtistPlays > 0
            },
            reason = MusicAffinityReason.RECENTLY_PLAYED_ARTIST,
            primary = { it.lastPlayedAtMs ?: 0L },
            secondary = { it.recentArtistPlays.toLong().nonNegative() },
            tertiary = { it.playCount.toLong().nonNegative() }
        )?.let { return it }

        selectBest(
            candidates = candidates.filter {
                it.kind == MusicAffinityKind.ARTIST &&
                    (it.playCount > 0 || it.completedTracks > 0)
            },
            reason = MusicAffinityReason.FREQUENTLY_PLAYED_ARTIST,
            primary = { engagementScore(it) },
            secondary = { it.playCount.toLong().nonNegative() },
            tertiary = { it.completedTracks.toLong().nonNegative() }
        )?.let { return it }

        selectBest(
            candidates = candidates.filter {
                it.kind == MusicAffinityKind.ALBUM && it.recentAlbumPlays > 0
            },
            reason = MusicAffinityReason.RECENTLY_PLAYED_ALBUM,
            primary = { it.lastPlayedAtMs ?: 0L },
            secondary = { it.recentAlbumPlays.toLong().nonNegative() },
            tertiary = { it.playCount.toLong().nonNegative() }
        )?.let { return it }

        selectBest(
            candidates = candidates.filter {
                it.kind == MusicAffinityKind.ARTIST && it.isFavorite
            },
            reason = MusicAffinityReason.FAVORITE_ARTIST,
            primary = { engagementScore(it) },
            secondary = { it.lastPlayedAtMs ?: 0L },
            tertiary = { it.completedTracks.toLong().nonNegative() }
        )?.let { return it }

        return selectBest(
            candidates = candidates.filter {
                it.kind == MusicAffinityKind.ALBUM && it.addedAtMs != null
            },
            reason = MusicAffinityReason.RECENTLY_ADDED_ALBUM,
            primary = { it.addedAtMs ?: 0L },
            secondary = { it.playCount.toLong().nonNegative() },
            tertiary = { it.completedTracks.toLong().nonNegative() }
        )
    }

    private fun selectBest(
        candidates: List<MusicAffinityCandidate>,
        reason: MusicAffinityReason,
        primary: (MusicAffinityCandidate) -> Long,
        secondary: (MusicAffinityCandidate) -> Long,
        tertiary: (MusicAffinityCandidate) -> Long
    ): MusicAffinitySelection? {
        val selected = candidates.minWithOrNull(
            compareByDescending<MusicAffinityCandidate>(primary)
                .thenByDescending(secondary)
                .thenByDescending(tertiary)
                .thenBy { it.id }
        ) ?: return null
        return MusicAffinitySelection(
            candidate = selected,
            reason = reason,
            score = primary(selected)
        )
    }

    private fun engagementScore(candidate: MusicAffinityCandidate): Long =
        candidate.playCount.toLong().nonNegative() * 10L +
            candidate.completedTracks.toLong().nonNegative() * 3L +
            candidate.recentArtistPlays.toLong().nonNegative() * 5L

    private fun Long.nonNegative(): Long = coerceAtLeast(0L)
}
