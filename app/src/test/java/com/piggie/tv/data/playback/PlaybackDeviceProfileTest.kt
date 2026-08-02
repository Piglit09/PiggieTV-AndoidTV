package com.piggie.tv.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackDeviceProfileTest {
    @Test
    fun `video transcoding profile matches HLS TS h264 aac fallback`() {
        val profile = PlaybackDeviceProfile.build(20_000_000)
        val transcoding = profile.getJSONArray("TranscodingProfiles")
        val video = (0 until transcoding.length())
            .map(transcoding::getJSONObject)
            .firstOrNull { it.optString("Type") == "Video" }

        assertNotNull(video)
        requireNotNull(video)
        assertEquals("ts", video.getString("Container"))
        assertEquals("hls", video.getString("Protocol"))
        assertEquals("Streaming", video.getString("Context"))
        assertEquals("h264", video.getString("VideoCodec"))
        assertEquals("aac", video.getString("AudioCodec"))
        assertEquals("2", video.getString("MaxAudioChannels"))
        assertEquals(20_000_000, profile.getInt("MaxStreamingBitrate"))
        assertEquals(20_000_000, profile.getInt("MaxVideoBitrate"))
    }

    @Test
    fun `subtitle profiles externalize text and encode image formats`() {
        val subtitles = PlaybackDeviceProfile.build(100_000_000)
            .getJSONArray("SubtitleProfiles")
        val methods = (0 until subtitles.length())
            .map(subtitles::getJSONObject)
            .associate { it.getString("Format") to it.getString("Method") }

        listOf("srt", "subrip", "vtt", "webvtt", "ttml", "dfxp", "ass", "ssa")
            .forEach { format ->
            assertEquals("External", methods[format])
        }
        listOf("pgssub", "dvdsub", "dvbsub").forEach { format ->
            assertEquals("Encode", methods[format])
        }
        assertTrue(methods.isNotEmpty())
    }
}
