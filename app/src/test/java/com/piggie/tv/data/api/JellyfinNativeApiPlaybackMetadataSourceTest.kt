package com.piggie.tv.data.api

import com.piggie.tv.data.models.NativeSession
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class JellyfinNativeApiPlaybackMetadataSourceTest {
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
    fun `metadata binds tracks to the best playable source and exposes every source`() {
        val response = JSONObject().put(
            "MediaSources",
            JSONArray()
                .put(
                    JSONObject()
                        .put("Id", "unplayable")
                        .put("SupportsDirectPlay", false)
                        .put("SupportsDirectStream", false)
                        .put("SupportsTranscoding", false)
                        .put("MediaStreams", JSONArray().put(audioStream(1, "eng")))
                )
                .put(
                    JSONObject()
                        .put("Id", "playable")
                        .put("SupportsDirectPlay", true)
                        .put("Bitrate", 8_000_000L)
                        .put("MediaStreams", JSONArray().put(audioStream(7, "jpn")))
                )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(response.toString()))

        val metadata = JellyfinNativeApi(RuntimeEnvironment.getApplication())
            .loadPlaybackInfoMetadata(
                session = NativeSession(
                    token = "token",
                    serverId = "server",
                    userId = "user",
                    userName = "Codex",
                    serverUrl = server.url("/").toString().trimEnd('/')
                ),
                itemId = "movie",
                bitrate = 25_000_000L
            )

        assertEquals("playable", metadata.mediaSourceId)
        assertEquals(listOf(7), metadata.audioTracks.map { it.index })
        assertEquals(listOf("unplayable", "playable"), metadata.mediaSources.map { it.mediaSourceId })
        assertEquals(listOf(1), metadata.forMediaSource("unplayable")?.audioTracks?.map { it.index })
        assertEquals(listOf(7), metadata.forMediaSource("PLAYABLE")?.audioTracks?.map { it.index })
    }

    private fun audioStream(index: Int, language: String) = JSONObject()
        .put("Type", "Audio")
        .put("Index", index)
        .put("Language", language)
        .put("Codec", "aac")
        .put("Channels", 2)
}
