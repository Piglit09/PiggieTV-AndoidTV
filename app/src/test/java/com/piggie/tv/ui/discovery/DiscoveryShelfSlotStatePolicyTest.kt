package com.piggie.tv.ui.discovery

import com.piggie.tv.data.discovery.ShelfStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryShelfSlotStatePolicyTest {
    @Test
    fun exposesLoadingContentEmptyAndErrorStates() {
        assertEquals(
            DiscoveryShelfSlotState.LOADING,
            DiscoveryShelfSlotStatePolicy.resolve(null)
        )
        assertEquals(
            DiscoveryShelfSlotState.LOADING,
            DiscoveryShelfSlotStatePolicy.resolve(ShelfStatus.LOADING)
        )
        assertEquals(
            DiscoveryShelfSlotState.CONTENT,
            DiscoveryShelfSlotStatePolicy.resolve(ShelfStatus.READY)
        )
        assertEquals(
            DiscoveryShelfSlotState.EMPTY,
            DiscoveryShelfSlotStatePolicy.resolve(ShelfStatus.NO_ITEMS)
        )
        assertEquals(
            DiscoveryShelfSlotState.EMPTY,
            DiscoveryShelfSlotStatePolicy.resolve(ShelfStatus.EMPTY)
        )
        assertEquals(
            DiscoveryShelfSlotState.ERROR,
            DiscoveryShelfSlotStatePolicy.resolve(ShelfStatus.HTTP_ERROR)
        )
    }
}
