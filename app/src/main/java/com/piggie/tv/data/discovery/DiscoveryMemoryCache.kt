package com.piggie.tv.data.discovery

import android.os.SystemClock
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException

/** Process-memory-only cache. It intentionally makes no process-restart persistence claim. */
class DiscoveryMemoryCache(
    private val maxEntries: Int = 32,
    private val maxBytes: Long = 2L * 1024L * 1024L,
    private val ttlMs: Long = 15L * 60L * 1000L,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime
) {
    data class Entry(
        val shelf: DiscoveryShelf,
        val storedAtMs: Long,
        val estimatedBytes: Long,
        val sourceShelfId: String
    )

    data class Hit(val entry: Entry, val ageMs: Long, val ttlRemainingMs: Long)
    data class Flight(val owner: Boolean, val future: CompletableFuture<DiscoveryShelf>)
    data class Stats(val entries: Int, val estimatedBytes: Long, val inFlight: Int)

    private val entries = object : LinkedHashMap<String, Entry>(maxEntries, .75f, true) {}
    private val flights = mutableMapOf<String, CompletableFuture<DiscoveryShelf>>()
    private var bytes = 0L

    @Synchronized
    fun get(key: String, nowMs: Long = clockMs()): Hit? {
        val entry = entries[key] ?: return null
        val age = (nowMs - entry.storedAtMs).coerceAtLeast(0L)
        if (age >= ttlMs) {
            remove(key)
            return null
        }
        return Hit(entry, age, (ttlMs - age).coerceAtLeast(0L))
    }

    @Synchronized
    fun put(key: String, shelf: DiscoveryShelf, estimatedBytes: Long, nowMs: Long = clockMs()) {
        remove(key)
        val safeBytes = estimatedBytes.coerceAtLeast(0L)
        entries[key] = Entry(shelf, nowMs, safeBytes, shelf.definition.id)
        bytes += safeBytes
        trim()
    }

    @Synchronized
    fun acquireFlight(key: String): Flight {
        val existing = flights[key]
        if (existing != null) return Flight(false, existing)
        return Flight(true, CompletableFuture<DiscoveryShelf>().also { flights[key] = it })
    }

    @Synchronized
    fun completeFlight(key: String, shelf: DiscoveryShelf) {
        flights.remove(key)?.complete(shelf)
    }

    @Synchronized
    fun failFlight(key: String, error: Throwable) {
        flights.remove(key)?.completeExceptionally(error)
    }

    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0L
        val cancellation = CancellationException("Discovery cache scope cleared")
        flights.values.forEach { it.completeExceptionally(cancellation) }
        flights.clear()
    }

    /**
     * Evicts completed shelf values to a fraction of the configured limits while leaving active
     * requests alone. A memory callback must not turn a healthy in-flight request into a user-
     * visible cancellation.
     */
    @Synchronized
    fun trimToPercent(percent: Int, nowMs: Long = clockMs()): Stats {
        val safePercent = percent.coerceIn(0, 100)
        removeExpired(nowMs)
        val targetEntries = (maxEntries.toLong() * safePercent / 100L).toInt()
        val targetBytes = maxBytes * safePercent / 100L
        while (entries.size > targetEntries || bytes > targetBytes) {
            val eldest = entries.entries.firstOrNull() ?: break
            remove(eldest.key)
        }
        return stats()
    }

    @Synchronized
    fun stats(): Stats = Stats(entries.size, bytes, flights.size)

    @Synchronized
    private fun remove(key: String) {
        entries.remove(key)?.let { bytes -= it.estimatedBytes }
    }

    private fun removeExpired(nowMs: Long) {
        val expiredKeys = entries
            .filterValues { entry -> (nowMs - entry.storedAtMs).coerceAtLeast(0L) >= ttlMs }
            .keys
            .toList()
        expiredKeys.forEach(::remove)
    }

    private fun trim() {
        while (entries.size > maxEntries || bytes > maxBytes) {
            val eldest = entries.entries.firstOrNull() ?: break
            remove(eldest.key)
        }
    }
}
