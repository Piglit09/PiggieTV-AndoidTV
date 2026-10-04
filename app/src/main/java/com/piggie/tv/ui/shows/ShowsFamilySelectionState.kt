package com.piggie.tv.ui.shows

import android.content.Context
import com.piggie.tv.data.discovery.DiscoveryPage
import com.piggie.tv.data.models.NativeSession
import java.security.MessageDigest

internal enum class ShowsFamilyTab(val page: DiscoveryPage, val label: String) {
    SHOWS(DiscoveryPage.SHOWS, "Shows"),
    ANIME(DiscoveryPage.ANIME, "Anime"),
    CARTOONS(DiscoveryPage.CARTOONS, "Cartoons");

    companion object {
        fun fromStored(value: String?): ShowsFamilyTab =
            entries.firstOrNull { it.name == value } ?: SHOWS
    }
}

internal data class ShowsFamilyViewport(
    val firstVisiblePosition: Int = 0,
    val topOffset: Int = 0,
    val focusedItemId: String? = null
)

/** Each library keeps its own viewport and item focus while the main route remains Shows. */
internal class ShowsFamilySelectionState(initial: ShowsFamilyTab) {
    var selected: ShowsFamilyTab = initial
        private set

    private val viewportByTab = mutableMapOf<ShowsFamilyTab, ShowsFamilyViewport>()

    fun switchTo(tab: ShowsFamilyTab) {
        selected = tab
    }

    fun currentViewport(): ShowsFamilyViewport = viewportByTab[selected] ?: ShowsFamilyViewport()

    fun rememberViewport(firstVisiblePosition: Int, topOffset: Int) {
        val current = currentViewport()
        viewportByTab[selected] = current.copy(
            firstVisiblePosition = firstVisiblePosition.coerceAtLeast(0),
            topOffset = topOffset
        )
    }

    fun rememberFocus(itemId: String) {
        if (itemId.isBlank()) return
        viewportByTab[selected] = currentViewport().copy(focusedItemId = itemId)
    }
}

/** A saved tab is a navigation preference, never proof of membership. The scoped query
 * always resolves a fresh user-visible Jellyfin root before it can display cards. */
internal class ShowsFamilySelectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("ptv_shows_family_tabs", Context.MODE_PRIVATE)

    fun read(session: NativeSession): ShowsFamilyTab =
        scopeKey(session)?.let { ShowsFamilyTab.fromStored(prefs.getString(it, null)) }
            ?: ShowsFamilyTab.SHOWS

    fun save(session: NativeSession, tab: ShowsFamilyTab) {
        scopeKey(session)?.let { prefs.edit().putString(it, tab.name).apply() }
    }

    private fun scopeKey(session: NativeSession): String? {
        if (session.serverId.isBlank() || session.userId.isBlank()) return null
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("${session.serverId}\u0000${session.userId}".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
