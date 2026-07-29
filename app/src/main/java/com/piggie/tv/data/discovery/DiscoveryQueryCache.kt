package com.piggie.tv.data.discovery

import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

data class DiscoveryCacheResult(
    val items: List<MediaItem>,
    val hit: Boolean
)

/**
 * Small process-local LRU cache for overlapping discovery queries.
 *
 * Loads for the same key are serialized, preventing duplicate Jellyfin calls while the
 * three-worker discovery pipeline is active.
 */
class DiscoveryQueryCache(
    private val maxEntries: Int = 16,
    private val ttlMs: Long = 10 * 60 * 1_000L
) {
    private data class Entry(val items: List<MediaItem>, val storedAtMs: Long)

    private val entries = LinkedHashMap<String, Entry>(maxEntries, 0.75f, true)
    private val keyLocks = ConcurrentHashMap<String, Any>()

    fun getOrLoad(key: String, loader: () -> List<MediaItem>): DiscoveryCacheResult {
        getFresh(key)?.let { return DiscoveryCacheResult(it, true) }
        val keyLock = keyLocks.getOrPut(key) { Any() }
        return try {
            synchronized(keyLock) {
                getFresh(key)?.let { return@synchronized DiscoveryCacheResult(it, true) }
                val loaded = loader().toList()
                synchronized(entries) {
                    entries[key] = Entry(loaded, System.currentTimeMillis())
                    trimLocked()
                    PtvDiagnosticsManager.setPageCacheEntries(entries.size)
                }
                DiscoveryCacheResult(loaded, false)
            }
        } finally {
            keyLocks.remove(key, keyLock)
        }
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
            PtvDiagnosticsManager.setPageCacheEntries(0)
        }
        keyLocks.clear()
    }

    fun size(): Int = synchronized(entries) { entries.size }

    private fun getFresh(key: String): List<MediaItem>? = synchronized(entries) {
        val entry = entries[key] ?: return@synchronized null
        if (System.currentTimeMillis() - entry.storedAtMs > ttlMs) {
            entries.remove(key)
            PtvDiagnosticsManager.setPageCacheEntries(entries.size)
            null
        } else {
            entry.items
        }
    }

    private fun trimLocked() {
        while (entries.size > maxEntries) {
            val eldest = entries.entries.firstOrNull()?.key ?: break
            entries.remove(eldest)
        }
    }
}
