package com.piggie.tv.ui.rendering

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class RecyclerRenderingTuning(
    val prefetchEnabled: Boolean,
    val initialPrefetchCount: Int,
    val itemViewCacheSize: Int
)

object RecyclerRenderingPolicy {
    fun tuning(features: RendererFeatures, visibleItems: Int): RecyclerRenderingTuning =
        when (features.prefetchMode) {
            RecyclerPrefetchMode.ENABLED -> RecyclerRenderingTuning(
                prefetchEnabled = true,
                initialPrefetchCount = visibleItems.coerceAtLeast(2),
                itemViewCacheSize = visibleItems.coerceAtLeast(3)
            )
            RecyclerPrefetchMode.REDUCED -> RecyclerRenderingTuning(
                prefetchEnabled = true,
                initialPrefetchCount = 1,
                itemViewCacheSize = 2
            )
            RecyclerPrefetchMode.DISABLED -> RecyclerRenderingTuning(
                prefetchEnabled = false,
                initialPrefetchCount = 0,
                itemViewCacheSize = 1
            )
        }
}

fun RecyclerView.applyRenderingTuning(
    manager: LinearLayoutManager,
    visibleItems: Int
) {
    val tuning = RecyclerRenderingPolicy.tuning(TvRenderingRuntime.features(), visibleItems)
    manager.isItemPrefetchEnabled = tuning.prefetchEnabled
    manager.initialPrefetchItemCount = tuning.initialPrefetchCount
    setItemViewCacheSize(tuning.itemViewCacheSize)
    itemAnimator = null
    setHasFixedSize(true)
    overScrollMode = RecyclerView.OVER_SCROLL_NEVER
}
