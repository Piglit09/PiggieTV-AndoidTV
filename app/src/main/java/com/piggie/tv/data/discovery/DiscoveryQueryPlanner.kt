package com.piggie.tv.data.discovery

import com.piggie.tv.data.api.JellyfinItemFields

object DiscoveryQueryPlanner {
    const val CARD_FIELDS = JellyfinItemFields.CARD

    const val RECOMMENDATION_FIELDS = JellyfinItemFields.RECOMMENDATION

    /**
     * /Users/{userId}/Items/Resume is already Jellyfin's resumable-items contract. Keep this
     * parameter list separate from the ordinary recursive /Items query so diagnostics describe
     * the request that is actually sent.
     */
    fun resumeParameters(
        itemTypes: List<String>,
        limit: Int,
        fields: String = CARD_FIELDS
    ): Map<String, String> = linkedMapOf(
        "IncludeItemTypes" to itemTypes.joinToString(","),
        "Fields" to fields,
        "Limit" to limit.toString(),
        "EnableUserData" to "true",
        "EnableTotalRecordCount" to "false"
    )

    fun itemParameters(
        itemTypes: List<String>,
        filter: DiscoveryFilter,
        limit: Int,
        libraryId: String? = null,
        fields: String = CARD_FIELDS
    ): Map<String, String> {
        val params = linkedMapOf(
            "IncludeItemTypes" to itemTypes.joinToString(","),
            "Recursive" to "true",
            "Fields" to fields,
            "Limit" to limit.toString(),
            "EnableTotalRecordCount" to "false"
        )
        libraryId?.takeIf(String::isNotBlank)?.let { params["ParentId"] = it }

        when (filter.type) {
            DiscoveryFilterType.CONTINUE_WATCHING -> {
                params["Filters"] = "IsResumable"
                params["SortBy"] = "DatePlayed"
                params["SortOrder"] = "Descending"
            }
            DiscoveryFilterType.NEXT_UP -> Unit
            DiscoveryFilterType.RECENTLY_ADDED -> {
                params["SortBy"] = "DateCreated"
                params["SortOrder"] = "Descending"
            }
            DiscoveryFilterType.LATEST_RELEASES -> {
                params["SortBy"] = "PremiereDate"
                params["SortOrder"] = "Descending"
            }
            DiscoveryFilterType.POPULAR -> {
                params["SortBy"] = "CommunityRating,ProductionYear"
                params["SortOrder"] = "Descending"
            }
            DiscoveryFilterType.RECOMMENDED -> {
                params["SortBy"] = "CommunityRating"
                params["SortOrder"] = "Descending"
            }
            DiscoveryFilterType.RANDOM_GENRE,
            DiscoveryFilterType.GENRE -> {
                params["Genres"] = filter.requiredValue()
                params["SortBy"] = "CommunityRating"
                params["SortOrder"] = "Descending"
            }
            DiscoveryFilterType.RANDOM_STUDIO,
            DiscoveryFilterType.STUDIO -> {
                params["Studios"] = filter.requiredValue()
                params["SortBy"] = "CommunityRating"
                params["SortOrder"] = "Descending"
            }
            DiscoveryFilterType.LIBRARY -> {
                params["SortBy"] = "DateCreated"
                params["SortOrder"] = "Descending"
            }
            DiscoveryFilterType.ACTOR,
            DiscoveryFilterType.DIRECTOR -> {
                params["PersonIds"] = filter.requiredValue()
                params["SortBy"] = "CommunityRating"
                params["SortOrder"] = "Descending"
            }
            DiscoveryFilterType.COLLECTION -> {
                params["ParentId"] = filter.requiredValue()
                params["SortBy"] = "SortName"
                params["SortOrder"] = "Ascending"
            }
            DiscoveryFilterType.TAG -> {
                params["Tags"] = filter.requiredValue()
                params["SortBy"] = "SortName"
                params["SortOrder"] = "Ascending"
            }
            DiscoveryFilterType.YEAR -> {
                params["Years"] = filter.requiredValue()
                params["SortBy"] = "PremiereDate"
                params["SortOrder"] = "Descending"
            }
            DiscoveryFilterType.DECADE -> {
                val start = filter.requiredValue().toIntOrNull()
                    ?: throw IllegalArgumentException("Decade must be a four-digit year")
                require(start in 1000..9990) { "Decade must be a four-digit year" }
                params["MinPremiereDate"] = "$start-01-01"
                params["MaxPremiereDate"] = "${start + 9}-12-31"
                params["SortBy"] = "PremiereDate"
                params["SortOrder"] = "Descending"
            }
        }
        return params
    }

    fun safeQueryParameters(params: Map<String, String>): Map<String, String> =
        params.filterKeys { it != "Fields" }

    private fun DiscoveryFilter.requiredValue(): String =
        value?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("${type.name} requires a value")
}

data class RecommendationProfile(
    val watchedIds: Set<String>,
    val favoriteIds: Set<String>,
    val preferredGenres: Map<String, Int>,
    val preferredStudios: Map<String, Int>,
    val preferredActors: Map<String, Int>,
    val preferredDirectors: Map<String, Int>,
    val recentWatchCount: Int,
    val recentAdditionCount: Int
)

data class RecommendationScore(
    val item: com.piggie.tv.data.models.MediaItem,
    val score: Double,
    val reasons: List<String>
)

object DiscoveryRecommendationScorer {
    fun buildProfile(
        watched: List<com.piggie.tv.data.models.MediaItem>,
        favorites: List<com.piggie.tv.data.models.MediaItem>,
        recentAdditions: List<com.piggie.tv.data.models.MediaItem>
    ): RecommendationProfile {
        val genres = mutableMapOf<String, Int>()
        val studios = mutableMapOf<String, Int>()
        val actors = mutableMapOf<String, Int>()
        val directors = mutableMapOf<String, Int>()

        fun add(item: com.piggie.tv.data.models.MediaItem, weight: Int) {
            item.genres.forEach { genres[it] = genres.getOrDefault(it, 0) + weight }
            item.studios.forEach { studios[it] = studios.getOrDefault(it, 0) + weight }
            item.people.filter { it.type.equals("Actor", true) }
                .forEach { actors[it.name] = actors.getOrDefault(it.name, 0) + weight }
            item.people.filter { it.type.equals("Director", true) }
                .forEach { directors[it.name] = directors.getOrDefault(it.name, 0) + weight }
            item.director?.let { directors[it] = directors.getOrDefault(it, 0) + weight }
        }

        watched.forEach { add(it, 2) }
        favorites.forEach { add(it, 4) }
        return RecommendationProfile(
            watchedIds = watched.mapTo(mutableSetOf()) { it.id },
            favoriteIds = favorites.mapTo(mutableSetOf()) { it.id },
            preferredGenres = genres,
            preferredStudios = studios,
            preferredActors = actors,
            preferredDirectors = directors,
            recentWatchCount = watched.size,
            recentAdditionCount = recentAdditions.size
        )
    }

    fun score(
        candidates: List<com.piggie.tv.data.models.MediaItem>,
        profile: RecommendationProfile,
        currentYear: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    ): List<RecommendationScore> = candidates
        .asSequence()
        .filterNot { it.id in profile.watchedIds }
        .distinctBy { it.id }
        .map { item ->
            val reasons = mutableListOf<String>()
            var score = (item.communityRating ?: 0f).coerceIn(0f, 10f) * 3.0
            score += (item.criticRating ?: 0f).coerceIn(0f, 100f) * 0.1

            val age = item.productionYear?.let { (currentYear - it).coerceAtLeast(0) }
            val freshness = when (age) {
                null -> 0.0
                0 -> 10.0
                1 -> 8.0
                2 -> 6.0
                in 3..5 -> 3.0
                else -> 0.0
            }
            if (freshness > 0) reasons += "recent release"
            score += freshness

            val genreAffinity = item.genres.maxOfOrNull { profile.preferredGenres[it] ?: 0 } ?: 0
            if (genreAffinity > 0) reasons += "preferred genre"
            score += genreAffinity.coerceAtMost(15)

            val studioAffinity = item.studios.maxOfOrNull { profile.preferredStudios[it] ?: 0 } ?: 0
            if (studioAffinity > 0) reasons += "preferred studio"
            score += studioAffinity.coerceAtMost(10)

            val actorAffinity = item.people
                .filter { it.type.equals("Actor", true) }
                .maxOfOrNull { profile.preferredActors[it.name] ?: 0 } ?: 0
            if (actorAffinity > 0) reasons += "preferred actor"
            score += actorAffinity.coerceAtMost(10)

            val directorAffinity = item.people
                .filter { it.type.equals("Director", true) }
                .maxOfOrNull { profile.preferredDirectors[it.name] ?: 0 }
                ?: item.director?.let { profile.preferredDirectors[it] }
                ?: 0
            if (directorAffinity > 0) reasons += "preferred director"
            score += directorAffinity.coerceAtMost(10)

            if (item.id in profile.favoriteIds) {
                score += 15
                reasons += "favorite"
            }
            if (reasons.isEmpty()) reasons += "rating and catalog freshness"
            RecommendationScore(item, score, reasons.distinct())
        }
        .sortedByDescending(RecommendationScore::score)
        .toList()
}
