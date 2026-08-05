package com.piggie.tv.data.models

data class MediaItem(
    val id: String,
    val title: String,
    val type: String,
    val year: String?,
    val imageTag: String?,
    val backdropTag: String? = null,
    val logoTag: String? = null,
    val seriesName: String?,
    val episodeLabel: String?,
    val playbackPositionTicks: Long,
    val runtimeTicks: Long,
    val overview: String? = null,
    val communityRating: Float? = null,
    val officialRating: String? = null,
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val productionYear: Int? = null,
    val container: String? = null,
    val seriesId: String? = null,
    val seasonId: String? = null,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val albumArtist: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val isFavorite: Boolean = false,
    val isPlayed: Boolean = false,
    val pageCount: Int = 0,
    val criticRating: Float? = null,
    val director: String? = null,
    val people: List<Person> = emptyList(),
    val childCount: Int? = null,
    val episodeCount: Int? = null,
    val recursiveItemCount: Int? = null,
    val playCount: Int = 0,
    val lastPlayedDate: String? = null,
    val dateCreated: String? = null,
    val backdropImageTags: List<String> = emptyList(),
    val thumbImageTag: String? = null,
    val parentBackdropItemId: String? = null,
    val parentBackdropImageTags: List<String> = emptyList(),
    val parentLogoItemId: String? = null,
    val parentLogoImageTag: String? = null,
    val parentPrimaryImageItemId: String? = null,
    val parentPrimaryImageTag: String? = null,
    val parentThumbItemId: String? = null,
    val parentThumbImageTag: String? = null,
    val seriesPrimaryImageTag: String? = null,
    val audioTracks: List<AudioTrack> = emptyList(),
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    /** Jellyfin source whose stream indices populate [audioTracks] and [subtitleTracks]. */
    val mediaSourceId: String? = null,
    /** Exact intro/outro ranges derived from Jellyfin chapter markers, if supplied. */
    val playbackSkipSegments: List<PlaybackSkipSegment> = emptyList()
)

data class Person(
    val id: String,
    val name: String,
    val role: String?,
    val type: String?,
    val primaryImageTag: String? = null
)

data class AudioTrack(
    val index: Int,
    val language: String?,
    val title: String?,
    val codec: String?,
    val isDefault: Boolean,
    val channels: Int? = null
)

data class SubtitleTrack(
    val index: Int,
    val language: String?,
    val title: String?,
    val isDefault: Boolean,
    val type: String?,
    val isForced: Boolean = false,
    val codec: String? = null,
    val isExternal: Boolean = false,
    val supportsExternalStream: Boolean = false,
    val deliveryUrl: String? = null
)

data class MediaShelf(
    val title: String, 
    val items: List<MediaItem>, 
    val presentation: MediaCardPresentation
)

enum class MediaCardPresentation { POSTER, LANDSCAPE, SQUARE }

object MediaCardPresentationSelector {
    fun forItem(item: MediaItem, shelfTitle: String = ""): MediaCardPresentation = when {
        shelfTitle.equals("Continue watching", ignoreCase = true) -> MediaCardPresentation.LANDSCAPE
        item.type.equals("Episode", ignoreCase = true) -> MediaCardPresentation.LANDSCAPE
        item.type.equals("MusicAlbum", ignoreCase = true) || item.type.equals("Audio", ignoreCase = true) -> MediaCardPresentation.SQUARE
        else -> MediaCardPresentation.POSTER
    }
}
