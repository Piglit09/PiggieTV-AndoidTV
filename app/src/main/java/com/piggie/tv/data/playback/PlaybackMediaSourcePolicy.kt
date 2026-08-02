package com.piggie.tv.data.playback

/** A JSON-free description of one source returned by Jellyfin PlaybackInfo. */
data class PlaybackMediaSourceCandidate(
    val id: String,
    val sourceIndex: Int,
    val supportsDirectPlay: Boolean,
    val supportsDirectStream: Boolean,
    val supportsTranscoding: Boolean,
    val bitrate: Long? = null,
    val audioStreamIndices: Set<Int> = emptySet(),
    val subtitleStreamIndices: Set<Int> = emptySet()
) {
    init {
        require(id.isNotBlank()) { "Media source id must not be blank" }
        require(sourceIndex >= 0) { "sourceIndex must be non-negative" }
        require(bitrate == null || bitrate >= 0L) { "bitrate must be non-negative" }
    }
}

data class PlaybackMediaSourcePlan(
    val source: PlaybackMediaSourceCandidate,
    val route: PlaybackRoute
)

/**
 * Produces an ordered failover list of sources which can actually satisfy the requested route.
 * A preferred source wins only when it remains playable and owns the requested stream indices.
 */
object PlaybackMediaSourcePolicy {
    fun orderedPlans(
        candidates: List<PlaybackMediaSourceCandidate>,
        preferredMediaSourceId: String? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        maxAllowedBitrate: Long? = null
    ): List<PlaybackMediaSourcePlan> {
        val hasExplicitTrack = audioStreamIndex != null || subtitleStreamIndex != null
        // Stream indices are scoped to a MediaSource. A matching integer in a different source is
        // not proof that it represents the same language/edition, so explicit choices stay pinned.
        if (hasExplicitTrack && preferredMediaSourceId.isNullOrBlank()) return emptyList()

        return candidates
        .asSequence()
        .filter { candidate ->
            !hasExplicitTrack || candidate.id.equals(preferredMediaSourceId, ignoreCase = true)
        }
        .mapNotNull { candidate ->
            if (audioStreamIndex != null && audioStreamIndex !in candidate.audioStreamIndices) {
                return@mapNotNull null
            }
            if (
                subtitleStreamIndex != null &&
                subtitleStreamIndex !in candidate.subtitleStreamIndices
            ) {
                return@mapNotNull null
            }

            val bitrateCapExceeded = maxAllowedBitrate != null &&
                candidate.bitrate != null &&
                candidate.bitrate > maxAllowedBitrate
            val route = PlaybackRoutePolicy.choose(
                supportsDirectPlay = candidate.supportsDirectPlay,
                supportsDirectStream = candidate.supportsDirectStream,
                hasExplicitAudioSelection = audioStreamIndex != null,
                hasExplicitSubtitleSelection = subtitleStreamIndex != null,
                bitrateCapExceeded = bitrateCapExceeded
            )
            if (route == PlaybackRoute.TRANSCODE && !candidate.supportsTranscoding) {
                return@mapNotNull null
            }
            PlaybackMediaSourcePlan(candidate, route)
        }
        .sortedWith(
            compareByDescending<PlaybackMediaSourcePlan> {
                preferredMediaSourceId != null &&
                    it.source.id.equals(preferredMediaSourceId, ignoreCase = true)
            }
                .thenByDescending { routePreference(it.route) }
                .thenBy { it.source.sourceIndex }
                .thenBy { it.source.id }
        )
        .toList()
    }

    fun choose(
        candidates: List<PlaybackMediaSourceCandidate>,
        preferredMediaSourceId: String? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        maxAllowedBitrate: Long? = null
    ): PlaybackMediaSourcePlan? = orderedPlans(
        candidates = candidates,
        preferredMediaSourceId = preferredMediaSourceId,
        audioStreamIndex = audioStreamIndex,
        subtitleStreamIndex = subtitleStreamIndex,
        maxAllowedBitrate = maxAllowedBitrate
    ).firstOrNull()

    private fun routePreference(route: PlaybackRoute): Int = when (route) {
        PlaybackRoute.DIRECT_PLAY -> 3
        PlaybackRoute.DIRECT_STREAM -> 2
        PlaybackRoute.TRANSCODE -> 1
    }
}
