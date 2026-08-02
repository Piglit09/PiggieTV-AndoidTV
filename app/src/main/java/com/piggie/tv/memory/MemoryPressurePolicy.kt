package com.piggie.tv.memory

/**
 * Maps Android's trim levels to bounded, deterministic work. The numeric values are part of
 * Android's public ComponentCallbacks2 contract and keeping them here makes the policy directly
 * unit-testable without an Android runtime.
 */
data class MemoryPressureActions(
    val discoveryCachePercent: Int = 100,
    val clearImageMemoryCache: Boolean = false,
    val releaseInactiveRoutes: Boolean = false,
    val releaseDiscoverySession: Boolean = false,
    val releaseCurrentScreenContent: Boolean = false
) {
    val hasWork: Boolean
        get() = discoveryCachePercent < 100 || clearImageMemoryCache ||
            releaseInactiveRoutes || releaseDiscoverySession || releaseCurrentScreenContent
}

object MemoryPressurePolicy {
    const val RUNNING_MODERATE = 5
    const val RUNNING_LOW = 10
    const val RUNNING_CRITICAL = 15
    const val UI_HIDDEN = 20
    const val BACKGROUND = 40
    const val MODERATE = 60
    const val COMPLETE = 80

    fun actions(level: Int): MemoryPressureActions = when {
        level >= COMPLETE -> critical(background = true)
        level >= MODERATE -> critical(background = true)
        level >= BACKGROUND -> background(releaseSession = true)
        level >= UI_HIDDEN -> background(releaseSession = false)
        level >= RUNNING_CRITICAL -> critical(background = false)
        level >= RUNNING_LOW -> MemoryPressureActions(
            discoveryCachePercent = 25,
            clearImageMemoryCache = true,
            releaseInactiveRoutes = true
        )
        level >= RUNNING_MODERATE -> MemoryPressureActions(discoveryCachePercent = 50)
        else -> MemoryPressureActions()
    }

    private fun critical(background: Boolean) = MemoryPressureActions(
        discoveryCachePercent = 0,
        clearImageMemoryCache = true,
        releaseInactiveRoutes = true,
        releaseDiscoverySession = background,
        // A running-critical callback can arrive while the current screen remains RESUMED.
        // Destructive view release is only recoverable when a background lifecycle transition
        // will resume and rehydrate that screen later.
        releaseCurrentScreenContent = background
    )

    private fun background(releaseSession: Boolean) = MemoryPressureActions(
        // Keep a compact quarter of the byte-bounded shelf cache for a fast foreground return.
        discoveryCachePercent = 25,
        clearImageMemoryCache = true,
        releaseInactiveRoutes = true,
        releaseDiscoverySession = releaseSession
    )
}
