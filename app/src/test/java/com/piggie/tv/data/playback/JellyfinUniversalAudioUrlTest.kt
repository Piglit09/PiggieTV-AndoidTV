package com.piggie.tv.data.playback

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinUniversalAudioUrlTest {
    @Test
    fun `builds authenticated-origin universal URL with AAC fallback`() {
        val result = JellyfinUniversalAudioUrl.build(
            serverUrl = "https://media.example/jellyfin/",
            itemId = "track id",
            userId = "user-id",
            deviceId = "device-id",
            mediaSourceId = "source-id",
            maxStreamingBitrate = 20_000_000
        ).toHttpUrl()

        assertEquals("/jellyfin/Audio/track%20id/universal", result.encodedPath)
        assertEquals("user-id", result.queryParameter("UserId"))
        assertEquals("device-id", result.queryParameter("DeviceId"))
        assertEquals("20000000", result.queryParameter("MaxStreamingBitrate"))
        assertEquals("aac", result.queryParameter("TranscodingContainer"))
        assertEquals("http", result.queryParameter("TranscodingProtocol"))
        assertEquals("aac", result.queryParameter("AudioCodec"))
        assertEquals("320000", result.queryParameter("AudioBitrate"))
        assertEquals("2", result.queryParameter("MaxAudioChannels"))
        assertEquals("2", result.queryParameter("TranscodingAudioChannels"))
        assertEquals("48000", result.queryParameter("MaxAudioSampleRate"))
        assertEquals("source-id", result.queryParameter("MediaSourceId"))
        assertFalse(result.queryParameterNames.contains("PlaySessionId"))
        assertEquals("0", result.queryParameter("StartTimeTicks"))
        assertEquals("true", result.queryParameter("EnableRedirection"))
        assertEquals("false", result.queryParameter("EnableRemoteMedia"))
        assertTrue(result.queryParameter("Container").orEmpty().contains("flac|flac"))
        assertFalse(result.queryParameterNames.contains("api_key"))
    }

    @Test
    fun `clamps negative start and rejects invalid inputs`() {
        val result = JellyfinUniversalAudioUrl.build(
            serverUrl = "http://192.168.1.20:8096",
            itemId = "track",
            userId = "user",
            deviceId = "device",
            startTimeTicks = -10L
        ).toHttpUrl()

        assertEquals("0", result.queryParameter("StartTimeTicks"))
        assertThrows(IllegalArgumentException::class.java) {
            JellyfinUniversalAudioUrl.build(
                serverUrl = "not a url",
                itemId = "track",
                userId = "user",
                deviceId = "device"
            )
        }
    }
}
