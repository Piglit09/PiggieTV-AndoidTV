package com.piggie.tv.ui.home

import com.piggie.tv.data.discovery.DiscoveryPage
import com.piggie.tv.ui.discovery.BaseDiscoveryFragment
import com.piggie.tv.ui.hero.HeroRoute

class HomeFragment : BaseDiscoveryFragment() {
    override val discoveryPage = DiscoveryPage.HOME
    override val heroRoute = HeroRoute.HOME
}
