package com.piggie.tv.diagnostics

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import com.piggie.tv.BuildConfig
import com.piggie.tv.data.session.NativeSettings
import java.util.WeakHashMap
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

data class PtvRuntimeStats(
    val networkInFlight: Int,
    val imageInFlight: Int,
    val imageCompleted: Int,
    val imageSucceeded: Int,
    val imageFailed: Int,
    val imageCanceled: Int,
    val activityCount: Int,
    val bitmapBytesEstimate: Int,
    val latestBitmapWidth: Int,
    val latestBitmapHeight: Int,
    val latestBitmapAllocationBytes: Int,
    val latestImageDataSource: String,
    val eventSamples: Int,
    val traceSamples: Int
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

private data class PtvSnapshotBuffers(
    val events: List<PtvDiagnosticEvent>,
    val routes: List<PtvRouteTrace>,
    val network: List<PtvNetworkTrace>,
    val images: List<PtvImageTrace>,
    val playback: List<PtvPlaybackTrace>,
    val audio: List<PtvAudioTrace>,
    val reader: List<PtvReaderTrace>,
    val focus: List<PtvFocusTrace>,
    val performance: List<PtvPerformanceSample>,
    val testResults: List<PtvTestResult>
)

/** A single-producer primitive frame batch. It allocates only when a batch is consumed. */
private class PtvFrameAccumulator(private val capacity: Int) {
    private val values = LongArray(capacity)
    private var size = 0

    @Synchronized
    fun add(durationNanos: Long): LongArray? {
        values[size++] = durationNanos
        if (size < capacity) return null
        return values.copyOf(size).also { size = 0 }
    }

    @Synchronized
    fun snapshot(): LongArray = values.copyOf(size)

    @Synchronized
    fun clear() {
        size = 0
    }
}

object PtvDiagnosticsManager {
    private const val EVENT_LIMIT = 250
    private const val TRACE_LIMIT = 120
    private const val FRAME_BATCH_SIZE = 60
    private const val MAX_FRAME_ROUTES = 16
    private const val MEMORY_SAMPLE_INTERVAL_MS = 5_000L
    private const val OVERLAY_INPUT_QUIET_MS = 900L

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
    private val frameAccumulators = ConcurrentHashMap<String, PtvFrameAccumulator>()
    private val frameAccumulatorCreationLock = Any()
    private val instrumentationLock = Any()
    private val activeActivities = WeakHashMap<Activity, Unit>()
    private val frameListeners = mutableMapOf<Activity, Window.OnFrameMetricsAvailableListener>()
    private val frameThread by lazy { HandlerThread("ptv-frame-metrics").apply { start() } }
    private val frameHandler by lazy { Handler(frameThread.looper) }

    private val networkInFlight = AtomicInteger()
    private val imageInFlight = AtomicInteger()
    private val imageCompleted = AtomicInteger()
    private val imageSucceeded = AtomicInteger()
    private val imageFailed = AtomicInteger()
    private val imageCanceled = AtomicInteger()
    private val playerCount = AtomicInteger()
    private val activityCount = AtomicInteger()
    private val bitmapBytesEstimate = AtomicInteger()
    private val pageCacheEntries = AtomicInteger()
    private val cachedPssKb = AtomicInteger()
    private val lastMemorySampleAtMs = AtomicLong()
    private val memorySampleInFlight = AtomicBoolean()
    private val lastUiInteractionAtMs = AtomicLong()

    @Volatile private var latestBitmapWidth = 0
    @Volatile private var latestBitmapHeight = 0
    @Volatile private var latestBitmapAllocationBytes = 0
    @Volatile private var latestImageDataSource = "none"

    @Volatile private var appContext: Context? = null
    @Volatile private var collectionMode = DiagnosticsCollectionMode.DISABLED
    @Volatile private var currentRoute = "startup"
    @Volatile private var currentFocus = "none"
    @Volatile private var selectedItemId = "none"
    @Volatile private var playerState = "idle"
    @Volatile private var readerState = "closed"
    @Volatile private var lastError = "none"
    @Volatile private var displayRefreshRateHz = 60f
    @Volatile private var latestFocusGeometry: PtvLatestFocusGeometry? = null
    @Volatile private var latestPerformanceSample: PtvPerformanceSample? = null

    @Synchronized
    fun initialize(context: Context) {
        // Application startup owns initialization. Settings may ask for diagnostics state later,
        // but that must not overwrite an explicit ADB shelf/full/overlay experiment mid-run.
        if (appContext != null) return
        appContext = context.applicationContext
        collectionMode = if (BuildConfig.ENABLE_DIAGNOSTICS && NativeSettings(context).diagnosticsEnabled) {
            DiagnosticsCollectionMode.SUMMARY
        } else {
            DiagnosticsCollectionMode.DISABLED
        }
        @Suppress("DEPRECATION")
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
        displayRefreshRateHz = display.refreshRate.takeIf { it > 0 } ?: 60f
        scheduleMemorySample(force = true)
    }

    fun isEnabled(): Boolean =
        BuildConfig.ENABLE_DIAGNOSTICS && collectionMode != DiagnosticsCollectionMode.DISABLED

    fun currentCollectionMode(): DiagnosticsCollectionMode = collectionMode

    fun shouldCollectShelfTrace(): Boolean =
        BuildConfig.ENABLE_DIAGNOSTICS &&
            DiagnosticsInstrumentationPolicy.shouldCollectShelfTrace(collectionMode)

    fun shouldCollectFocusTrace(): Boolean =
        BuildConfig.ENABLE_DIAGNOSTICS &&
            DiagnosticsInstrumentationPolicy.shouldCollectFocusTrace(collectionMode)

    fun shouldCollectFullTrace(): Boolean =
        BuildConfig.ENABLE_DIAGNOSTICS &&
            DiagnosticsInstrumentationPolicy.shouldCollectFullTrace(collectionMode)

    fun shouldCaptureFocusGeometry(): Boolean = shouldCollectFocusTrace()

    fun shouldTraceInput(): Boolean = shouldCollectFocusTrace()

    fun markUiInteraction() {
        if (isEnabled()) lastUiInteractionAtMs.set(SystemClock.elapsedRealtime())
    }

    internal fun isOverlayRefreshAllowed(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        nowMs - lastUiInteractionAtMs.get() >= OVERLAY_INPUT_QUIET_MS

    fun setEnabled(context: Context, enabled: Boolean) {
        setCollectionMode(
            context,
            if (enabled) DiagnosticsCollectionMode.SUMMARY else DiagnosticsCollectionMode.DISABLED
        )
    }

    fun setCollectionMode(context: Context, mode: DiagnosticsCollectionMode) {
        NativeSettings(context).diagnosticsEnabled = mode != DiagnosticsCollectionMode.DISABLED
        val resolved = if (BuildConfig.ENABLE_DIAGNOSTICS) mode else DiagnosticsCollectionMode.DISABLED
        val wasEnabled = isEnabled()
        if (wasEnabled && resolved == DiagnosticsCollectionMode.DISABLED) {
            add(
                events,
                PtvDiagnosticEvent(category = "diagnostics", name = "disabled", route = currentRoute),
                EVENT_LIMIT
            )
        }
        collectionMode = resolved
        reconcileFrameInstrumentation()
        if (resolved == DiagnosticsCollectionMode.DISABLED) {
            latestFocusGeometry = null
            frameAccumulators.values.forEach(PtvFrameAccumulator::clear)
            PerformanceMonitor.onDiagnosticsDisabled()
        } else {
            scheduleMemorySample(force = true)
            if (!wasEnabled) {
                add(
                    events,
                    PtvDiagnosticEvent(category = "diagnostics", name = "enabled", route = currentRoute),
                    EVENT_LIMIT
                )
            }
        }
    }

    fun event(category: String, name: String, attributes: Map<String, String> = emptyMap()) {
        if (
            !BuildConfig.ENABLE_DIAGNOSTICS ||
            !DiagnosticsInstrumentationPolicy.shouldRecordEvent(collectionMode, category, name)
        ) return
        add(events, PtvDiagnosticEvent(category = category, name = name, route = currentRoute, attributes = PtvRedactor.attributes(attributes)), EVENT_LIMIT)
    }

    fun routeRequested(route: String) {
        if (!isEnabled()) return
        currentRoute = route
        add(routes, PtvRouteTrace(route, SystemClock.elapsedRealtime()), TRACE_LIMIT)
        event("route", "requested", mapOf("route" to route))
    }

    fun routeVisible(route: String, focusOwner: String? = null) {
        if (!isEnabled()) return
        currentRoute = route
        updateLastRoute(route) { it.copy(visibleAtMs = SystemClock.elapsedRealtime(), focusOwner = focusOwner) }
        capturePerformanceAsync(route)
        event("route", "visible", mapOf("route" to route))
    }

    fun routeFirstContent(route: String, image: Boolean = false) {
        if (!isEnabled()) return
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            for (index in routes.lastIndex downTo 0) {
                val current = routes[index]
                if (current.route != route) continue
                if (image && current.firstImageAtMs == null) {
                    routes[index] = current.copy(firstImageAtMs = now)
                } else if (!image && current.firstContentAtMs == null) {
                    routes[index] = current.copy(firstContentAtMs = now)
                }
                break
            }
        }
    }

    fun routeInteractive(route: String, focusOwner: String?) {
        if (!isEnabled()) return
        updateLastRoute(route) { it.copy(interactiveAtMs = SystemClock.elapsedRealtime(), focusOwner = focusOwner) }
    }

    fun networkStarted(): Int {
        if (!isEnabled()) return 0
        return networkInFlight.incrementAndGet()
    }

    fun recordNetwork(trace: PtvNetworkTrace) {
        networkInFlight.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (!isEnabled()) return
        add(
            network,
            trace.copy(
                endpoint = PtvRedactor.endpoint(trace.endpoint),
                exception = PtvRedactor.text(trace.exception)
            ),
            TRACE_LIMIT
        )
        updateLastRoute(currentRoute) {
            it.copy(
                requestCount = it.requestCount + 1,
                failureCount = it.failureCount + if (trace.exception != null || (trace.status ?: 0) >= 400) 1 else 0,
                retryCount = it.retryCount + trace.retryCount
            )
        }
        if (trace.exception != null || (trace.status ?: 0) >= 400) {
            lastError = trace.exception ?: "http_error"
        }
    }

    fun imageStarted(): Int {
        if (!isEnabled()) return 0
        return imageInFlight.incrementAndGet()
    }

    fun recordImage(trace: PtvImageTrace) {
        imageInFlight.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (!isEnabled()) return
        trace.bitmapBytes?.let { bytes ->
            bitmapBytesEstimate.updateAndGet { current -> maxOf(current, bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
        }
        add(images, trace.copy(itemId = PtvRedactor.text(trace.itemId).orEmpty()), TRACE_LIMIT)
        if (trace.failure == null) routeFirstContent(currentRoute, image = true)
    }

    /**
     * Coil's global listener reports only primitive/result metadata here. Summary mode updates
     * atomics without allocating a retained trace; shelf/full modes retain a bounded sample.
     */
    internal fun recordCoilImageResult(
        category: String,
        requestedWidth: Int,
        requestedHeight: Int,
        bitmapWidth: Int?,
        bitmapHeight: Int?,
        bitmapAllocationBytes: Int?,
        dataSource: String?,
        loadMs: Long,
        failure: String?,
        canceled: Boolean,
        concurrentRequests: Int
    ) {
        imageInFlight.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (!isEnabled()) return

        imageCompleted.incrementAndGet()
        when {
            canceled -> imageCanceled.incrementAndGet()
            failure != null -> imageFailed.incrementAndGet()
            else -> imageSucceeded.incrementAndGet()
        }
        if (
            bitmapWidth != null &&
            bitmapHeight != null &&
            bitmapAllocationBytes != null
        ) {
            latestBitmapWidth = bitmapWidth
            latestBitmapHeight = bitmapHeight
            latestBitmapAllocationBytes = bitmapAllocationBytes
            bitmapBytesEstimate.updateAndGet { current -> maxOf(current, bitmapAllocationBytes) }
        }
        latestImageDataSource = dataSource ?: "none"
        if (failure == null && !canceled) routeFirstContent(currentRoute, image = true)

        if (!shouldCollectShelfTrace()) return
        add(
            images,
            PtvImageTrace(
                itemId = "coil",
                imageType = category,
                requestedWidth = requestedWidth,
                requestedHeight = requestedHeight,
                sourceWidth = bitmapWidth,
                sourceHeight = bitmapHeight,
                cacheSource = dataSource,
                loadMs = loadMs,
                failure = failure ?: if (canceled) "canceled" else null,
                bitmapBytes = bitmapAllocationBytes?.toLong(),
                concurrentRequests = concurrentRequests
            ),
            TRACE_LIMIT
        )
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
        if (!shouldCollectFocusTrace()) return
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
        if (!shouldCaptureFocusGeometry()) return
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

    fun runtimeStats(): PtvRuntimeStats = synchronized(lock) {
        PtvRuntimeStats(
            networkInFlight = networkInFlight.get(),
            imageInFlight = imageInFlight.get(),
            imageCompleted = imageCompleted.get(),
            imageSucceeded = imageSucceeded.get(),
            imageFailed = imageFailed.get(),
            imageCanceled = imageCanceled.get(),
            activityCount = activityCount.get(),
            bitmapBytesEstimate = bitmapBytesEstimate.get(),
            latestBitmapWidth = latestBitmapWidth,
            latestBitmapHeight = latestBitmapHeight,
            latestBitmapAllocationBytes = latestBitmapAllocationBytes,
            latestImageDataSource = latestImageDataSource,
            eventSamples = events.size,
            traceSamples = routes.size + network.size + images.size + playback.size + audio.size +
                reader.size + focus.size + performance.size + tests.size
        )
    }

    fun attachActivity(activity: Activity) {
        synchronized(instrumentationLock) {
            if (activeActivities.put(activity, Unit) == null) activityCount.incrementAndGet()
        }
        ensureActivityInstrumentation(activity)
    }

    fun ensureActivityInstrumentation(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        synchronized(instrumentationLock) {
            if (
                !DiagnosticsInstrumentationPolicy.shouldAttachFrameMetrics(
                    BuildConfig.ENABLE_DIAGNOSTICS,
                    collectionMode
                ) ||
                frameListeners.containsKey(activity)
            ) return
            val fallbackRoute = activity.javaClass.simpleName
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                if (duration > 0) recordFrame(currentRoute.ifBlank { fallbackRoute }, duration)
            }
            frameListeners[activity] = listener
            activity.window.addOnFrameMetricsAvailableListener(listener, frameHandler)
        }
    }

    fun detachActivity(activity: Activity) {
        synchronized(instrumentationLock) {
            if (activeActivities.remove(activity) != null) {
                activityCount.updateAndGet { (it - 1).coerceAtLeast(0) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                frameListeners.remove(activity)?.let { listener ->
                    runCatching { activity.window.removeOnFrameMetricsAvailableListener(listener) }
                }
            }
        }
    }

    private fun recordFrame(route: String, durationNanos: Long) {
        if (!isEnabled()) return
        val durations = frameAccumulator(route).add(durationNanos) ?: return
        val sample = performanceSample(route, durations)
        latestPerformanceSample = sample
        add(performance, sample, TRACE_LIMIT)
        scheduleMemorySample()
    }

    private fun reconcileFrameInstrumentation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val activitiesToAttach: List<Activity>
        synchronized(instrumentationLock) {
            if (!isEnabled()) {
                frameListeners.forEach { (activity, listener) ->
                    runCatching { activity.window.removeOnFrameMetricsAvailableListener(listener) }
                }
                frameListeners.clear()
                return
            }
            activitiesToAttach = activeActivities.keys.toList()
        }
        activitiesToAttach.forEach(::ensureActivityInstrumentation)
    }

    fun capturePerformance(route: String = currentRoute): PtvPerformanceSample {
        val durations = frameAccumulators[route]?.snapshot() ?: LongArray(0)
        val sample = performanceSample(route, durations)
        if (isEnabled()) {
            latestPerformanceSample = sample
            add(performance, sample, TRACE_LIMIT)
            scheduleMemorySample()
        }
        return sample
    }

    private fun capturePerformanceAsync(route: String) {
        if (!isEnabled()) return
        val durations = frameAccumulators[route]?.snapshot() ?: LongArray(0)
        frameHandler.post {
            if (!isEnabled()) return@post
            val sample = performanceSample(route, durations)
            latestPerformanceSample = sample
            add(performance, sample, TRACE_LIMIT)
            scheduleMemorySample()
        }
    }

    private fun frameAccumulator(route: String): PtvFrameAccumulator {
        frameAccumulators[route]?.let { return it }
        synchronized(frameAccumulatorCreationLock) {
            frameAccumulators[route]?.let { return it }
            if (frameAccumulators.size >= MAX_FRAME_ROUTES - 1) {
                return frameAccumulators.getOrPut(FRAME_OVERFLOW_ROUTE) {
                    PtvFrameAccumulator(FRAME_BATCH_SIZE)
                }
            }
            return PtvFrameAccumulator(FRAME_BATCH_SIZE).also { frameAccumulators[route] = it }
        }
    }

    private fun performanceSample(route: String, durations: LongArray): PtvPerformanceSample {
        val sorted = durations.copyOf()
        sorted.sort()
        var totalDuration = 0.0
        var slowFrames = 0
        var frozenFrames = 0
        var maxFrame = 0L
        durations.forEach { duration ->
            totalDuration += duration
            if (duration > 16_666_667L) slowFrames++
            if (duration > 700_000_000L) frozenFrames++
            if (duration > maxFrame) maxFrame = duration
        }
        val averageDuration = if (durations.isNotEmpty()) totalDuration / durations.size else null
        val averageFps = averageDuration?.let { min(displayRefreshRateHz.toDouble(), 1_000_000_000.0 / it) }
        return PtvPerformanceSample(
            route = route,
            frameCount = durations.size,
            averageFps = averageFps,
            frameTimeP50Ms = percentile(sorted, 0.50),
            frameTimeP95Ms = percentile(sorted, 0.95),
            frameTimeP99Ms = percentile(sorted, 0.99),
            slowFrames = slowFrames,
            frozenFrames = frozenFrames,
            maxFrameMs = maxFrame.takeIf { durations.isNotEmpty() }?.div(1_000_000.0),
            pssKb = cachedPssKb.get(),
            javaHeapBytes = currentJavaHeapBytes(),
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            bitmapBytesEstimate = bitmapBytesEstimate.get().toLong(),
            playerCount = playerCount.get(),
            activityCount = activityCount.get(),
            pageCacheEntries = pageCacheEntries.get()
        )
    }

    private fun scheduleMemorySample(force: Boolean = false) {
        if (!isEnabled()) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastMemorySampleAtMs.get() < MEMORY_SAMPLE_INTERVAL_MS) return
        if (!memorySampleInFlight.compareAndSet(false, true)) return
        frameHandler.post {
            try {
                val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                cachedPssKb.set(memory.totalPss)
                lastMemorySampleAtMs.set(SystemClock.elapsedRealtime())
            } finally {
                memorySampleInFlight.set(false)
            }
        }
    }

    fun snapshot(crashReports: String? = null): PtvDiagnosticsSnapshot {
        val context = requireNotNull(appContext) { "Diagnostics manager is not initialized" }
        capturePerformance()
        val buffers = synchronized(lock) {
            PtvSnapshotBuffers(
                events = events.toList(),
                routes = routes.toList(),
                network = network.toList(),
                images = images.toList(),
                playback = playback.toList(),
                audio = audio.toList(),
                reader = reader.toList(),
                focus = focus.toList(),
                performance = performance.toList(),
                testResults = tests.toList()
            )
        }
        val reachability = when {
            buffers.network.any { (it.status ?: 0) in 200..399 } -> "reachable"
            buffers.network.any { it.exception != null || (it.status ?: 0) >= 400 } -> "unreachable-or-error"
            else -> "unknown"
        }
        val device = PtvDeviceSnapshot.capture(context).copy(serverReachability = reachability)
        return PtvDiagnosticsSnapshot(
            device = device,
            events = buffers.events,
            routes = buffers.routes,
            network = buffers.network,
            images = buffers.images,
            playback = buffers.playback,
            audio = buffers.audio,
            reader = buffers.reader,
            focus = buffers.focus,
            performance = buffers.performance,
            testResults = buffers.testResults,
            crashReports = PtvRedactor.text(crashReports)
        )
    }

    fun overlaySummary(): String {
        val sample = latestPerformanceSample
        val p95 = sample?.frameTimeP95Ms?.let { "%.1fms".format(it) } ?: "collecting"
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
            "Frame p95: $p95  Slow: ${sample?.slowFrames ?: 0}\n" +
            "PSS: ${(sample?.pssKb ?: cachedPssKb.get()) / 1024}MB  " +
            "Heap: ${(sample?.javaHeapBytes ?: currentJavaHeapBytes()) / 1024 / 1024}MB\n" +
            "API: ${networkInFlight.get()}  Images: ${imageInFlight.get()}  Players: ${playerCount.get()}\n" +
            "Bitmap: ${latestBitmapWidth}x$latestBitmapHeight  " +
            "${latestBitmapAllocationBytes / 1024}KB  $latestImageDataSource\n" +
            "Player: $playerState  Reader: $readerState\nLast error: ${PtvRedactor.text(lastError)}"
    }

    /** Releases high-volume diagnostic history before the process becomes a kill candidate. */
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        val critical =
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        val low =
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
        val hiddenOrBackground = level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN

        if (critical || low || hiddenOrBackground) {
            latestFocusGeometry = null
            frameAccumulators.clear()
        }
        synchronized(lock) {
            when {
                critical -> {
                    trimToLatest(events, 32)
                    trimToLatest(routes, 24)
                    trimToLatest(network, 24)
                    trimToLatest(images, 8)
                    trimToLatest(playback, 8)
                    trimToLatest(audio, 8)
                    trimToLatest(reader, 8)
                    focus.clear()
                    trimToLatest(performance, 12)
                    trimToLatest(tests, 16)
                }
                low -> {
                    trimToLatest(events, 96)
                    trimToLatest(routes, 48)
                    trimToLatest(network, 48)
                    trimToLatest(images, 24)
                    trimToLatest(playback, 24)
                    trimToLatest(audio, 24)
                    trimToLatest(reader, 24)
                    trimToLatest(focus, 16)
                    trimToLatest(performance, 32)
                    trimToLatest(tests, 32)
                }
                hiddenOrBackground -> {
                    trimToLatest(events, 160)
                    trimToLatest(focus, 16)
                    trimToLatest(performance, 60)
                }
            }
        }
        if (critical) {
            latestPerformanceSample = null
            bitmapBytesEstimate.set(0)
            latestBitmapWidth = 0
            latestBitmapHeight = 0
            latestBitmapAllocationBytes = 0
            latestImageDataSource = "trimmed"
        }
    }

    private fun sanitizeOverlayLabel(value: String): String =
        PtvRedactor.text(value)
            ?.replace(OVERLAY_WHITESPACE, " ")
            ?.trim()
            ?.take(MAX_OVERLAY_LABEL_LENGTH)
            ?.takeIf(String::isNotEmpty)
            ?: "unknown"

    private fun currentJavaHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun updateLastRoute(route: String, update: (PtvRouteTrace) -> PtvRouteTrace) {
        synchronized(lock) {
            for (index in routes.lastIndex downTo 0) {
                if (routes[index].route == route) {
                    routes[index] = update(routes[index])
                    break
                }
            }
        }
    }

    private fun percentile(sortedNanos: LongArray, fraction: Double): Double? {
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

    private fun <T> trimToLatest(buffer: ArrayDeque<T>, limit: Int) {
        while (buffer.size > limit) buffer.removeFirst()
    }

    private val OVERLAY_WHITESPACE = Regex("\\s+")
    private const val MAX_OVERLAY_LABEL_LENGTH = 80
    private const val FRAME_OVERFLOW_ROUTE = "other"
}
