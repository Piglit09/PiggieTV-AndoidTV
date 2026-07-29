package com.piggie.tv.diagnostics

import android.content.Context
import com.piggie.tv.util.CrashReporter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PtvExportFiles(val json: File, val text: File)

object PtvDiagnosticExporter {
    const val EXPORT_DIRECTORY = "diagnostics"
    const val JSON_FILE = "ptv-diagnostics.json"
    const val TEXT_FILE = "ptv-diagnostics.txt"

    fun exportJson(context: Context): String = toJson(snapshot(context)).toString(2)

    fun exportText(context: Context): String = toText(snapshot(context))

    fun save(context: Context): PtvExportFiles {
        val directory = File(context.filesDir, EXPORT_DIRECTORY).apply { mkdirs() }
        val snapshot = snapshot(context)
        val json = File(directory, JSON_FILE).apply { writeText(toJson(snapshot).toString(2)) }
        val text = File(directory, TEXT_FILE).apply { writeText(toText(snapshot)) }
        return PtvExportFiles(json, text)
    }

    private fun snapshot(context: Context) = PtvDiagnosticsManager.snapshot(CrashReporter.getReports(context))

    fun toJson(snapshot: PtvDiagnosticsSnapshot): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("generatedAtMs", System.currentTimeMillis())
        put("device", deviceJson(snapshot.device))
        put("routeTraces", JSONArray(snapshot.routes.map(::routeJson)))
        put("networkTraces", JSONArray(snapshot.network.map(::networkJson)))
        put("imageTraces", JSONArray(snapshot.images.map(::imageJson)))
        put("playbackTraces", JSONArray(snapshot.playback.map(::playbackJson)))
        put("audioTraces", JSONArray(snapshot.audio.map(::audioJson)))
        put("readerTraces", JSONArray(snapshot.reader.map(::readerJson)))
        put("focusTraces", JSONArray(snapshot.focus.map(::focusJson)))
        put("performanceSamples", JSONArray(snapshot.performance.map(::performanceJson)))
        put("events", JSONArray(snapshot.events.map(::eventJson)))
        put("testResults", JSONArray(snapshot.testResults.map(::testJson)))
        put("crashReports", PtvRedactor.text(snapshot.crashReports) ?: JSONObject.NULL)
    }

    private fun deviceJson(value: PtvDeviceSnapshot) = JSONObject().apply {
        put("capturedAtMs", value.capturedAtMs)
        put("appVersion", value.appVersion)
        put("versionCode", value.versionCode)
        put("manufacturer", value.manufacturer)
        put("model", value.model)
        put("androidVersion", value.androidVersion)
        put("apiLevel", value.apiLevel)
        put("widthPixels", value.widthPixels)
        put("heightPixels", value.heightPixels)
        put("availableWindowWidth", value.availableWindowWidth)
        put("availableWindowHeight", value.availableWindowHeight)
        put("insets", JSONObject().apply {
            put("left", value.insetLeft); put("top", value.insetTop)
            put("right", value.insetRight); put("bottom", value.insetBottom)
        })
        put("density", value.density)
        put("densityDpi", value.densityDpi)
        put("scaledDensity", value.scaledDensity)
        put("xdpi", value.xdpi)
        put("ydpi", value.ydpi)
        put("logicalWidthDp", value.logicalWidthDp)
        put("logicalHeightDp", value.logicalHeightDp)
        put("smallestScreenWidthDp", value.smallestScreenWidthDp)
        put("fontScale", value.fontScale)
        put("layoutProfile", value.layoutProfile)
        put("displayMode", value.displayMode)
        put("refreshRateHz", value.refreshRateHz)
        put("memoryClassMb", value.memoryClassMb)
        put("largeMemoryClassMb", value.largeMemoryClassMb)
        put("availableMemoryBytes", value.availableMemoryBytes)
        put("lowMemory", value.lowMemory)
        put("availableStorageBytes", value.availableStorageBytes)
        put("networkTransport", value.networkTransport)
        put("batteryPercent", value.batteryPercent ?: JSONObject.NULL)
        put("batteryTemperatureC", value.batteryTemperatureC ?: JSONObject.NULL)
        put("thermalStatus", value.thermalStatus ?: JSONObject.NULL)
        put("videoDecoders", JSONArray(value.videoDecoders))
        put("audioCapabilities", value.audioCapabilities)
        put("serverReachability", value.serverReachability)
    }

    private fun routeJson(value: PtvRouteTrace) = JSONObject().apply {
        put("route", value.route); put("requestedAtMs", value.requestedAtMs)
        putNullable("visibleAtMs", value.visibleAtMs); putNullable("firstContentAtMs", value.firstContentAtMs)
        putNullable("firstImageAtMs", value.firstImageAtMs); putNullable("interactiveAtMs", value.interactiveAtMs)
        putNullable("focusOwner", value.focusOwner); put("requestCount", value.requestCount)
        put("failureCount", value.failureCount); put("retryCount", value.retryCount)
    }

    private fun networkJson(value: PtvNetworkTrace) = JSONObject().apply {
        put("method", value.method); put("endpoint", PtvRedactor.endpoint(value.endpoint)); put("startedAtMs", value.startedAtMs)
        putNullable("dnsMs", value.dnsMs); putNullable("connectMs", value.connectMs); putNullable("tlsMs", value.tlsMs)
        putNullable("requestWriteMs", value.requestWriteMs); putNullable("responseHeadersMs", value.responseHeadersMs)
        putNullable("firstByteMs", value.firstByteMs); putNullable("totalMs", value.totalMs); putNullable("status", value.status)
        putNullable("requestBytes", value.requestBytes); putNullable("responseBytes", value.responseBytes)
        putNullable("exception", PtvRedactor.text(value.exception)); put("retryCount", value.retryCount)
    }

    private fun imageJson(value: PtvImageTrace) = JSONObject().apply {
        put("timestampMs", value.timestampMs); put("itemId", PtvRedactor.identifier(value.itemId)); put("imageType", value.imageType)
        put("requestedWidth", value.requestedWidth); put("requestedHeight", value.requestedHeight)
        putNullable("sourceWidth", value.sourceWidth); putNullable("sourceHeight", value.sourceHeight)
        putNullable("cacheSource", value.cacheSource); put("loadMs", value.loadMs); putNullable("failure", PtvRedactor.text(value.failure))
        putNullable("placeholderMs", value.placeholderMs); putNullable("bitmapBytes", value.bitmapBytes)
        put("concurrentRequests", value.concurrentRequests)
    }

    private fun playbackJson(value: PtvPlaybackTrace) = JSONObject().apply {
        put("timestampMs", value.timestampMs); put("itemId", PtvRedactor.identifier(value.itemId)); put("event", value.event)
        putNullable("playMethod", value.playMethod); putNullable("mediaSourceId", PtvRedactor.identifier(value.mediaSourceId))
        putNullable("codecs", value.codecs); putNullable("container", value.container); putNullable("resolution", value.resolution)
        putNullable("bitrate", value.bitrate); putNullable("connectionSpeed", value.connectionSpeed)
        putNullable("negotiatedBitrate", value.negotiatedBitrate)
        putNullable("startupMs", value.startupMs); putNullable("bufferCount", value.bufferCount)
        putNullable("droppedFrames", value.droppedFrames); putNullable("decoder", value.decoder)
        putNullable("audioTrack", value.audioTrack); putNullable("subtitleTrack", value.subtitleTrack)
        putNullable("detail", PtvRedactor.text(value.detail))
    }

    private fun audioJson(value: PtvAudioTrace) = JSONObject().apply {
        put("timestampMs", value.timestampMs); put("event", value.event); putNullable("serviceState", value.serviceState)
        putNullable("mediaSessionState", value.mediaSessionState); putNullable("queueSize", value.queueSize)
        putNullable("currentIndex", value.currentIndex); putNullable("audioFocus", value.audioFocus)
        putNullable("notificationState", value.notificationState); putNullable("miniPlayerState", value.miniPlayerState)
        putNullable("error", PtvRedactor.text(value.error))
    }

    private fun readerJson(value: PtvReaderTrace) = JSONObject().apply {
        put("timestampMs", value.timestampMs); put("itemId", PtvRedactor.identifier(value.itemId)); put("event", value.event)
        putNullable("format", value.format); putNullable("pageCount", value.pageCount); putNullable("page", value.page)
        putNullable("fitMode", value.fitMode); putNullable("direction", value.direction); putNullable("renderTarget", value.renderTarget)
        putNullable("renderMs", value.renderMs); putNullable("cacheHit", value.cacheHit); putNullable("bitmapBytes", value.bitmapBytes)
        putNullable("input", value.input); putNullable("error", PtvRedactor.text(value.error))
    }

    private fun focusJson(value: PtvFocusTrace) = JSONObject().apply {
        put("timestampMs", value.timestampMs); put("route", value.route); put("direction", value.direction)
        putNullable("previousFocus", value.previousFocus); putNullable("resultingFocus", value.resultingFocus)
        putNullable("resultingBounds", value.resultingBounds); putNullable("failure", value.failure)
    }

    private fun performanceJson(value: PtvPerformanceSample) = JSONObject().apply {
        put("timestampMs", value.timestampMs); put("route", value.route); put("frameCount", value.frameCount)
        putNullable("averageFps", value.averageFps); putNullable("frameTimeP50Ms", value.frameTimeP50Ms)
        putNullable("frameTimeP95Ms", value.frameTimeP95Ms); putNullable("frameTimeP99Ms", value.frameTimeP99Ms)
        put("slowFrames", value.slowFrames); put("frozenFrames", value.frozenFrames); putNullable("maxFrameMs", value.maxFrameMs)
        put("pssKb", value.pssKb); put("javaHeapBytes", value.javaHeapBytes); put("nativeHeapBytes", value.nativeHeapBytes)
        put("bitmapBytesEstimate", value.bitmapBytesEstimate); put("playerCount", value.playerCount)
        put("activityCount", value.activityCount); put("pageCacheEntries", value.pageCacheEntries)
        put("measurementMethod", value.measurementMethod)
    }

    private fun eventJson(value: PtvDiagnosticEvent) = JSONObject().apply {
        put("timestampMs", value.timestampMs); put("category", value.category); put("name", value.name)
        putNullable("route", value.route); put("attributes", JSONObject(PtvRedactor.attributes(value.attributes)))
    }

    private fun testJson(value: PtvTestResult) = JSONObject().apply {
        put("timestampMs", value.timestampMs); put("suite", value.suite); put("test", value.test)
        put("status", value.status.name); put("durationMs", value.durationMs); put("detail", PtvRedactor.text(value.detail))
    }

    fun toText(snapshot: PtvDiagnosticsSnapshot): String = buildString {
        val device = snapshot.device
        appendLine("PiggieTV Native Diagnostic Report")
        appendLine("================================")
        appendLine("App: ${device.appVersion} (${device.versionCode})")
        appendLine("Device: ${device.manufacturer} ${device.model}; Android ${device.androidVersion} API ${device.apiLevel}")
        appendLine("Display: ${device.widthPixels}x${device.heightPixels}; window ${device.availableWindowWidth}x${device.availableWindowHeight}")
        appendLine("Logical: ${device.logicalWidthDp}x${device.logicalHeightDp}dp; sw=${device.smallestScreenWidthDp}dp; ${device.layoutProfile}")
        appendLine("Density: ${device.density} (${device.densityDpi}dpi); scaled=${device.scaledDensity}; xdpi=${device.xdpi}; ydpi=${device.ydpi}; font=${device.fontScale}")
        appendLine("Mode: ${device.displayMode} @ ${device.refreshRateHz}Hz; insets=${device.insetLeft},${device.insetTop},${device.insetRight},${device.insetBottom}")
        appendLine("Memory: class=${device.memoryClassMb}MB; available=${device.availableMemoryBytes / 1024 / 1024}MB; storage=${device.availableStorageBytes / 1024 / 1024}MB")
        appendLine("Network: ${device.networkTransport}; server=${device.serverReachability}; thermal=${device.thermalStatus ?: "unknown"}")
        appendLine()
        appendLine("Trace counts: routes=${snapshot.routes.size}, network=${snapshot.network.size}, images=${snapshot.images.size}, playback=${snapshot.playback.size}, reader=${snapshot.reader.size}, focus=${snapshot.focus.size}")
        snapshot.routes.takeLast(20).forEach { appendLine("ROUTE ${it.route}: requested=${it.requestedAtMs} visible=${it.visibleAtMs} interactive=${it.interactiveAtMs} focus=${it.focusOwner}") }
        snapshot.network.takeLast(30).forEach { appendLine("NET ${it.method} ${PtvRedactor.endpoint(it.endpoint)} status=${it.status} total=${it.totalMs}ms bytes=${it.responseBytes} error=${PtvRedactor.text(it.exception)}") }
        snapshot.playback.takeLast(30).forEach { appendLine("PLAY ${it.event} item=${PtvRedactor.identifier(it.itemId)} detail=${PtvRedactor.text(it.detail)}") }
        snapshot.reader.takeLast(30).forEach { appendLine("READ ${it.event} item=${PtvRedactor.identifier(it.itemId)} page=${it.page} fit=${it.fitMode} target=${it.renderTarget} render=${it.renderMs}ms error=${PtvRedactor.text(it.error)}") }
        snapshot.focus.filter { it.failure != null }.takeLast(20).forEach { appendLine("FOCUS ${it.route} ${it.direction}: ${it.failure}") }
        snapshot.events
            .filter { it.category == "discovery" || it.category == "recommendation" }
            .takeLast(80)
            .forEach {
                appendLine(
                    "${it.category.uppercase()} ${it.name}: " +
                        it.attributes.entries.joinToString(" ") { (key, value) -> "$key=$value" }
                )
            }
        snapshot.performance.takeLast(10).forEach { appendLine("PERF ${it.route}: frames=${it.frameCount} p95=${it.frameTimeP95Ms}ms slow=${it.slowFrames} frozen=${it.frozenFrames} pss=${it.pssKb / 1024}MB") }
        snapshot.testResults.takeLast(30).forEach { appendLine("TEST ${it.suite}/${it.test}: ${it.status} (${it.durationMs}ms) ${PtvRedactor.text(it.detail)}") }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }
}
