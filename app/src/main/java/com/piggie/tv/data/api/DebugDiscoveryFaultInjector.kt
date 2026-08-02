package com.piggie.tv.data.api

import com.piggie.tv.BuildConfig
import java.io.IOException
import java.net.SocketTimeoutException
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class DiscoveryEndpointCategory {
    RESUME, NEXT_UP, USER_ITEMS, SUGGESTIONS, GENRE_FACETS, STUDIO_FACETS, USER_VIEWS, OTHER
}

enum class DiscoveryFaultMode {
    NORMAL, DELAY_10_SECONDS, DELAY_BEYOND_TIMEOUT, HTTP_400, HTTP_502,
    DISCONNECT_BEFORE_HEADERS, DISCONNECT_DURING_BODY, MALFORMED_JSON,
    EMPTY_HTTP_200, SUCCESS_AFTER_MANUAL_RETRY;

    companion object {
        fun fromWireName(value: String?): DiscoveryFaultMode = entries.firstOrNull {
            it.name.equals(value?.replace('-', '_'), ignoreCase = true)
        } ?: NORMAL
    }
}

data class DiscoveryFaultSnapshot(
    val category: DiscoveryEndpointCategory?,
    val shelfId: String?,
    val mode: DiscoveryFaultMode,
    val attempts: Int,
    val applied: Int,
    val enabled: Boolean,
    val refreshBypass: Boolean
)

class DebugInjectedDisconnectBeforeHeadersException : IOException("debug injected disconnect before headers")
class DebugInjectedDisconnectDuringBodyException : IOException("debug injected disconnect during body")

/** Memory-only and separately build-gated; beta/release cannot configure or apply faults. */
object DebugDiscoveryFaultInjector {
    private data class Rule(
        val category: DiscoveryEndpointCategory,
        val mode: DiscoveryFaultMode,
        val shelfId: String?
    )
    private data class ShelfScope(val shelfId: String, val generationId: Long, val attemptId: Int)
    private val rule = AtomicReference<Rule?>(null)
    private val attempts = AtomicInteger()
    private val applied = AtomicInteger()
    private val refreshBypass = java.util.concurrent.atomic.AtomicBoolean()
    private val appliedScopes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val shelfScope = ThreadLocal<ShelfScope?>()
    private val appliedDelayMs = ThreadLocal<Long?>()
    @Volatile internal var sleeper: (Long) -> Unit = Thread::sleep
    @Volatile internal var elapsedRealtime: () -> Long = SystemClock::elapsedRealtime

    fun configure(
        category: DiscoveryEndpointCategory?,
        mode: DiscoveryFaultMode,
        shelfId: String? = null,
        bypassFreshCache: Boolean = false
    ) {
        attempts.set(0)
        applied.set(0)
        appliedScopes.clear()
        refreshBypass.set(BuildConfig.ENABLE_DISCOVERY_FAULT_INJECTION && bypassFreshCache)
        rule.set(
            if (BuildConfig.ENABLE_DISCOVERY_FAULT_INJECTION && category != null && mode != DiscoveryFaultMode.NORMAL) {
                Rule(category, mode, shelfId?.takeIf(String::isNotBlank))
            } else {
                null
            }
        )
    }

    fun snapshot(): DiscoveryFaultSnapshot = DiscoveryFaultSnapshot(
        rule.get()?.category,
        rule.get()?.shelfId,
        rule.get()?.mode ?: DiscoveryFaultMode.NORMAL,
        attempts.get(),
        applied.get(),
        BuildConfig.ENABLE_DISCOVERY_FAULT_INJECTION,
        refreshBypass.get()
    )

    fun shouldBypassFreshCache(): Boolean =
        BuildConfig.ENABLE_DISCOVERY_FAULT_INJECTION && refreshBypass.get()

    /** Returns a deterministic synthetic body, null to continue to the real transport. */
    fun beforeRequest(category: DiscoveryEndpointCategory): String? {
        appliedDelayMs.remove()
        if (!BuildConfig.ENABLE_DISCOVERY_FAULT_INJECTION) return null
        val active = rule.get()?.takeIf { it.category == category } ?: return null
        val scope = shelfScope.get()
        if (active.shelfId != null && scope?.shelfId != active.shelfId) return null
        val invocation = attempts.incrementAndGet()
        if (active.mode == DiscoveryFaultMode.SUCCESS_AFTER_MANUAL_RETRY &&
            ((scope != null && scope.attemptId > 0) || (scope == null && invocation > 1))) return null
        val scopeKey = scope?.let { "${it.shelfId}:${it.generationId}:${it.attemptId}" }
            ?: "unscoped:$invocation"
        if (!appliedScopes.add(scopeKey)) return null
        applied.incrementAndGet()
        return when (active.mode) {
            DiscoveryFaultMode.NORMAL -> null
            DiscoveryFaultMode.DELAY_10_SECONDS -> null.also { timedDelay(10_000L) }
            DiscoveryFaultMode.DELAY_BEYOND_TIMEOUT -> {
                timedDelay(46_000L)
                throw SocketTimeoutException("debug injected total timeout")
            }
            DiscoveryFaultMode.HTTP_400 -> throw HttpRequestFailure(400, "")
            DiscoveryFaultMode.HTTP_502 -> throw HttpRequestFailure(502, "")
            DiscoveryFaultMode.DISCONNECT_BEFORE_HEADERS -> throw DebugInjectedDisconnectBeforeHeadersException()
            DiscoveryFaultMode.DISCONNECT_DURING_BODY -> throw DebugInjectedDisconnectDuringBodyException()
            DiscoveryFaultMode.MALFORMED_JSON -> "{\"Items\":["
            DiscoveryFaultMode.EMPTY_HTTP_200 -> "{\"Items\":[]}"
            DiscoveryFaultMode.SUCCESS_AFTER_MANUAL_RETRY -> throw SocketTimeoutException("debug injected timeout; manual retry will succeed")
        }
    }

    fun consumeAppliedDelayMs(): Long? = appliedDelayMs.get().also { appliedDelayMs.remove() }

    private fun timedDelay(durationMs: Long) {
        val startedAt = elapsedRealtime()
        try {
            sleeper(durationMs)
        } finally {
            appliedDelayMs.set((elapsedRealtime() - startedAt).coerceAtLeast(0L))
        }
    }

    fun <T> withShelfScope(shelfId: String, generationId: Long, attemptId: Int, action: () -> T): T {
        val previous = shelfScope.get()
        shelfScope.set(ShelfScope(shelfId, generationId, attemptId))
        return try {
            action()
        } finally {
            if (previous == null) shelfScope.remove() else shelfScope.set(previous)
        }
    }

    fun category(endpoint: String): DiscoveryEndpointCategory = when {
        "/Items/Resume" in endpoint -> DiscoveryEndpointCategory.RESUME
        "/Shows/NextUp" in endpoint -> DiscoveryEndpointCategory.NEXT_UP
        "/Suggestions" in endpoint -> DiscoveryEndpointCategory.SUGGESTIONS
        "/Genres" in endpoint -> DiscoveryEndpointCategory.GENRE_FACETS
        "/Studios" in endpoint -> DiscoveryEndpointCategory.STUDIO_FACETS
        endpoint.substringBefore('?').endsWith("/Views") -> DiscoveryEndpointCategory.USER_VIEWS
        "/Items" in endpoint -> DiscoveryEndpointCategory.USER_ITEMS
        else -> DiscoveryEndpointCategory.OTHER
    }
}
