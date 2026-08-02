package com.piggie.tv.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackMediaSourcePolicyTest {
    @Test
    fun `unplayable preferred source falls through to a playable source`() {
        val plans = PlaybackMediaSourcePolicy.orderedPlans(
            candidates = listOf(
                source(
                    id = "broken",
                    order = 0,
                    directPlay = false,
                    directStream = false,
                    transcode = false
                ),
                source(id = "fallback", order = 1, directPlay = true)
            ),
            preferredMediaSourceId = "broken"
        )

        assertEquals(listOf("fallback"), plans.map { it.source.id })
        assertEquals(PlaybackRoute.DIRECT_PLAY, plans.single().route)
    }

    @Test
    fun `playable preferred source stays bound ahead of another source`() {
        val plans = PlaybackMediaSourcePolicy.orderedPlans(
            candidates = listOf(
                source(id = "first", order = 0, directPlay = true),
                source(id = "preferred", order = 1, directStream = true)
            ),
            preferredMediaSourceId = "preferred"
        )

        assertEquals(listOf("preferred", "first"), plans.map { it.source.id })
    }

    @Test
    fun `explicit audio selection never reuses an index from another source`() {
        val plans = PlaybackMediaSourcePolicy.orderedPlans(
            candidates = listOf(
                source(
                    id = "wrong-tracks",
                    order = 0,
                    directStream = true,
                    audio = setOf(1)
                ),
                source(
                    id = "matching-tracks",
                    order = 1,
                    directStream = true,
                    audio = setOf(7)
                )
            ),
            preferredMediaSourceId = "wrong-tracks",
            audioStreamIndex = 7
        )

        assertTrue(plans.isEmpty())
    }

    @Test
    fun `subtitle selection requires a source that can transcode and owns the subtitle`() {
        val plan = PlaybackMediaSourcePolicy.choose(
            candidates = listOf(
                source(
                    id = "no-transcode",
                    order = 0,
                    directPlay = true,
                    transcode = false,
                    subtitles = setOf(3)
                ),
                source(
                    id = "transcode",
                    order = 1,
                    transcode = true,
                    subtitles = setOf(3)
                )
            ),
            preferredMediaSourceId = "transcode",
            subtitleStreamIndex = 3
        )

        assertEquals("transcode", plan?.source?.id)
        assertEquals(PlaybackRoute.TRANSCODE, plan?.route)
    }

    @Test
    fun `bitrate cap removes a source that cannot transcode`() {
        val plans = PlaybackMediaSourcePolicy.orderedPlans(
            candidates = listOf(
                source(
                    id = "too-large",
                    order = 0,
                    directPlay = true,
                    transcode = false,
                    bitrate = 50_000_000L
                )
            ),
            maxAllowedBitrate = 20_000_000L
        )

        assertTrue(plans.isEmpty())
    }

    private fun source(
        id: String,
        order: Int,
        directPlay: Boolean = false,
        directStream: Boolean = false,
        transcode: Boolean = false,
        bitrate: Long? = null,
        audio: Set<Int> = emptySet(),
        subtitles: Set<Int> = emptySet()
    ) = PlaybackMediaSourceCandidate(
        id = id,
        sourceIndex = order,
        supportsDirectPlay = directPlay,
        supportsDirectStream = directStream,
        supportsTranscoding = transcode,
        bitrate = bitrate,
        audioStreamIndices = audio,
        subtitleStreamIndices = subtitles
    )
}
