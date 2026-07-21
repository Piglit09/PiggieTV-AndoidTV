package com.piggie.tv.navigation

object NativeRouteNavigator {
    fun backTarget(current: NativeRoute): NativeRoute? = when (current) {
        NativeRoute.HOME -> null
        NativeRoute.MOVIES,
        NativeRoute.SHOWS,
        NativeRoute.MUSIC,
        NativeRoute.READING,
        NativeRoute.SEARCH,
        NativeRoute.SETTINGS,
        NativeRoute.PROFILE -> NativeRoute.HOME
    }
}
