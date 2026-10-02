package com.piggie.tv.data.api

/**
 * Jellyfin 10.11 ItemFields values used by PiggieTV.
 *
 * Many BaseItemDto properties (for example ImageTags, SeriesId, IndexNumber, and UserData) are
 * returned by the item endpoints themselves and are not members of Jellyfin's ItemFields enum.
 * Sending those property names through the Fields query parameter makes Jellyfin's model binder
 * reject or log the request. Keep every outbound Fields value sourced from this allow-listed set.
 */
object JellyfinItemFields {
    const val CARD = "PrimaryImageAspectRatio,Genres,Overview"
    const val CARD_WITH_PEOPLE = "$CARD,People"
    const val RECOMMENDATION = "$CARD_WITH_PEOPLE,Studios,DateCreated"
    const val ITEM_DETAILS = "$CARD_WITH_PEOPLE,MediaSources,Chapters"
    const val SERIES_DETAILS = "PrimaryImageAspectRatio,Overview,ChildCount,RecursiveItemCount"
    const val EPISODE_DETAILS = "PrimaryImageAspectRatio,Genres,People"
    const val QUEUE = "PrimaryImageAspectRatio"
    const val MUSIC = "PrimaryImageAspectRatio"
    const val MUSIC_RECOMMENDATION = "PrimaryImageAspectRatio,Genres,Studios,People,DateCreated"
    const val READING = "PrimaryImageAspectRatio,Genres,MediaSources"

    val supported: Set<String> = setOf(
        "AirTime",
        "CanDelete",
        "CanDownload",
        "ChannelInfo",
        "Chapters",
        "Trickplay",
        "ChildCount",
        "CumulativeRunTimeTicks",
        "CustomRating",
        "DateCreated",
        "DateLastMediaAdded",
        "DisplayPreferencesId",
        "Etag",
        "ExternalUrls",
        "Genres",
        "ItemCounts",
        "MediaSourceCount",
        "MediaSources",
        "OriginalTitle",
        "Overview",
        "ParentId",
        "Path",
        "People",
        "PlayAccess",
        "ProductionLocations",
        "ProviderIds",
        "PrimaryImageAspectRatio",
        "RecursiveItemCount",
        "Settings",
        "SeriesStudio",
        "SortName",
        "SpecialEpisodeNumbers",
        "Studios",
        "Taglines",
        "Tags",
        "RemoteTrailers",
        "MediaStreams",
        "SeasonUserData",
        "DateLastRefreshed",
        "DateLastSaved",
        "RefreshState",
        "ChannelImage",
        "EnableMediaSourceDisplay",
        "Width",
        "Height",
        "ExtraIds",
        "LocalTrailerCount",
        "IsHD",
        "SpecialFeatureCount"
    )

    fun unsupported(fields: String): Set<String> = fields
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .filterNot(supported::contains)
        .toSet()

    fun normalize(fields: String): String = fields
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .filter(supported::contains)
        .distinct()
        .joinToString(",")
}
