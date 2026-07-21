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
    val pageCount: Int = 0
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
