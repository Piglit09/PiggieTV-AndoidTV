package com.piggie.tv.ui.layout

import kotlin.math.abs

data class TvNavigationAlignment(
    val screenCenterPx: Float,
    val navigationCenterPx: Float,
    val offsetPx: Float,
    val offsetDp: Float,
    val errorPx: Float,
    val errorDp: Float
) {
    val withinTolerance: Boolean
        get() = errorDp <= TvNavigationAlignmentPolicy.MAX_ERROR_DP
}

object TvNavigationAlignmentPolicy {
    const val MAX_ERROR_DP = 4f

    fun measure(
        screenLeftPx: Int,
        screenWidthPx: Int,
        navigationLeftPx: Int,
        navigationWidthPx: Int,
        density: Float
    ): TvNavigationAlignment {
        val screenCenter = screenLeftPx + (screenWidthPx / 2f)
        val navigationCenter = navigationLeftPx + (navigationWidthPx / 2f)
        val offsetPx = navigationCenter - screenCenter
        val safeDensity = density.coerceAtLeast(0.01f)
        val errorPx = abs(offsetPx)
        return TvNavigationAlignment(
            screenCenterPx = screenCenter,
            navigationCenterPx = navigationCenter,
            offsetPx = offsetPx,
            offsetDp = offsetPx / safeDensity,
            errorPx = errorPx,
            errorDp = errorPx / safeDensity
        )
    }
}
