package com.piggie.tv.data.playback

import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.NativeSettings
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PlaybackNegotiatorIntegrationTest {
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
    fun `direct stream uses Jellyfin transcoding URL and keeps Media3 timeline absolute`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                    "PlaySessionId":"play-1",
                    "MediaSources":[{
                        "Id":"source-1",
                        "Container":"mkv",
                        "SupportsDirectPlay":false,
                        "SupportsDirectStream":true,
                        "SupportsTranscoding":true,
                        "TranscodingUrl":"/Videos/movie/stream.ts?VideoCodec=copy&AudioCodec=copy&Container=ts&StartTimeTicks=0&custom=keep",
                        "MediaStreams":[
                            {"Type":"Video","Index":0,"Codec":"hevc","Width":3840,"Height":2160},
                            {"Type":"Audio","Index":1,"Codec":"eac3","IsDefault":true}
                        ]
                    }]
                }""".trimIndent()
            )
        )
        val context = RuntimeEnvironment.getApplication()
        val session = NativeSession(
            token = "token",
            serverId = "server",
            userId = "user",
            userName = "Viewer",
            serverUrl = server.url("/").toString().trimEnd('/')
        )

        val stream = PlaybackNegotiator(
            JellyfinNativeApi(context),
            NativeSettings(context)
        ).getPlaybackStream(
            session = session,
            itemId = "movie",
            positionTicks = 600_000_000L
        )

        assertEquals("DirectStream", stream.method)
        assertEquals("play-1", stream.playSessionId)
        assertEquals("source-1", stream.mediaSourceId)
        assertEquals(listOf(1), stream.audioTracks.map { it.index })
        assertEquals("eac3", stream.audioTracks.single().codec)
        val streamUrl = requireNotNull(stream.url.toHttpUrlOrNull())
        assertEquals("copy", streamUrl.queryParameter("VideoCodec"))
        assertEquals("copy", streamUrl.queryParameter("AudioCodec"))
        assertEquals("ts", streamUrl.queryParameter("Container"))
        assertEquals("keep", streamUrl.queryParameter("custom"))
        assertEquals("source-1", streamUrl.queryParameter("MediaSourceId"))
        assertEquals("play-1", streamUrl.queryParameter("PlaySessionId"))

        val request = server.takeRequest()
        assertFalse(requireNotNull(request.requestUrl).queryParameterNames.contains("StartTimeTicks"))
        assertEquals(0L, JSONObject(request.body.readUtf8()).getLong("StartTimeTicks"))
        assertTrue(request.bodySize > 0L)
    }

    @Test
    fun `explicit audio falls back to transcode when direct stream URL is absent`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                    "PlaySessionId":"play-2",
                    "MediaSources":[{
                        "Id":"source-2",
                        "Container":"mkv",
                        "SupportsDirectPlay":true,
                        "SupportsDirectStream":true,
                        "SupportsTranscoding":true,
                        "MediaStreams":[
                            {"Type":"Video","Index":0,"Codec":"h264"},
                            {"Type":"Audio","Index":4,"Codec":"aac","IsDefault":true}
                        ]
                    }]
                }""".trimIndent()
            )
        )
        val context = RuntimeEnvironment.getApplication()
        val session = NativeSession(
            token = "token",
            serverId = "server",
            userId = "user",
            userName = "Viewer",
            serverUrl = server.url("/").toString().trimEnd('/')
        )

        val stream = PlaybackNegotiator(
            JellyfinNativeApi(context),
            NativeSettings(context)
        ).getPlaybackStream(
            session = session,
            itemId = "movie",
            positionTicks = 0L,
            audioIndex = 4,
            preferredMediaSourceId = "source-2"
        )

        assertEquals("Transcode", stream.method)
        assertTrue(stream.url.contains("/Videos/movie/master.m3u8"))
        assertEquals("4", requireNotNull(stream.url.toHttpUrlOrNull())
            .queryParameter("AudioStreamIndex"))
    }
}
