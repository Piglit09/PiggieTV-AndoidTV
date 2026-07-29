package com.piggie.tv.ui.hero

import kotlin.math.max
import kotlin.math.min

object HeroVisibilityPolicy {
    fun visiblePercent(
        viewportHeight: Int,
        heroTop: Int,
        heroBottom: Int,
        heroHeight: Int
    ): Int {
        val visibleHeight =
            max(0, min(viewportHeight, heroBottom) - max(0, heroTop))
        return ((visibleHeight * 100f) / heroHeight.coerceAtLeast(1))
            .toInt()
            .coerceIn(0, 100)
    }
}
