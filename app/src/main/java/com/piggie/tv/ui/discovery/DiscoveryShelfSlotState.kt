package com.piggie.tv.ui.discovery

import com.piggie.tv.data.discovery.ShelfStatus

enum class DiscoveryShelfSlotState {
    LOADING,
    CONTENT,
    EMPTY,
    ERROR
}

object DiscoveryShelfSlotStatePolicy {
    fun resolve(status: ShelfStatus?): DiscoveryShelfSlotState = when (status) {
        null,
        ShelfStatus.LOADING -> DiscoveryShelfSlotState.LOADING
        ShelfStatus.READY -> DiscoveryShelfSlotState.CONTENT
        ShelfStatus.EMPTY,
        ShelfStatus.NO_ITEMS -> DiscoveryShelfSlotState.EMPTY
        else -> DiscoveryShelfSlotState.ERROR
    }
}
