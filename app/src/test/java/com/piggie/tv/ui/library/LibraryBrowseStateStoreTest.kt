package com.piggie.tv.ui.library

import com.piggie.tv.data.discovery.DiscoveryBrowseSort
import com.piggie.tv.data.discovery.DiscoveryBrowseWatchFilter
import com.piggie.tv.data.models.NativeSession
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LibraryBrowseStateStoreTest {
    @Test fun sortAndFilterRemainSeparateAcrossLibrariesAndUsers() {
        val store = LibraryBrowseStateStore(RuntimeEnvironment.getApplication())
        val userOne = NativeSession("token", "server", "user-one", "User", "https://fixture.invalid")
        val userTwo = NativeSession("token", "server", "user-two", "User", "https://fixture.invalid")
        val otherServer = NativeSession("token", "another-server", "user-one", "User", "https://fixture.invalid")

        store.save(userOne, "Anime", DiscoveryBrowseSort.TITLE,
            DiscoveryBrowseWatchFilter.UNPLAYED)

        assertEquals(
            DiscoveryBrowseSort.TITLE to DiscoveryBrowseWatchFilter.UNPLAYED,
            store.read(userOne, "Anime")
        )
        assertEquals(
            DiscoveryBrowseSort.RECENT to DiscoveryBrowseWatchFilter.ALL,
            store.read(userOne, "Shows")
        )
        assertEquals(
            DiscoveryBrowseSort.RECENT to DiscoveryBrowseWatchFilter.ALL,
            store.read(userTwo, "Anime")
        )
        assertEquals(
            DiscoveryBrowseSort.RECENT to DiscoveryBrowseWatchFilter.ALL,
            store.read(otherServer, "Anime")
        )
    }
}
