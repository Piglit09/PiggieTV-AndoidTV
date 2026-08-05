package com.piggie.tv.data.api

import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.PlaybackSkipSegmentType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class JellyfinNativeApiSkipSegmentsTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `native media segments request returns only valid intro and outro ranges`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                    "Items":[
                        {"Type":"Intro","StartTicks":10000000,"EndTicks":75000000},
                        {"Type":"Outro","StartTicks":500000000,"EndTicks":600000000},
                        {"Type":"Commercial","StartTicks":80000000,"EndTicks":90000000},
                        {"Type":"Intro","StartTicks":10,"EndTicks":10}
                    ],
                    "TotalRecordCount":4
                }""".trimIndent()
            )
        )

        val segments = JellyfinNativeApi(RuntimeEnvironment.getApplication())
            .loadPlaybackSkipSegments(session(), "episode/one")

        assertEquals(listOf(PlaybackSkipSegmentType.INTRO, PlaybackSkipSegmentType.OUTRO), segments.map { it.type })
        assertEquals(75_000_000L, segments.first().endTicks)
        val request = server.takeRequest()
        assertEquals("/MediaSegments/episode%2Fone", request.requestUrl?.encodedPath)
        assertEquals(listOf("Intro", "Outro"), request.requestUrl?.queryParameterValues("includeSegmentTypes"))
    }

    @Test fun `exact Jellyfin chapter markers provide fallback without guessing names`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                    "Id":"episode-1",
                    "Name":"Pilot",
                    "Type":"Episode",
                    "RunTimeTicks":600000000,
                    "Chapters":[
                        {"Name":"Intro","MarkerType":"IntroStart","StartPositionTicks":10000000},
                        {"Name":"Intro End","MarkerType":"IntroEnd","StartPositionTicks":75000000},
                        {"Name":"Credits","MarkerType":"CreditsStart","StartPositionTicks":500000000},
                        {"Name":"Looks like an intro","StartPositionTicks":80000000}
                    ]
                }""".trimIndent()
            )
        )

        val item = JellyfinNativeApi(RuntimeEnvironment.getApplication())
            .loadItem(session(), "episode-1")

        assertEquals(2, item.playbackSkipSegments.size)
        assertEquals(PlaybackSkipSegmentType.INTRO, item.playbackSkipSegments[0].type)
        assertEquals(75_000_000L, item.playbackSkipSegments[0].endTicks)
        assertEquals(PlaybackSkipSegmentType.OUTRO, item.playbackSkipSegments[1].type)
        assertEquals(item.runtimeTicks, item.playbackSkipSegments[1].endTicks)
        val fields = server.takeRequest().requestUrl?.queryParameter("Fields").orEmpty().split(',')
        assertTrue("Chapters" in fields)
    }

    @Test fun `ordinary previous episode is resolved from ordered series response`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                    "Items":[
                        {"Id":"episode-1","Name":"One","Type":"Episode","SeriesId":"series-1"},
                        {"Id":"episode-2","Name":"Two","Type":"Episode","SeriesId":"series-1"}
                    ]
                }""".trimIndent()
            )
        )
        val current = MediaItem(
            id = "episode-2",
            title = "Two",
            type = "Episode",
            year = null,
            imageTag = null,
            seriesName = "Series",
            episodeLabel = "S1 E2",
            playbackPositionTicks = 0L,
            runtimeTicks = 1L,
            seriesId = "series-1"
        )

        val previous = JellyfinNativeApi(RuntimeEnvironment.getApplication())
            .loadPreviousEpisode(session(), current)

        assertEquals("episode-1", previous?.id)
        val url = server.takeRequest().requestUrl
        assertEquals(listOf("Shows", "series-1", "Episodes"), url?.pathSegments)
        assertEquals("true", url?.queryParameter("EnableUserData"))
    }

    private fun session() = NativeSession(
        token = "test-token",
        serverId = "server",
        userId = "user",
        userName = "Codex",
        serverUrl = server.url("/").toString().trimEnd('/')
    )
}
