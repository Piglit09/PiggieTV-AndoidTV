package com.piggie.tv.navigation

object NativeRouteNavigator {
    fun restoreTarget(savedName: String?): NativeRoute =
        NativeRoute.entries.firstOrNull { it.name == savedName } ?: NativeRoute.HOME

    /**
     * A same-route selection can refresh in place only after a route Fragment is actually
     * attached. In particular, two absent references must not compare as a reusable route during
     * cold start.
     */
    fun canRefreshVisibleRoute(
        current: NativeRoute,
        target: NativeRoute,
        detailsOpen: Boolean,
        visibleFragmentPresent: Boolean,
        visibleTagMatches: Boolean,
        cachedFragmentMatchesVisible: Boolean
    ): Boolean =
        current == target &&
            !detailsOpen &&
            visibleFragmentPresent &&
            (visibleTagMatches || cachedFragmentMatchesVisible)

    fun backTarget(current: NativeRoute): NativeRoute? = when (current) {
        NativeRoute.HOME -> null
        NativeRoute.MOVIES,
        NativeRoute.SHOWS,
        NativeRoute.MUSIC,
        NativeRoute.SEARCH,
        NativeRoute.SETTINGS,
        NativeRoute.PROFILE -> NativeRoute.HOME
    }
}
