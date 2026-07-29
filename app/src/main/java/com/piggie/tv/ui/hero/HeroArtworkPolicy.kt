package com.piggie.tv.ui.hero

import com.piggie.tv.data.models.MediaItem

enum class HeroArtworkKind(val wireName: String) {
    BACKDROP("backdrop"),
    PRIMARY("primary")
}

data class HeroArtworkChoice(
    val item: MediaItem,
    val kind: HeroArtworkKind,
    val tag: String?
) {
    val cacheKey: String
        get() = "ptv-hero:${item.id}:${kind.wireName}:${tag ?: "untagged"}"
}

/**
 * Produces the bounded, route-explicit artwork chain. Music uses cover-specific ordering while
 * film/television routes retain backdrop-first presentation.
 */
object HeroArtworkPolicy {
    const val MAX_ATTEMPTS = 6

    fun choices(route: HeroRoute, candidate: HeroCandidate): List<HeroArtworkChoice> {
        val artwork = candidate.artworkItem
        val ordered = when {
            route != HeroRoute.MUSIC -> buildList {
                addBackdrop(
                    artwork,
                    allowUntagged =
                        route == HeroRoute.SHOWS &&
                            candidate.item.type == "Episode" &&
                            artwork.type == "Series" &&
                            artwork.id != candidate.item.id
                )
                addPrimary(artwork)
                candidate.fallbackArtworkItems.forEach {
                    addBackdrop(it)
                    addPrimary(it)
                }
            }
            candidate.item.type == "MusicArtist" -> buildList {
                addBackdrop(artwork)
                addPrimary(artwork)
                candidate.fallbackArtworkItems.forEach { addPrimary(it) }
            }
            candidate.item.type == "MusicAlbum" -> buildList {
                addPrimary(artwork)
                candidate.fallbackArtworkItems.forEach { addPrimary(it) }
            }
            else -> buildList {
                addPrimary(artwork)
                candidate.fallbackArtworkItems.forEach { addPrimary(it) }
            }
        }
        return ordered
            .distinctBy(HeroArtworkChoice::cacheKey)
            .take(MAX_ATTEMPTS)
    }

    private fun MutableList<HeroArtworkChoice>.addBackdrop(
        item: MediaItem,
        allowUntagged: Boolean = false
    ) {
        val tag = item.backdropImageTags.firstOrNull() ?: item.backdropTag
        if (!tag.isNullOrBlank() || allowUntagged) {
            add(HeroArtworkChoice(item, HeroArtworkKind.BACKDROP, tag?.takeIf(String::isNotBlank)))
        }
    }

    private fun MutableList<HeroArtworkChoice>.addPrimary(item: MediaItem) {
        val tag = item.imageTag
        if (!tag.isNullOrBlank()) {
            add(HeroArtworkChoice(item, HeroArtworkKind.PRIMARY, tag))
        }
    }
}

/**
 * Gives each rendered artwork chain an identity that includes the playable candidate.
 *
 * Episodes from the same series can intentionally share every artwork request. Including the
 * episode id still forces an in-flight request to be rebound when rotation changes candidates,
 * so callbacks owned by the previous episode cannot strand the shared fallback chain.
 */
internal object HeroArtworkLoadGuard {
    fun chainKey(candidateItemId: String, requestCacheKeys: List<String>): String =
        buildString {
            append("ptv-hero-chain:")
            append(candidateItemId)
            requestCacheKeys.forEach {
                append('|')
                append(it)
            }
        }

    fun ownsCallback(
        expectedGeneration: Long,
        activeGeneration: Long,
        expectedChainKey: String,
        activeChainKey: String?,
        expectedCandidateItemId: String,
        currentCandidateItemId: String?
    ): Boolean =
        expectedGeneration == activeGeneration &&
            expectedChainKey == activeChainKey &&
            expectedCandidateItemId == currentCandidateItemId
}
