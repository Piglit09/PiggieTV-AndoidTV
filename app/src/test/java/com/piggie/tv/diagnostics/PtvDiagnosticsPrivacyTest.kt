package com.piggie.tv.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PtvDiagnosticsPrivacyTest {
    @Test fun endpointRemovesQueryCredentialsAndUserInfo() {
        val value = PtvRedactor.endpoint("https://user:pass@example.test/Items/1?api_key=secret&x=2#fragment")
        assertTrue(value == "/Items/1")
        assertFalse(value.contains("secret"))
        assertFalse(value.contains("user"))
        assertFalse(value.contains("example.test"))
    }

    @Test fun textAndAttributesRedactCommonSecretForms() {
        val text = PtvRedactor.text(
            "token=abc123 password: hunter2 Authorization=BearerValue " +
                "QuickConnectSecret=qcs ServerId=server-123 UserId=user-456 " +
                "url=https://media.example/Videos/abcdef0123456789/stream?api_key=stream-secret"
        )!!
        assertFalse(text.contains("abc123"))
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("BearerValue"))
        assertFalse(text.contains("qcs"))
        assertFalse(text.contains("server-123"))
        assertFalse(text.contains("user-456"))
        assertFalse(text.contains("media.example"))
        assertFalse(text.contains("stream-secret"))
        assertTrue(text.contains("/Videos/[ID]/stream"))
        assertTrue(PtvRedactor.attributes(mapOf("access_token" to "secret"))["access_token"] == "[REDACTED]")
        assertTrue(PtvRedactor.attributes(mapOf("quick_connect_secret" to "secret"))["quick_connect_secret"] == "[REDACTED]")
        assertTrue(PtvRedactor.attributes(mapOf("server_id" to "full-id"))["server_id"] == "[REDACTED]")
        assertTrue(PtvRedactor.attributes(mapOf("user_id" to "full-id"))["user_id"] == "[REDACTED]")
    }

    @Test fun exportSchemaContainsTracesButNoSecrets() {
        val snapshot = PtvDiagnosticsSnapshot(
            device = device(),
            events = listOf(PtvDiagnosticEvent(category = "test", name = "privacy", attributes = mapOf("token" to "event-secret"))),
            routes = listOf(PtvRouteTrace("home", 1, visibleAtMs = 2)),
            network = listOf(PtvNetworkTrace("GET", "https://example.test/path?api_key=url-secret", 1, status = 200)),
            images = emptyList(),
            playback = listOf(PtvPlaybackTrace(itemId = "0123456789abcdef", event = "test", detail = "password=play-secret")),
            audio = emptyList(),
            reader = emptyList(),
            focus = emptyList(),
            performance = emptyList(),
            testResults = emptyList(),
            crashReports = "authorization=crash-secret"
        )
        val json = PtvDiagnosticExporter.toJson(snapshot).toString()
        assertTrue(json.contains("schemaVersion"))
        assertTrue(json.contains("routeTraces"))
        assertTrue(json.contains("\"rendering\""))
        assertTrue(json.contains("\"renderingProfile\""))
        assertTrue(json.contains("\"staticGlassEnabled\""))
        assertTrue(json.contains("\"focusScale\""))
        listOf("event-secret", "url-secret", "play-secret", "crash-secret").forEach { assertFalse(json.contains(it)) }
        assertFalse(json.contains("0123456789abcdef"))
        assertTrue(json.contains("[ID:0123…cdef]"))
    }

    private fun device() = PtvDeviceSnapshot(
        capturedAtMs = 1,
        appVersion = "test",
        versionCode = 1,
        manufacturer = "Amazon",
        model = "AFTKM",
        androidVersion = "11",
        apiLevel = 30,
        widthPixels = 1920,
        heightPixels = 1080,
        availableWindowWidth = 1920,
        availableWindowHeight = 1080,
        insetLeft = 0,
        insetTop = 0,
        insetRight = 0,
        insetBottom = 0,
        density = 2f,
        densityDpi = 320,
        scaledDensity = 2f,
        xdpi = 60.96f,
        ydpi = 60.96f,
        logicalWidthDp = 960,
        logicalHeightDp = 540,
        smallestScreenWidthDp = 540,
        fontScale = 1f,
        layoutProfile = "TV_COMPACT",
        displayMode = "3840x2160",
        refreshRateHz = 59.94f,
        memoryClassMb = 256,
        largeMemoryClassMb = 512,
        availableMemoryBytes = 1,
        lowMemory = false,
        availableStorageBytes = 1,
        networkTransport = "wifi",
        batteryPercent = null,
        batteryTemperatureC = null,
        thermalStatus = 0,
        videoDecoders = listOf("decoder"),
        audioCapabilities = "stereo"
    )
}
