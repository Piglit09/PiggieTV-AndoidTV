package com.piggie.tv.data.api

import com.piggie.tv.data.models.NativeSession
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class JellyfinNativeApiPlaybackRequestTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `playback info request scopes stream indices to source and resume position`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val session = NativeSession(
            token = "test-token",
            serverId = "server",
            userId = "user",
            userName = "Codex",
            serverUrl = server.url("/").toString().trimEnd('/')
        )
        val profile = JSONObject()
            .put("MaxStreamingBitrate", 20_000_000)
            .put(
                "DirectPlayProfiles",
                JSONArray().put(
                    JSONObject()
                        .put("Container", "mkv")
                        .put("Type", "Video")
                )
            )

        JellyfinNativeApi(RuntimeEnvironment.getApplication()).requestPlaybackInfo(
            session = session,
            itemId = "movie",
            profile = profile,
            mediaSourceId = "source-a",
            startTimeTicks = 123_450_000L,
            audioStreamIndex = 2,
            subtitleStreamIndex = 3
        )

        val request = server.takeRequest()
        val url = requireNotNull(request.requestUrl)
        assertEquals("user", url.queryParameter("UserId"))
        assertEquals("source-a", url.queryParameter("MediaSourceId"))
        assertEquals("123450000", url.queryParameter("StartTimeTicks"))
        assertEquals("2", url.queryParameter("AudioStreamIndex"))
        assertEquals("3", url.queryParameter("SubtitleStreamIndex"))
        assertEquals("POST", request.method)

        val body = JSONObject(request.body.readUtf8())
        assertEquals("user", body.getString("UserId"))
        assertEquals("source-a", body.getString("MediaSourceId"))
        assertEquals(123_450_000L, body.getLong("StartTimeTicks"))
        assertEquals(2, body.getInt("AudioStreamIndex"))
        assertEquals(3, body.getInt("SubtitleStreamIndex"))
        assertEquals(20_000_000, body.getInt("MaxStreamingBitrate"))
        assertFalse(body.has("DirectPlayProfiles"))

        val nestedProfile = body.getJSONObject("DeviceProfile")
        assertEquals(20_000_000, nestedProfile.getInt("MaxStreamingBitrate"))
        assertEquals(
            "mkv",
            nestedProfile.getJSONArray("DirectPlayProfiles")
                .getJSONObject(0)
                .getString("Container")
        )
        assertTrue(request.getHeader("X-Emby-Authorization").orEmpty().contains("Token="))
    }

    @Test
    fun `playback metadata request uses the same playback info dto contract`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"MediaSources\":[]}")
        )
        val session = NativeSession(
            token = "test-token",
            serverId = "server",
            userId = "metadata-user",
            userName = "Codex",
            serverUrl = server.url("/").toString().trimEnd('/')
        )

        val metadata = JellyfinNativeApi(RuntimeEnvironment.getApplication())
            .loadPlaybackInfoMetadata(
                session = session,
                itemId = "movie-metadata",
                bitrate = 25_000_000L
            )

        assertTrue(metadata.audioTracks.isEmpty())
        assertTrue(metadata.subtitleTracks.isEmpty())

        val request = server.takeRequest()
        val url = requireNotNull(request.requestUrl)
        assertEquals(listOf("Items", "movie-metadata", "PlaybackInfo"), url.pathSegments)
        assertEquals("metadata-user", url.queryParameter("UserId"))
        assertEquals("POST", request.method)

        val body = JSONObject(request.body.readUtf8())
        assertEquals("metadata-user", body.getString("UserId"))
        assertEquals(0L, body.getLong("StartTimeTicks"))
        assertEquals(25_000_000L, body.getLong("MaxStreamingBitrate"))
        assertEquals(
            25_000_000L,
            body.getJSONObject("DeviceProfile").getLong("MaxStreamingBitrate")
        )
        assertTrue(body.getJSONObject("DeviceProfile").getJSONArray("DirectPlayProfiles").length() > 0)
        assertTrue(body.getJSONObject("DeviceProfile").getJSONArray("TranscodingProfiles").length() > 0)
        assertTrue(body.getJSONObject("DeviceProfile").getJSONArray("SubtitleProfiles").length() > 0)
        assertFalse(body.has("MediaSourceId"))
        assertFalse(body.has("AudioStreamIndex"))
        assertFalse(body.has("SubtitleStreamIndex"))
        assertFalse(url.queryParameterNames.contains("StartTimeTicks"))
    }

    @Test
    fun `metadata keeps source tracks when an older server omits capability flags`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                    "MediaSources": [{
                        "Id": "legacy-source",
                        "MediaStreams": [
                            {"Type":"Audio","Index":2,"Language":"eng","Codec":"aac"},
                            {"Type":"Subtitle","Index":3,"Language":"eng","Codec":"pgssub"}
                        ]
                    }]
                }""".trimIndent()
            )
        )
        val session = NativeSession(
            token = "test-token",
            serverId = "server",
            userId = "legacy-user",
            userName = "Codex",
            serverUrl = server.url("/").toString().trimEnd('/')
        )

        val metadata = JellyfinNativeApi(RuntimeEnvironment.getApplication())
            .loadPlaybackInfoMetadata(session, "legacy-movie", 20_000_000L)

        assertEquals("legacy-source", metadata.mediaSourceId)
        assertEquals(listOf(2), metadata.audioTracks.map { it.index })
        assertEquals(listOf(3), metadata.subtitleTracks.map { it.index })
        assertEquals(1, metadata.mediaSources.size)
    }
}
