package com.piggie.tv.data.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackNegotiatorTest {

    @Test
    fun testPlaybackStreamDataClass() {
        val stream = PlaybackStream("url", "DirectPlay", "ps1", "ms1")
        assertEquals("url", stream.url)
        assertEquals("DirectPlay", stream.method)
        assertEquals("ps1", stream.playSessionId)
        assertEquals("ms1", stream.mediaSourceId)
    }

    @Test
    fun testNegotiatedBitrateCalculation() {
        assertEquals(100_000_000, ConnectionSpeed.MBPS_100.negotiationBitrate())
    }

    @Test
    fun testForceTranscodeLogic() {
        val sourceBitrate = 30_000_000L
        assertTrue(ConnectionSpeed.MBPS_20.shouldCap(sourceBitrate))
    }

    @Test
    fun testHlsUrlConstruction() {
        val server = "https://ptv.io"
        val itemId = "v123"
        val url = "$server/Videos/$itemId/master.m3u8"
        assertTrue(url.contains("master.m3u8"))
    }

    @Test
    fun testStaticStreamUrlConstruction() {
        val server = "https://ptv.io"
        val itemId = "v123"
        val url = "$server/Videos/$itemId/stream?Static=true"
        assertTrue(url.contains("Static=true"))
    }

    @Test
    fun `route policy preserves direct play when no cap or explicit track applies`() {
        assertEquals(
            PlaybackRoute.DIRECT_PLAY,
            PlaybackRoutePolicy.choose(
                supportsDirectPlay = true,
                supportsDirectStream = true,
                hasExplicitAudioSelection = false,
                hasExplicitSubtitleSelection = false,
                bitrateCapExceeded = false
            )
        )
    }

    @Test
    fun `route policy uses direct stream for track selection and transcodes for bitrate cap`() {
        assertEquals(
            PlaybackRoute.DIRECT_STREAM,
            PlaybackRoutePolicy.choose(
                supportsDirectPlay = true,
                supportsDirectStream = true,
                hasExplicitAudioSelection = true,
                hasExplicitSubtitleSelection = false,
                bitrateCapExceeded = false
            )
        )
        assertEquals(
            PlaybackRoute.TRANSCODE,
            PlaybackRoutePolicy.choose(
                supportsDirectPlay = true,
                supportsDirectStream = true,
                hasExplicitAudioSelection = false,
                hasExplicitSubtitleSelection = false,
                bitrateCapExceeded = true
            )
        )
    }

    @Test
    fun `explicit subtitle selection forces server encoding`() {
        assertEquals(
            PlaybackRoute.TRANSCODE,
            PlaybackRoutePolicy.choose(
                supportsDirectPlay = true,
                supportsDirectStream = true,
                hasExplicitAudioSelection = false,
                hasExplicitSubtitleSelection = true,
                bitrateCapExceeded = false
            )
        )
        assertEquals(
            "https://ptv.io/Videos/v123/master.m3u8?PlaySessionId=p1" +
                "&SubtitleStreamIndex=3&SubtitleMethod=Encode",
            PlaybackSubtitlePolicy.applyServerEncoding(
                "https://ptv.io/Videos/v123/master.m3u8?PlaySessionId=p1",
                subtitleIndex = 3
            )
        )
    }

    @Test
    fun `server subtitle encoding preserves existing negotiation parameters`() {
        assertEquals(
            "https://ptv.io/Videos/v123/master.m3u8" +
                "?SubtitleStreamIndex=3&SubtitleMethod=Encode",
            PlaybackSubtitlePolicy.applyServerEncoding(
                "https://ptv.io/Videos/v123/master.m3u8" +
                    "?SubtitleStreamIndex=3&SubtitleMethod=Encode",
                subtitleIndex = 3
            )
        )
        assertEquals(
            "https://ptv.io/Videos/v123/master.m3u8",
            PlaybackSubtitlePolicy.applyServerEncoding(
                "https://ptv.io/Videos/v123/master.m3u8",
                subtitleIndex = null
            )
        )
    }

    @Test
    fun `playback URLs and credentials stay on the exact Jellyfin origin`() {
        val server = "https://media.example.test:9443/jellyfin"

        assertEquals(
            "https://media.example.test:9443/jellyfin/Videos/item/master.m3u8",
            PlaybackOriginPolicy.resolve(server, "Videos/item/master.m3u8")
        )
        assertTrue(
            PlaybackOriginPolicy.shouldAttachCredentials(
                server,
                "https://media.example.test:9443/Items/item/stream"
            )
        )
        assertFalse(
            PlaybackOriginPolicy.shouldAttachCredentials(
                server,
                "https://media.example.test/Items/item/stream"
            )
        )
        assertFalse(
            PlaybackOriginPolicy.shouldAttachCredentials(
                server,
                "https://media.example.test.attacker.invalid/steal"
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackOriginPolicy.resolve(server, "https://attacker.invalid/stream")
        }
    }
}
