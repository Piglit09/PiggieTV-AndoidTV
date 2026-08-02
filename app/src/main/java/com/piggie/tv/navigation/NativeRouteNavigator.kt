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

    /**
     * Chooses the least-recently-used route that is safe to evict from the two-route cache.
     * The outgoing route is deliberately retained so a single transaction never both caps and
     * removes the same Fragment while FragmentManager is reordering operations.
     */
    fun evictionCandidate(
        lruRoutes: Iterable<NativeRoute>,
        target: NativeRoute,
        outgoing: NativeRoute?
    ): NativeRoute? = lruRoutes.firstOrNull { route -> route != target && route != outgoing }
}
