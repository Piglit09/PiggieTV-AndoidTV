package com.piggie.tv.ui.library

import android.content.Context
import com.piggie.tv.data.discovery.DiscoveryBrowseSort
import com.piggie.tv.data.discovery.DiscoveryBrowseWatchFilter
import com.piggie.tv.data.models.NativeSession
import java.util.Locale

/** Presentation preferences only; saved values never establish library membership. */
class LibraryBrowseStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("ptv_premium_library_browse", Context.MODE_PRIVATE)

    fun read(session: NativeSession, libraryName: String): Pair<DiscoveryBrowseSort, DiscoveryBrowseWatchFilter> {
        val key = key(session, libraryName)
        val sort = prefs.getString("$key.sort", null)
            ?.let { runCatching { DiscoveryBrowseSort.valueOf(it) }.getOrNull() }
            ?: DiscoveryBrowseSort.RECENT
        val watched = prefs.getString("$key.watch", null)
            ?.let { runCatching { DiscoveryBrowseWatchFilter.valueOf(it) }.getOrNull() }
            ?: DiscoveryBrowseWatchFilter.ALL
        return sort to watched
    }

    fun save(
        session: NativeSession,
        libraryName: String,
        sort: DiscoveryBrowseSort,
        watchFilter: DiscoveryBrowseWatchFilter
    ) {
        val key = key(session, libraryName)
        prefs.edit().putString("$key.sort", sort.name)
            .putString("$key.watch", watchFilter.name).apply()
    }

    private fun key(session: NativeSession, libraryName: String): String =
        "${session.serverId}|${session.userId}|${libraryName.trim().lowercase(Locale.ROOT)}"
}
