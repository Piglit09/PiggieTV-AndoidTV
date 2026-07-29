package com.piggie.tv.ui.movies

import com.piggie.tv.data.discovery.DiscoveryPage
import com.piggie.tv.ui.discovery.BaseDiscoveryFragment
import com.piggie.tv.ui.hero.HeroRoute

class MoviesFragment : BaseDiscoveryFragment() {
    override val discoveryPage = DiscoveryPage.MOVIES
    override val heroRoute = HeroRoute.MOVIES
}
