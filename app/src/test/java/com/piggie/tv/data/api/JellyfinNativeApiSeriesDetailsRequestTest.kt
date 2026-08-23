package com.piggie.tv.data.api

import com.piggie.tv.data.models.NativeSession
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class JellyfinNativeApiSeriesDetailsRequestTest {
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
    fun `series queue loads every episode without a season boundary`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                    "Items":[
                        {
                            "Id":"s1e1",
                            "Name":"First",
                            "Type":"Episode",
                            "SeriesId":"series-1",
                            "SeasonId":"season-1",
                            "ParentIndexNumber":1,
                            "IndexNumber":1
                        },
                        {
                            "Id":"s2e1",
                            "Name":"Second Season",
                            "Type":"Episode",
                            "SeriesId":"series-1",
                            "SeasonId":"season-2",
                            "ParentIndexNumber":2,
                            "IndexNumber":1
                        }
                    ]
                }""".trimIndent()
            )
        )
        val session = NativeSession(
            token = "test-token",
            serverId = "server",
            userId = "details-user",
            userName = "Codex",
            serverUrl = server.url("/").toString().trimEnd('/')
        )

        val episodes = JellyfinNativeApi(RuntimeEnvironment.getApplication())
            .loadSeriesEpisodes(session, "series-1")

        assertEquals(listOf("s1e1", "s2e1"), episodes.map { it.id })
        val request = server.takeRequest()
        val url = requireNotNull(request.requestUrl)
        assertEquals("/Shows/series-1/Episodes", url.encodedPath)
        assertEquals("details-user", url.queryParameter("UserId"))
        assertEquals("true", url.queryParameter("EnableUserData"))
        assertNull(url.queryParameter("SeasonId"))
        assertEquals("0", url.queryParameter("StartIndex"))
        assertEquals("200", url.queryParameter("Limit"))
        assertEquals("true", url.queryParameter("EnableTotalRecordCount"))
        assertEquals(
            setOf("PrimaryImageAspectRatio"),
            url.queryParameter("Fields").orEmpty().split(',').toSet()
        )
        assertTrue(request.getHeader("X-Emby-Authorization").orEmpty().contains("Token="))
    }

    @Test
    fun `series queue follows total count across bounded pages`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                    "StartIndex":0,
                    "TotalRecordCount":3,
                    "Items":[
                        {"Id":"episode-1","Name":"One","Type":"Episode"},
                        {"Id":"episode-2","Name":"Two","Type":"Episode"}
                    ]
                }""".trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                    "StartIndex":2,
                    "TotalRecordCount":3,
                    "Items":[
                        {"Id":"episode-3","Name":"Three","Type":"Episode"}
                    ]
                }""".trimIndent()
            )
        )
        val session = NativeSession(
            token = "test-token",
            serverId = "server",
            userId = "details-user",
            userName = "Codex",
            serverUrl = server.url("/").toString().trimEnd('/')
        )

        val episodes = JellyfinNativeApi(RuntimeEnvironment.getApplication())
            .loadSeriesEpisodes(session, "series-1")

        assertEquals(listOf("episode-1", "episode-2", "episode-3"), episodes.map { it.id })
        val first = requireNotNull(server.takeRequest().requestUrl)
        val second = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("0", first.queryParameter("StartIndex"))
        assertEquals("2", second.queryParameter("StartIndex"))
        assertEquals("200", first.queryParameter("Limit"))
        assertEquals("200", second.queryParameter("Limit"))
    }

}
