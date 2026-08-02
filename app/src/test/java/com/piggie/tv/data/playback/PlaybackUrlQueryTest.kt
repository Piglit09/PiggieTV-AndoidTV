package com.piggie.tv.data.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackUrlQueryTest {

    @Test
    fun `absolute URL replaces stale audio index and preserves parameters and fragment`() {
        assertEquals(
            "https://media.example.test/Videos/item/master.m3u8" +
                "?MediaSourceId=source&AudioStreamIndex=4&PlaySessionId=session#playback",
            PlaybackUrlQuery.set(
                "https://media.example.test/Videos/item/master.m3u8" +
                    "?MediaSourceId=source&AudioStreamIndex=0&PlaySessionId=session#playback",
                name = "AudioStreamIndex",
                value = "4"
            )
        )
    }

    @Test
    fun `relative URL replaces stale subtitle index and method`() {
        val withSelectedSubtitle = PlaybackUrlQuery.set(
            "/Videos/item/master.m3u8" +
                "?MediaSourceId=source&SubtitleStreamIndex=0&SubtitleMethod=Embed",
            name = "SubtitleStreamIndex",
            value = "3"
        )

        assertEquals(
            "/Videos/item/master.m3u8" +
                "?MediaSourceId=source&SubtitleStreamIndex=3&SubtitleMethod=Encode",
            PlaybackUrlQuery.set(
                withSelectedSubtitle,
                name = "SubtitleMethod",
                value = "Encode"
            )
        )
    }

    @Test
    fun `existing key matching is case insensitive and duplicates are removed`() {
        assertEquals(
            "/Videos/item/stream?before=1&AudioStreamIndex=7&after=2",
            PlaybackUrlQuery.set(
                "/Videos/item/stream" +
                    "?before=1&aUdIoStReAmInDeX=0&AUDIOSTREAMINDEX=2&after=2",
                name = "AudioStreamIndex",
                value = "7"
            )
        )
    }

    @Test
    fun `relative URL without query receives an encoded parameter before its fragment`() {
        assertEquals(
            "Videos/item/stream?SubtitleMethod=Encode#preview",
            PlaybackUrlQuery.set(
                "Videos/item/stream#preview",
                name = "SubtitleMethod",
                value = "Encode"
            )
        )
    }

    @Test
    fun `absolute and relative URL values use HttpUrl percent encoding`() {
        val name = "Track Name"
        val value = "English / SDH & commentary"
        val encoded = "Track%20Name=English%20%2F%20SDH%20%26%20commentary"

        assertEquals(
            "https://media.example.test/stream?$encoded",
            PlaybackUrlQuery.set("https://media.example.test/stream", name, value)
        )
        assertEquals(
            "/Videos/item/stream?$encoded",
            PlaybackUrlQuery.set("/Videos/item/stream", name, value)
        )
    }

    @Test
    fun `setting the same relative parameter repeatedly is deterministic`() {
        val once = PlaybackUrlQuery.set(
            "/Videos/item/stream?Static=false&AudioStreamIndex=1#fragment",
            "AudioStreamIndex",
            "5"
        )

        assertEquals(once, PlaybackUrlQuery.set(once, "AudioStreamIndex", "5"))
    }
}
