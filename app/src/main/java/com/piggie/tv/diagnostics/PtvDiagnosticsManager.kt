package com.piggie.tv.diagnostics

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import com.piggie.tv.BuildConfig
import com.piggie.tv.data.session.NativeSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

data class PtvDiagnosticsSnapshot(
    val device: PtvDeviceSnapshot,
    val events: List<PtvDiagnosticEvent>,
    val routes: List<PtvRouteTrace>,
    val network: List<PtvNetworkTrace>,
    val images: List<PtvImageTrace>,
    val playback: List<PtvPlaybackTrace>,
    val audio: List<PtvAudioTrace>,
    val reader: List<PtvReaderTrace>,
    val focus: List<PtvFocusTrace>,
    val performance: List<PtvPerformanceSample>,
    val testResults: List<PtvTestResult>,
    val crashReports: String?
)

data class PtvFocusGeometryBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun compact(): String = "$left,$top,$right,$bottom"
}

private data class PtvLatestFocusGeometry(
    val focusedViewId: String,
    val cardBounds: PtvFocusGeometryBounds,
    val artworkBounds: PtvFocusGeometryBounds,
    val indicatorBounds: PtvFocusGeometryBounds
)

object PtvDiagnosticsManager {
    private const val EVENT_LIMIT = 250
    private const val TRACE_LIMIT = 120
    private const val FRAME_BATCH_SIZE = 60
    private const val MEMORY_SAMPLE_INTERVAL_MS = 5_000L

    private val lock = Any()
    private val events = ArrayDeque<PtvDiagnosticEvent>()
    private val routes = ArrayDeque<PtvRouteTrace>()
    private val network = ArrayDeque<PtvNetworkTrace>()
    private val images = ArrayDeque<PtvImageTrace>()
    private val playback = ArrayDeque<PtvPlaybackTrace>()
    private val audio = ArrayDeque<PtvAudioTrace>()
    private val reader = ArrayDeque<PtvReaderTrace>()
    private val focus = ArrayDeque<PtvFocusTrace>()
    private val performance = ArrayDeque<PtvPerformanceSample>()
    private val tests = ArrayDeque<PtvTestResult>()
    private val frameDurationsByRoute = mutableMapOf<String, MutableList<Long>>()
    private val frameListeners = ConcurrentHashMap<Activity, Window.OnFrameMetricsAvailableListener>()
    private val frameThread by lazy { HandlerThread("ptv-frame-metrics").apply { start() } }
    private val frameHandler by lazy { Handler(frameThread.looper) }

    private val networkInFlight = AtomicInteger()
    private val imageInFlight = AtomicInteger()
    private val playerCount = AtomicInteger()
    private val activityCount = AtomicInteger()
    private val bitmapBytesEstimate = AtomicInteger()
    private val pageCacheEntries = AtomicInteger()
    private val cachedPssKb = AtomicInteger()
    private val lastMemorySampleAtMs = AtomicLong()
    private val memorySampleInFlight = AtomicBoolean()

    @Volatile private var appContext: Context? = null
    @Volatile private var diagnosticsEnabled = false
    @Volatile private var currentRoute = "startup"
    @Volatile private var currentFocus = "none"
    @Volatile private var selectedItemId = "none"
    @Volatile private var playerState = "idle"
    @Volatile private var readerState = "closed"
    @Volatile private var lastError = "none"
    @Volatile private var displayRefreshRateHz = 60f
    @Volatile private var latestFocusGeometry: PtvLatestFocusGeometry? = null

    fun initialize(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        diagnosticsEnabled = BuildConfig.ENABLE_DIAGNOSTICS && NativeSettings(context).diagnosticsEnabled
        @Suppress("DEPRECATION")
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
        displayRefreshRateHz = display.refreshRate.takeIf { it > 0 } ?: 60f
        scheduleMemorySample(force = true)
    }

    fun isEnabled(): Boolean = diagnosticsEnabled && BuildConfig.ENABLE_DIAGNOSTICS

    fun setEnabled(context: Context, enabled: Boolean) {
        NativeSettings(context).diagnosticsEnabled = enabled
        diagnosticsEnabled = enabled && BuildConfig.ENABLE_DIAGNOSTICS
        if (diagnosticsEnabled) scheduleMemorySample(force = true)
        event("diagnostics", if (diagnosticsEnabled) "enabled" else "disabled")
    }

    fun event(category: String, name: String, attributes: Map<String, String> = emptyMap()) {
        if (!isEnabled()) return
        add(events, PtvDiagnosticEvent(category = category, name = name, route = currentRoute, attributes = PtvRedactor.attributes(attributes)), EVENT_LIMIT)
    }

    fun routeRequested(route: String) {
        if (!isEnabled()) return
        currentRoute = route
        add(routes, PtvRouteTrace(route, android.os.SystemClock.elapsedRealtime()), TRACE_LIMIT)
        event("route", "requested", mapOf("route" to route))
    }

    fun routeVisible(route: String, focusOwner: String? = null) {
        if (!isEnabled()) return
        currentRoute = route
        updateLastRoute(route) { it.copy(visibleAtMs = android.os.SystemClock.elapsedRealtime(), focusOwner = focusOwner) }
        capturePerformance(route)
        event("route", "visible", mapOf("route" to route))
    }

    fun routeFirstContent(route: String, image: Boolean = false) {
        if (!isEnabled()) return
        val now = android.os.SystemClock.elapsedRealtime()
        updateLastRoute(route) {
            if (image) it.copy(firstImageAtMs = it.firstImageAtMs ?: now) else it.copy(firstContentAtMs = it.firstContentAtMs ?: now)
        }
    }

    fun routeInteractive(route: String, focusOwner: String?) {
        if (!isEnabled()) return
        updateLastRoute(route) { it.copy(interactiveAtMs = android.os.SystemClock.elapsedRealtime(), focusOwner = focusOwner) }
    }

    fun networkStarted(): Int {
        if (!isEnabled()) return 0
        return networkInFlight.incrementAndGet()
    }

    fun recordNetwork(trace: PtvNetworkTrace) {
        if (!isEnabled()) return
        networkInFlight.updateAndGet { (it - 1).coerceAtLeast(0) }
        add(network, trace.copy(endpoint = PtvRedactor.endpoint(trace.endpoint), exception = PtvRedactor.text(trace.exception)), TRACE_LIMIT)
        updateLastRoute(currentRoute) {
            it.copy(
                requestCount = it.requestCount + 1,
                failureCount = it.failureCount + if (trace.exception != null || (trace.status ?: 0) >= 400) 1 else 0,
                retryCount = it.retryCount + trace.retryCount
            )
        }
        if (trace.exception != null || (trace.status ?: 0) >= 400) lastError = trace.exception ?: "HTTP ${trace.status}"
    }

    fun imageStarted(): Int {
        if (!isEnabled()) return 0
        return imageInFlight.incrementAndGet()
    }

    fun recordImage(trace: PtvImageTrace) {
        if (!isEnabled()) return
        imageInFlight.updateAndGet { (it - 1).coerceAtLeast(0) }
        trace.bitmapBytes?.let { bytes ->
            bitmapBytesEstimate.updateAndGet { current -> maxOf(current, bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
        }
        add(images, trace.copy(itemId = PtvRedactor.text(trace.itemId).orEmpty()), TRACE_LIMIT)
        routeFirstContent(currentRoute, image = trace.failure == null)
    }

    fun recordPlayback(trace: PtvPlaybackTrace) {
        if (!isEnabled()) return
        selectedItemId = trace.itemId
        playerState = trace.event
        if (trace.event == "player_created") playerCount.incrementAndGet()
        if (trace.event == "player_released") playerCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (trace.event.contains("error", ignoreCase = true)) lastError = trace.detail ?: trace.event
        add(playback, trace.copy(itemId = PtvRedactor.text(trace.itemId).orEmpty(), detail = PtvRedactor.text(trace.detail)), TRACE_LIMIT)
    }

    fun recordAudio(trace: PtvAudioTrace) {
        if (!isEnabled()) return
        add(audio, trace.copy(error = PtvRedactor.text(trace.error)), TRACE_LIMIT)
    }

    fun recordReader(trace: PtvReaderTrace) {
        if (!isEnabled()) return
        selectedItemId = trace.itemId
        readerState = buildString {
            append(trace.event)
            trace.page?.let { append(" p").append(it + 1) }
        }
        if (trace.error != null) lastError = trace.error
        add(reader, trace.copy(itemId = PtvRedactor.text(trace.itemId).orEmpty(), error = PtvRedactor.text(trace.error)), TRACE_LIMIT)
    }

    fun recordFocus(trace: PtvFocusTrace) {
        if (!isEnabled()) return
        currentFocus = trace.resultingFocus ?: "none"
        add(focus, trace, TRACE_LIMIT)
    }

    /**
     * Keeps exactly one immutable focus-geometry sample for the debug overlay.
     * The view label is redacted and line-bounded before it enters retained state;
     * bounds are numeric values produced by the focus indicator.
     */
    fun updateFocusGeometry(
        focusedViewId: String,
        cardBounds: PtvFocusGeometryBounds,
        artworkBounds: PtvFocusGeometryBounds,
        indicatorBounds: PtvFocusGeometryBounds
    ) {
        if (!isEnabled()) return
        latestFocusGeometry = PtvLatestFocusGeometry(
            focusedViewId = sanitizeOverlayLabel(focusedViewId),
            cardBounds = cardBounds,
            artworkBounds = artworkBounds,
            indicatorBounds = indicatorBounds
        )
    }

    fun recordTest(result: PtvTestResult) {
        if (!isEnabled()) return
        add(tests, result.copy(detail = PtvRedactor.text(result.detail).orEmpty()), TRACE_LIMIT)
    }

    fun setPageCacheEntries(entries: Int) {
        pageCacheEntries.set(entries.coerceAtLeast(0))
    }

    fun attachActivity(activity: Activity) {
        activityCount.incrementAndGet()
        ensureActivityInstrumentation(activity)
    }

    fun ensureActivityInstrumentation(activity: Activity) {
        if (
            !DiagnosticsInstrumentationPolicy.shouldAttachFrameMetrics(
                BuildConfig.ENABLE_DIAGNOSTICS,
                diagnosticsEnabled
            ) ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
            frameListeners.containsKey(activity)
        ) return
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (duration > 0) recordFrame(currentRoute.ifBlank { activity.javaClass.simpleName }, duration)
        }
        frameListeners[activity] = listener
        activity.window.addOnFrameMetricsAvailableListener(listener, frameHandler)
    }

    fun detachActivity(activity: Activity) {
        activityCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            frameListeners.remove(activity)?.let { activity.window.removeOnFrameMetricsAvailableListener(it) }
        }
    }

    private fun recordFrame(route: String, durationNanos: Long) {
        if (!isEnabled()) return
        synchronized(lock) {
            val durations = frameDurationsByRoute.getOrPut(route) { mutableListOf() }
            durations += durationNanos
            if (durations.size >= FRAME_BATCH_SIZE) {
                performance.addLast(performanceSample(route, durations.toList()))
                while (performance.size > TRACE_LIMIT) performance.removeFirst()
                durations.clear()
            }
        }
    }

    fun capturePerformance(route: String = currentRoute): PtvPerformanceSample {
        val durations = synchronized(lock) { frameDurationsByRoute[route]?.toList().orEmpty() }
        val sample = performanceSample(route, durations)
        if (isEnabled()) add(performance, sample, TRACE_LIMIT)
        return sample
    }

    private fun performanceSample(route: String, durations: List<Long>): PtvPerformanceSample {
        val sorted = durations.sorted()
        scheduleMemorySample()
        val averageDuration = durations.takeIf { it.isNotEmpty() }?.average()
        val averageFps = averageDuration?.let { min(displayRefreshRateHz.toDouble(), 1_000_000_000.0 / it) }
        return PtvPerformanceSample(
            route = route,
            frameCount = durations.size,
            averageFps = averageFps,
            frameTimeP50Ms = percentile(sorted, 0.50),
            frameTimeP95Ms = percentile(sorted, 0.95),
            frameTimeP99Ms = percentile(sorted, 0.99),
            slowFrames = durations.count { it > 16_666_667L },
            frozenFrames = durations.count { it > 700_000_000L },
            maxFrameMs = durations.maxOrNull()?.div(1_000_000.0),
            pssKb = cachedPssKb.get(),
            javaHeapBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            bitmapBytesEstimate = bitmapBytesEstimate.get().toLong(),
            playerCount = playerCount.get(),
            activityCount = activityCount.get(),
            pageCacheEntries = pageCacheEntries.get()
        )
    }

    private fun scheduleMemorySample(force: Boolean = false) {
        if (!isEnabled()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastMemorySampleAtMs.get() < MEMORY_SAMPLE_INTERVAL_MS) return
        if (!memorySampleInFlight.compareAndSet(false, true)) return
        frameHandler.post {
            try {
                val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                cachedPssKb.set(memory.totalPss)
                lastMemorySampleAtMs.set(android.os.SystemClock.elapsedRealtime())
            } finally {
                memorySampleInFlight.set(false)
            }
        }
    }

    fun snapshot(crashReports: String? = null): PtvDiagnosticsSnapshot {
        val context = requireNotNull(appContext) { "Diagnostics manager is not initialized" }
        capturePerformance()
        synchronized(lock) {
            return PtvDiagnosticsSnapshot(
                device = PtvDeviceSnapshot.capture(context).copy(
                    serverReachability = when {
                        network.any { (it.status ?: 0) in 200..399 } -> "reachable"
                        network.any { it.exception != null || (it.status ?: 0) >= 400 } -> "unreachable-or-error"
                        else -> "unknown"
                    }
                ),
                events = events.toList(),
                routes = routes.toList(),
                network = network.toList(),
                images = images.toList(),
                playback = playback.toList(),
                audio = audio.toList(),
                reader = reader.toList(),
                focus = focus.toList(),
                performance = performance.toList(),
                testResults = tests.toList(),
                crashReports = PtvRedactor.text(crashReports)
            )
        }
    }

    fun overlaySummary(): String {
        val sample = synchronized(lock) { performance.lastOrNull() } ?: capturePerformance()
        val p95 = sample.frameTimeP95Ms?.let { "%.1fms".format(it) } ?: "collecting"
        val geometry = latestFocusGeometry
        val geometrySummary = if (geometry == null) {
            "Focus view: none\nCard: none  Art: none  Indicator: none\n"
        } else {
            "Focus view: ${geometry.focusedViewId}\n" +
                "Card: ${geometry.cardBounds.compact()}  " +
                "Art: ${geometry.artworkBounds.compact()}  " +
                "Indicator: ${geometry.indicatorBounds.compact()}\n"
        }
        return "Route: $currentRoute\nFocus: $currentFocus\nItem: ${PtvRedactor.identifier(selectedItemId) ?: "none"}\n" +
            geometrySummary +
            "Frame p95: $p95  Slow: ${sample.slowFrames}\nPSS: ${sample.pssKb / 1024}MB  Heap: ${sample.javaHeapBytes / 1024 / 1024}MB\n" +
            "API: ${networkInFlight.get()}  Images: ${imageInFlight.get()}  Players: ${playerCount.get()}\n" +
            "Player: $playerState  Reader: $readerState\nLast error: ${PtvRedactor.text(lastError)}"
    }

    private fun sanitizeOverlayLabel(value: String): String =
        PtvRedactor.text(value)
            ?.replace(OVERLAY_WHITESPACE, " ")
            ?.trim()
            ?.take(MAX_OVERLAY_LABEL_LENGTH)
            ?.takeIf(String::isNotEmpty)
            ?: "unknown"

    private fun updateLastRoute(route: String, update: (PtvRouteTrace) -> PtvRouteTrace) {
        synchronized(lock) {
            val list = routes.toMutableList()
            val index = list.indexOfLast { it.route == route }
            if (index >= 0) {
                list[index] = update(list[index])
                routes.clear()
                routes.addAll(list)
            }
        }
    }

    private fun percentile(sortedNanos: List<Long>, fraction: Double): Double? {
        if (sortedNanos.isEmpty()) return null
        val index = ((sortedNanos.size - 1) * fraction).toInt().coerceIn(sortedNanos.indices)
        return sortedNanos[index] / 1_000_000.0
    }

    private fun <T> add(buffer: ArrayDeque<T>, value: T, limit: Int) {
        synchronized(lock) {
            buffer.addLast(value)
            while (buffer.size > limit) buffer.removeFirst()
        }
    }

    private val OVERLAY_WHITESPACE = Regex("\\s+")
    private const val MAX_OVERLAY_LABEL_LENGTH = 80
}
