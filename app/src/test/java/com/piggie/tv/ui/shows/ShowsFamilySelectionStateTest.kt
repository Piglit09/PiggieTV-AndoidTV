package com.piggie.tv.ui.shows

import com.piggie.tv.data.models.NativeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ShowsFamilySelectionStateTest {
    @Test fun eachTabRetainsItsOwnFocusAndScrollWhenSwitchingAwayAndBack() {
        val state = ShowsFamilySelectionState(ShowsFamilyTab.SHOWS)
        state.rememberViewport(3, -14)
        state.rememberFocus("show-7")
        state.switchTo(ShowsFamilyTab.ANIME)
        state.rememberViewport(5, -8)
        state.rememberFocus("anime-9")
        state.switchTo(ShowsFamilyTab.CARTOONS)
        state.switchTo(ShowsFamilyTab.SHOWS)

        assertEquals(ShowsFamilyViewport(3, -14, "show-7"), state.currentViewport())
        state.switchTo(ShowsFamilyTab.ANIME)
        assertEquals(ShowsFamilyViewport(5, -8, "anime-9"), state.currentViewport())
        state.switchTo(ShowsFamilyTab.CARTOONS)
        assertNull(state.currentViewport().focusedItemId)
    }

    @Test fun persistedSelectionIsScopedToBothServerAndUser() {
        val store = ShowsFamilySelectionStore(RuntimeEnvironment.getApplication())
        val first = session("server-1", "user-1")
        val otherUser = session("server-1", "user-2")
        val otherServer = session("server-2", "user-1")

        store.save(first, ShowsFamilyTab.ANIME)
        store.save(otherUser, ShowsFamilyTab.CARTOONS)

        assertEquals(ShowsFamilyTab.ANIME, store.read(first))
        assertEquals(ShowsFamilyTab.CARTOONS, store.read(otherUser))
        assertEquals(ShowsFamilyTab.SHOWS, store.read(otherServer))
        assertEquals(ShowsFamilyTab.SHOWS, ShowsFamilyTab.fromStored("unknown"))
    }

    private fun session(serverId: String, userId: String) = NativeSession(
        "token", serverId, userId, "User", "http://127.0.0.1"
    )
}
