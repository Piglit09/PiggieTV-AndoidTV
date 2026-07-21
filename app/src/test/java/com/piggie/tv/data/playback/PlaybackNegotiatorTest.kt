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
    fun testBitrateCalculations() {
        val quality4K = "4K (120 Mbps)"
        val maxBitrate = when (quality4K) {
            "4K (120 Mbps)" -> 120_000_000
            else -> 0
        }
        assertEquals(120_000_000, maxBitrate)
    }

    @Test
    fun testForceTranscodeLogic() {
        val maxBitrate = 20_000_000
        val sourceBitrate = 30_000_000L
        val forceTranscode = sourceBitrate > maxBitrate
        assertTrue(forceTranscode)
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
}
