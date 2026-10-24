package com.piggie.tv.data.discovery

/** A discovery library route always opens a browser backed by that same Jellyfin view. */
object DiscoveryLibraryRoutePolicy {
    fun browseRequest(page: DiscoveryPage): DiscoveryBrowseRequest? {
        val (name, itemType) = when (page) {
            DiscoveryPage.HOME -> return null
            DiscoveryPage.MOVIES -> "Movies" to "Movie"
            DiscoveryPage.SHOWS -> "Shows" to "Series"
            DiscoveryPage.ANIME -> "Anime" to "Series"
            DiscoveryPage.CARTOONS -> "Cartoons" to "Series"
        }
        return DiscoveryBrowseRequest(
            title = name,
            filter = DiscoveryFilter(DiscoveryFilterType.LIBRARY, name),
            itemTypes = listOf(itemType),
            libraryName = name
        )
    }
}
