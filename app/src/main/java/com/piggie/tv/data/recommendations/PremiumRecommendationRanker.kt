package com.piggie.tv.data.recommendations

import com.piggie.tv.data.models.MediaItem
import java.util.Locale

enum class PremiumRecommendationSurface(
    val policy: PremiumRecommendationPolicy
) {
    HOME(PremiumRecommendationPolicy(70, 70, 70)),
    MADE_FOR_YOU(PremiumRecommendationPolicy(70, 70, 70)),
    RADIO(
        PremiumRecommendationPolicy(
            preferredFamiliarPercent = 25,
            minFamiliarPercent = 25,
            maxFamiliarPercent = 25,
            seedArtistAffinityBoost = 225.0,
            seedAlbumAffinityBoost = 325.0
        )
    ),
    RELATED(PremiumRecommendationPolicy(60, 60, 60)),
    DISCOVERY(PremiumRecommendationPolicy(20, 20, 20))
}

data class PremiumRecommendationPolicy(
    val preferredFamiliarPercent: Int,
    val minFamiliarPercent: Int,
    val maxFamiliarPercent: Int,
    val maxConsecutiveArtist: Int = 2,
    val maxConsecutiveAlbum: Int = 2,
    val seedArtistAffinityBoost: Double = 92.0,
    val seedAlbumAffinityBoost: Double = 110.0
) {
    init {
        require(minFamiliarPercent in 0..100)
        require(preferredFamiliarPercent in minFamiliarPercent..maxFamiliarPercent)
        require(maxFamiliarPercent in 0..100)
        require(maxConsecutiveArtist > 0)
        require(maxConsecutiveAlbum > 0)
    }

    fun familiarTarget(limit: Int): Int {
        val safeLimit = limit.coerceAtLeast(0)
        val minimum = (safeLimit * minFamiliarPercent + 99) / 100
        val maximum = maxOf(minimum, safeLimit * maxFamiliarPercent / 100)
        val preferred = (safeLimit * preferredFamiliarPercent + 50) / 100
        return preferred.coerceIn(minimum, maximum.coerceAtMost(safeLimit))
    }
}

/**
 * Deterministic, client-side quality pass for Jellyfin's server-ranked candidates.
 *
 * Jellyfin remains the candidate source. This pass removes consumed/duplicate cards, keeps the
 * server's relevance signal, and applies the repetition and sequencing rules shared by PiggieTV
 * recommendation surfaces.
 */
object PremiumRecommendationRanker {
    fun rankSuggestions(candidates: List<MediaItem>, limit: Int): List<MediaItem> {
        val safeLimit = limit.coerceAtLeast(1)
        val ranked = candidates.asSequence()
            .filter { it.id.isNotBlank() && !it.isPlayed }
            .distinctBy(MediaItem::id)
            .mapIndexed { index, item ->
                RankedCandidate(
                    item = item,
                    baseScore = suggestionBaseScore(item, index),
                    familiar = item.isFavorite || item.playCount > 0,
                    tieBreaker = stableHash("suggestion:${item.id}")
                )
            }
            .toList()

        return selectWithSurfacePolicy(
            candidates = ranked,
            limit = safeLimit,
            policy = PremiumRecommendationSurface.HOME.policy
        ) { selected, candidate ->
            suggestionDiversityAdjustment(selected, candidate.item)
        }
    }

    fun rankRelated(
        seed: MediaItem,
        candidates: List<MediaItem>,
        allowedTypes: Set<String>,
        limit: Int
    ): List<MediaItem> {
        val normalizedAllowedTypes = allowedTypes.mapTo(hashSetOf()) { normalize(it) }
        val ranked = candidates.asSequence()
            .filter { it.id.isNotBlank() && it.id != seed.id }
            .filter { normalize(it.type) in normalizedAllowedTypes }
            .distinctBy(MediaItem::id)
            .mapIndexed { index, item ->
                val affinity = relatedAffinity(seed, item)
                RankedCandidate(
                    item = item,
                    baseScore = affinity + serverOrderBonus(index),
                    affinityScore = affinity,
                    familiar = isFamiliarToSeed(seed, item),
                    tieBreaker = stableHash("related:${seed.id}:${item.id}")
                )
            }
            .sortedWith(candidateComparator)
            .toList()
        val hasUsableMetadata = musicTerms(seed).isNotEmpty()
        val relevant = if (hasUsableMetadata && ranked.any { it.affinityScore > 0.0 }) {
            ranked.filter { it.affinityScore > 0.0 }
        } else {
            ranked
        }

        val safeLimit = limit.coerceAtLeast(1)
        return if (isMusicItem(seed) && relevant.any { isMusicItem(it.item) }) {
            selectMusicCandidates(
                ranked = relevant,
                limit = safeLimit,
                policy = PremiumRecommendationSurface.RELATED.policy,
                pinnedSeedId = null
            ).map(RankedCandidate::item)
        } else {
            selectWithSurfacePolicy(
                candidates = relevant,
                limit = safeLimit,
                policy = PremiumRecommendationSurface.RELATED.policy
            ) { selected, candidate ->
                suggestionDiversityAdjustment(selected, candidate.item)
            }
        }
    }

    fun rankMusicMix(
        seed: MediaItem,
        candidates: List<MediaItem>,
        limit: Int,
        sessionSeed: String = "stable",
        surface: PremiumRecommendationSurface = PremiumRecommendationSurface.MADE_FOR_YOU
    ): List<MediaItem> {
        val safeLimit = limit.coerceIn(1, MAX_MIX_TRACKS)
        val policy = surface.policy
        val unique = (listOf(seed) + candidates).asSequence()
            .filter { it.type.equals("Audio", ignoreCase = true) }
            .filter { it.id.isNotBlank() }
            .distinctBy(MediaItem::id)
            .toList()
        val ranked = unique.mapIndexed { index, item ->
            val affinity = if (item.id == seed.id) SEED_SCORE else musicAffinity(seed, item, policy)
            RankedCandidate(
                item = item,
                baseScore = affinity + musicDiscoveryScore(item) + serverOrderBonus(index),
                affinityScore = affinity,
                familiar = item.id == seed.id || item.isFavorite || item.playCount > 0,
                tieBreaker = stableHash("mix:$sessionSeed:${seed.id}:${item.id}")
            )
        }
        if (ranked.isEmpty()) return emptyList()

        return selectMusicCandidates(
            ranked = ranked,
            limit = safeLimit,
            policy = policy,
            pinnedSeedId = seed.id
        ).map(RankedCandidate::item)
    }

    private fun selectMusicCandidates(
        ranked: List<RankedCandidate>,
        limit: Int,
        policy: PremiumRecommendationPolicy,
        pinnedSeedId: String?
    ): List<RankedCandidate> {
        val selected = mutableListOf<RankedCandidate>()
        val remaining = ranked.toMutableList()
        val seedCandidate = pinnedSeedId?.let { id -> remaining.firstOrNull { it.item.id == id } }
        if (seedCandidate != null) {
            selected += seedCandidate
            remaining.remove(seedCandidate)
        }

        val familiarTarget = policy.familiarTarget(limit)
        val discoveryTarget = limit - familiarTarget
        val artistLimit = maxOf(2, (limit + 2) / 3)
        var familiarCount = selected.count(RankedCandidate::familiar)
        var discoveryCount = selected.count { !it.familiar }

        while (selected.size < limit && remaining.isNotEmpty()) {
            val selectedBucketCount = familiarCount + discoveryCount
            val expectedFamiliarByNext = ((selectedBucketCount + 1) * familiarTarget + limit - 1) / limit
            val preferFamiliar = when {
                familiarCount >= familiarTarget -> false
                discoveryCount >= discoveryTarget -> true
                else -> familiarCount < expectedFamiliarByNext
            }
            val preferred = remaining.filter { candidate -> candidate.familiar == preferFamiliar }
            val strict = preferred.filter { candidate ->
                respectsMusicConcentration(selected, candidate, artistLimit, policy)
            }
            val pool = strict.ifEmpty {
                remaining.filter { candidate ->
                    respectsMusicConcentration(selected, candidate, artistLimit, policy)
                }
            }.ifEmpty { preferred }.ifEmpty { remaining }
            val next = pool.maxWithOrNull(
                compareBy<RankedCandidate> {
                    it.baseScore + musicTransitionAdjustment(selected, it)
                }.thenBy(RankedCandidate::tieBreaker)
            ) ?: break

            selected += next
            remaining.remove(next)
            if (next.familiar) familiarCount += 1 else discoveryCount += 1
        }

        return selected
    }

    private fun suggestionBaseScore(item: MediaItem, index: Int): Double {
        val rating = (item.communityRating ?: 0f).coerceIn(0f, 10f) * 2.0
        val critic = (item.criticRating ?: 0f).coerceIn(0f, 100f) * 0.05
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val age = item.productionYear?.let { (currentYear - it).coerceAtLeast(0) }
        val freshness = when (age) {
            0 -> 8.0
            1 -> 6.0
            2 -> 4.0
            in 3..5 -> 2.0
            else -> 0.0
        }
        return serverOrderBonus(index) + rating + critic + freshness + if (item.isFavorite) 3.0 else 0.0
    }

    private fun suggestionDiversityAdjustment(
        selected: List<RankedCandidate>,
        candidate: MediaItem
    ): Double {
        if (selected.isEmpty()) return 0.0
        val recent = selected.takeLast(DIVERSITY_WINDOW).map(RankedCandidate::item)
        val candidateGenres = normalized(candidate.genres)
        val candidateStudios = normalized(candidate.studios)
        val seriesPenalty = if (
            !candidate.seriesId.isNullOrBlank() &&
            recent.any { it.seriesId == candidate.seriesId }
        ) SERIES_REPEAT_PENALTY else 0.0
        val genrePenalty = recent.sumOf { other ->
            candidateGenres.intersect(normalized(other.genres)).size
        } * GENRE_REPEAT_PENALTY
        val studioPenalty = recent.sumOf { other ->
            candidateStudios.intersect(normalized(other.studios)).size
        } * STUDIO_REPEAT_PENALTY
        return -(seriesPenalty + genrePenalty + studioPenalty)
    }

    private fun relatedAffinity(seed: MediaItem, candidate: MediaItem): Double {
        val genreMatches = normalized(seed.genres).intersect(normalized(candidate.genres)).size
        val studioMatches = normalized(seed.studios).intersect(normalized(candidate.studios)).size
        val peopleMatches = seed.people.mapTo(hashSetOf()) { normalize(it.name) }
            .intersect(candidate.people.mapTo(hashSetOf()) { normalize(it.name) }).size
        val artistMatches = musicArtistKeys(seed).intersect(musicArtistKeys(candidate)).size
        val sameAlbum = musicAlbumKey(seed).isNotBlank() && musicAlbumKey(seed) == musicAlbumKey(candidate)
        return genreMatches * 22.0 +
            studioMatches * 16.0 +
            peopleMatches * 10.0 +
            artistMatches * 36.0 +
            if (sameAlbum) 48.0 else 0.0
    }

    private fun musicAffinity(
        seed: MediaItem,
        candidate: MediaItem,
        policy: PremiumRecommendationPolicy
    ): Double {
        val artistMatches = musicArtistKeys(seed).intersect(musicArtistKeys(candidate)).size
        val genreMatches = normalized(seed.genres).intersect(normalized(candidate.genres)).size
        val sameAlbum = musicAlbumKey(seed).isNotBlank() && musicAlbumKey(seed) == musicAlbumKey(candidate)
        return artistMatches * policy.seedArtistAffinityBoost +
            genreMatches.coerceAtMost(3) * 34.0 +
            if (sameAlbum) policy.seedAlbumAffinityBoost else 0.0
    }

    private fun musicDiscoveryScore(item: MediaItem): Double =
        (if (item.playCount == 0) 22.0 else (12 - item.playCount * 2).coerceAtLeast(0).toDouble()) +
            (if (item.isFavorite) 18.0 else 0.0) +
            (item.communityRating ?: 0f).coerceIn(0f, 10f)

    private fun musicTransitionAdjustment(
        selected: List<RankedCandidate>,
        candidate: RankedCandidate
    ): Double {
        val previous = selected.lastOrNull()?.item ?: return 0.0
        val sharedGenres = normalized(previous.genres).intersect(normalized(candidate.item.genres)).size
        val sameArtist = musicArtistKeys(previous).intersect(musicArtistKeys(candidate.item)).isNotEmpty()
        val sameAlbum = musicAlbumKey(previous).isNotBlank() &&
            musicAlbumKey(previous) == musicAlbumKey(candidate.item)
        return sharedGenres.coerceAtMost(2) * 8.0 -
            (if (sameArtist) 18.0 else 0.0) -
            (if (sameAlbum) 24.0 else 0.0)
    }

    private fun respectsMusicConcentration(
        selected: List<RankedCandidate>,
        candidate: RankedCandidate,
        artistLimit: Int,
        policy: PremiumRecommendationPolicy
    ): Boolean {
        val artistKeys = musicArtistKeys(candidate.item)
        if (artistKeys.isNotEmpty()) {
            val artistCount = selected.count { selectedItem ->
                musicArtistKeys(selectedItem.item).intersect(artistKeys).isNotEmpty()
            }
            if (artistCount >= artistLimit) return false
            if (consecutiveMatches(selected, candidate.item, ::musicArtistKeys) >= policy.maxConsecutiveArtist) {
                return false
            }
        }
        val albumKey = musicAlbumKey(candidate.item)
        if (
            albumKey.isNotBlank() &&
            consecutiveMatches(selected, candidate.item) { setOf(musicAlbumKey(it)) } >= policy.maxConsecutiveAlbum
        ) return false
        return true
    }

    private fun isFamiliarToSeed(seed: MediaItem, item: MediaItem): Boolean =
        item.isFavorite ||
            item.playCount > 0 ||
            musicArtistKeys(seed).intersect(musicArtistKeys(item)).isNotEmpty() ||
            (
                musicAlbumKey(seed).isNotBlank() &&
                    musicAlbumKey(seed) == musicAlbumKey(item)
                )

    private fun isMusicItem(item: MediaItem): Boolean = when (normalize(item.type)) {
        "audio", "musicalbum", "musicartist" -> true
        else -> false
    }

    private fun consecutiveMatches(
        selected: List<RankedCandidate>,
        candidate: MediaItem,
        keys: (MediaItem) -> Set<String>
    ): Int {
        val candidateKeys = keys(candidate).filterTo(hashSetOf()) { it.isNotBlank() }
        if (candidateKeys.isEmpty()) return 0
        var count = 0
        for (entry in selected.asReversed()) {
            if (keys(entry.item).intersect(candidateKeys).isEmpty()) break
            count += 1
        }
        return count
    }

    private fun selectWithSurfacePolicy(
        candidates: List<RankedCandidate>,
        limit: Int,
        policy: PremiumRecommendationPolicy,
        adjustment: (List<RankedCandidate>, RankedCandidate) -> Double
    ): List<MediaItem> {
        val remaining = candidates.toMutableList()
        val selected = mutableListOf<RankedCandidate>()
        val familiarTarget = policy.familiarTarget(limit)
        val discoveryTarget = limit - familiarTarget
        var familiarCount = 0
        var discoveryCount = 0

        while (selected.size < limit && remaining.isNotEmpty()) {
            val expectedFamiliarByNext =
                ((selected.size + 1) * familiarTarget + limit - 1) / limit
            val preferFamiliar = when {
                familiarCount >= familiarTarget -> false
                discoveryCount >= discoveryTarget -> true
                else -> familiarCount < expectedFamiliarByNext
            }
            val preferred = remaining.filter { candidate -> candidate.familiar == preferFamiliar }
            val pool = preferred.ifEmpty { remaining }
            val next = pool.maxWithOrNull(
                compareBy<RankedCandidate> { it.baseScore + adjustment(selected, it) }
                    .thenBy(RankedCandidate::tieBreaker)
            ) ?: break
            selected += next
            remaining.remove(next)
            if (next.familiar) familiarCount += 1 else discoveryCount += 1
        }
        return selected.map(RankedCandidate::item)
    }

    private fun musicTerms(item: MediaItem): Set<String> = buildSet {
        addAll(musicArtistKeys(item))
        addAll(normalized(item.genres))
        musicAlbumKey(item).takeIf(String::isNotBlank)?.let(::add)
    }

    private fun musicArtistKeys(item: MediaItem): Set<String> = buildSet {
        item.artistId?.let { add(normalize(it)) }
        item.albumArtist?.let { add(normalize(it)) }
        item.artists.forEach { add(normalize(it)) }
        if (item.type.equals("MusicArtist", ignoreCase = true)) add(normalize(item.title))
    }.filterTo(hashSetOf(), String::isNotBlank)

    private fun musicAlbumKey(item: MediaItem): String = normalize(item.albumId ?: item.album.orEmpty())

    private fun normalized(values: Iterable<String>): Set<String> =
        values.mapTo(hashSetOf(), ::normalize).filterTo(hashSetOf(), String::isNotBlank)

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)

    private fun serverOrderBonus(index: Int): Double = (SERVER_ORDER_BONUS - index).coerceAtLeast(0).toDouble()

    private fun stableHash(value: String): Long {
        var hash = 2_166_136_261L
        value.forEach { character ->
            hash = (hash xor character.code.toLong()) * 16_777_619L
            hash = hash and 0xffff_ffffL
        }
        return hash
    }

    private data class RankedCandidate(
        val item: MediaItem,
        val baseScore: Double,
        val affinityScore: Double = 0.0,
        val familiar: Boolean = false,
        val tieBreaker: Long
    )

    private val candidateComparator = compareByDescending<RankedCandidate>(RankedCandidate::baseScore)
        .thenByDescending(RankedCandidate::tieBreaker)
        .thenBy { it.item.id }

    private const val MAX_MIX_TRACKS = 100
    private const val SERVER_ORDER_BONUS = 28
    private const val DIVERSITY_WINDOW = 6
    private const val SERIES_REPEAT_PENALTY = 80.0
    private const val GENRE_REPEAT_PENALTY = 2.5
    private const val STUDIO_REPEAT_PENALTY = 7.0
    private const val SEED_SCORE = 10_000.0
}
