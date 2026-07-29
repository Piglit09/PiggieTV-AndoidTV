package com.piggie.tv.ui.hero

/** Allows the shell to treat activating the current route as an explicit hero refresh. */
interface HeroRefreshableRoute {
    fun refreshHero()
}
