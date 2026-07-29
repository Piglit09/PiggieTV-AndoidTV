package com.piggie.tv.ui.music

import com.piggie.tv.data.models.MediaItem

enum class MusicArtworkSource {
    ARTIST_BACKDROP,
    ARTIST_PRIMARY,
    RELEVANT_ALBUM_PRIMARY,
    ALBUM_PRIMARY,
    EMBEDDED_TRACK_PRIMARY,
    ITEM_PRIMARY,
    BRAND
}

enum class MusicArtworkImageType {
    BACKDROP,
    PRIMARY,
    BRAND
}

data class MusicArtworkCandidate(
    val ownerItemId: String?,
    val imageTag: String?,
    val imageType: MusicArtworkImageType,
    val source: MusicArtworkSource
) {
    val isRemote: Boolean
        get() = imageType != MusicArtworkImageType.BRAND &&
            !ownerItemId.isNullOrBlank() &&
            !imageTag.isNullOrBlank()
}

/**
 * Produces a finite, ordered fallback chain. The final BRAND entry is deliberately local so
 * exhausted or missing Jellyfin artwork never starts an unbounded retry loop.
 */
object MusicDetailsArtworkPolicy {
    const val MAX_REMOTE_ATTEMPTS = 4

    fun candidates(
        item: MediaItem,
        songs: List<MediaItem>,
        relevantAlbums: List<MediaItem> = emptyList(),
        artist: MediaItem? = null
    ): List<MusicArtworkCandidate> {
        val candidates = when (item.type.lowercase()) {
            "musicartist" -> artistCandidates(item, relevantAlbums)
            "musicalbum" -> albumCandidates(item, songs, artist)
            else -> genericCandidates(item, songs, artist)
        }
        val remoteCandidates = candidates
            .asSequence()
            .filter(MusicArtworkCandidate::isRemote)
            .distinctBy { "${it.ownerItemId}:${it.imageType}:${it.imageTag}" }
            .take(MAX_REMOTE_ATTEMPTS)
            .toList()
        return remoteCandidates + brand()
    }

    private fun artistCandidates(
        artist: MediaItem,
        relevantAlbums: List<MediaItem>
    ): List<MusicArtworkCandidate> = buildList {
        add(
            MusicArtworkCandidate(
                ownerItemId = artist.id,
                imageTag = artist.backdropImageTags.firstOrNull() ?: artist.backdropTag,
                imageType = MusicArtworkImageType.BACKDROP,
                source = MusicArtworkSource.ARTIST_BACKDROP
            )
        )
        add(
            MusicArtworkCandidate(
                ownerItemId = artist.id,
                imageTag = artist.imageTag,
                imageType = MusicArtworkImageType.PRIMARY,
                source = MusicArtworkSource.ARTIST_PRIMARY
            )
        )
        relevantAlbums.asSequence()
            .filter { it.type.equals("MusicAlbum", ignoreCase = true) }
            .filter { !it.imageTag.isNullOrBlank() }
            .take(2)
            .forEach { album ->
                add(
                    MusicArtworkCandidate(
                        ownerItemId = album.id,
                        imageTag = album.imageTag,
                        imageType = MusicArtworkImageType.PRIMARY,
                        source = MusicArtworkSource.RELEVANT_ALBUM_PRIMARY
                    )
                )
            }
        add(brand())
    }

    private fun albumCandidates(
        album: MediaItem,
        songs: List<MediaItem>,
        artist: MediaItem?
    ): List<MusicArtworkCandidate> = buildList {
        add(
            MusicArtworkCandidate(
                ownerItemId = album.id,
                imageTag = album.imageTag,
                imageType = MusicArtworkImageType.PRIMARY,
                source = MusicArtworkSource.ALBUM_PRIMARY
            )
        )
        songs.firstOrNull { !it.imageTag.isNullOrBlank() }?.let { track ->
            add(
                MusicArtworkCandidate(
                    ownerItemId = track.id,
                    imageTag = track.imageTag,
                    imageType = MusicArtworkImageType.PRIMARY,
                    source = MusicArtworkSource.EMBEDDED_TRACK_PRIMARY
                )
            )
        }
        artistCandidate(album, songs, artist)?.let(::add)
        add(brand())
    }

    private fun genericCandidates(
        item: MediaItem,
        songs: List<MediaItem>,
        artist: MediaItem?
    ): List<MusicArtworkCandidate> = buildList {
        add(
            MusicArtworkCandidate(
                ownerItemId = item.id,
                imageTag = item.imageTag,
                imageType = MusicArtworkImageType.PRIMARY,
                source = MusicArtworkSource.ITEM_PRIMARY
            )
        )
        songs.firstOrNull { !it.imageTag.isNullOrBlank() }?.let { track ->
            add(
                MusicArtworkCandidate(
                    ownerItemId = track.id,
                    imageTag = track.imageTag,
                    imageType = MusicArtworkImageType.PRIMARY,
                    source = MusicArtworkSource.EMBEDDED_TRACK_PRIMARY
                )
            )
        }
        artistCandidate(item, songs, artist)?.let(::add)
        add(brand())
    }

    private fun artistCandidate(
        item: MediaItem,
        songs: List<MediaItem>,
        artist: MediaItem?
    ): MusicArtworkCandidate? {
        if (artist != null && !artist.imageTag.isNullOrBlank()) {
            return MusicArtworkCandidate(
                ownerItemId = artist.id,
                imageTag = artist.imageTag,
                imageType = MusicArtworkImageType.PRIMARY,
                source = MusicArtworkSource.ARTIST_PRIMARY
            )
        }
        val parentPair = if (
            !item.parentPrimaryImageItemId.isNullOrBlank() &&
            !item.parentPrimaryImageTag.isNullOrBlank()
        ) {
            item.parentPrimaryImageItemId to item.parentPrimaryImageTag
        } else {
            songs.firstNotNullOfOrNull { song ->
                if (
                    !song.parentPrimaryImageItemId.isNullOrBlank() &&
                    !song.parentPrimaryImageTag.isNullOrBlank()
                ) {
                    song.parentPrimaryImageItemId to song.parentPrimaryImageTag
                } else {
                    null
                }
            }
        }
        val parentOwner = parentPair?.first
        val parentTag = parentPair?.second
        return if (!parentOwner.isNullOrBlank() && !parentTag.isNullOrBlank()) {
            MusicArtworkCandidate(
                ownerItemId = parentOwner,
                imageTag = parentTag,
                imageType = MusicArtworkImageType.PRIMARY,
                source = MusicArtworkSource.ARTIST_PRIMARY
            )
        } else {
            null
        }
    }

    private fun brand() = MusicArtworkCandidate(
        ownerItemId = null,
        imageTag = null,
        imageType = MusicArtworkImageType.BRAND,
        source = MusicArtworkSource.BRAND
    )

}

object MusicDetailsRelatedPolicy {
    const val INITIAL_LIMIT = 16

    fun title(itemType: String): String =
        if (itemType.equals("MusicArtist", ignoreCase = true)) {
            "Similar Artists"
        } else {
            "More From This Artist"
        }

    fun filter(
        currentItemId: String,
        currentItemType: String,
        candidates: List<MediaItem>
    ): List<MediaItem> {
        val allowedTypes = if (currentItemType.equals("MusicArtist", ignoreCase = true)) {
            setOf("musicartist")
        } else {
            setOf("musicalbum", "musicartist", "playlist")
        }
        return candidates.asSequence()
            .filter { it.id.isNotBlank() && it.id != currentItemId }
            .filter { it.type.lowercase() in allowedTypes }
            .distinctBy(MediaItem::id)
            .take(INITIAL_LIMIT)
            .toList()
    }
}

object MusicDetailsTrackPolicy {
    /** Keeps both initial adapter state and the Play All/Shuffle queue bounded. */
    const val INITIAL_LIMIT = 200

    fun initial(items: List<MediaItem>): List<MediaItem> =
        items.take(INITIAL_LIMIT)
}
