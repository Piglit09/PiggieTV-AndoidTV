package com.piggie.tv.data.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JellyfinPlaybackMetadataParserTest {
    @Test
    fun `source identity and external subtitle capability stay attached to stream indices`() {
        val mediaSources = JSONArray().put(
            JSONObject().apply {
                put("Id", "source-a")
                put(
                    "MediaStreams",
                    JSONArray()
                        .put(
                            JSONObject().apply {
                                put("Type", "Subtitle")
                                put("Index", 0)
                                put("Language", "eng")
                                put("DisplayTitle", "English - SUBRIP - External")
                                put("Codec", "subrip")
                                put("IsExternal", true)
                                put("SupportsExternalStream", true)
                            }
                        )
                        .put(
                            JSONObject().apply {
                                put("Type", "Audio")
                                put("Index", 2)
                                put("Language", "eng")
                                put("Codec", "aac")
                                put("Channels", 6)
                                put("IsDefault", true)
                            }
                        )
                        .put(
                            JSONObject().apply {
                                put("Type", "Subtitle")
                                put("Index", 3)
                                put("Language", "eng")
                                put("Codec", "pgssub")
                                put("IsDefault", true)
                                put("IsExternal", false)
                            }
                        )
                )
            }
        )

        val parsed = JellyfinPlaybackMetadataParser.parseFirstSource(mediaSources)

        assertEquals("source-a", parsed.mediaSourceId)
        assertEquals(listOf(2), parsed.audioTracks.map { it.index })
        assertEquals(6, parsed.audioTracks.single().channels)
        assertEquals(listOf(0, 3), parsed.subtitleTracks.map { it.index })
        assertTrue(parsed.subtitleTracks.first().isExternal)
        assertTrue(parsed.subtitleTracks.first().supportsExternalStream)
        assertFalse(parsed.subtitleTracks.last().isExternal)
        assertNull(parsed.subtitleTracks.last().deliveryUrl)
    }

    @Test
    fun `missing media sources produce empty metadata without a synthetic source id`() {
        val parsed = JellyfinPlaybackMetadataParser.parseFirstSource(null)

        assertNull(parsed.mediaSourceId)
        assertTrue(parsed.audioTracks.isEmpty())
        assertTrue(parsed.subtitleTracks.isEmpty())
    }

    @Test
    fun `all media sources retain their own stream indices and capabilities`() {
        val mediaSources = JSONArray()
            .put(
                JSONObject().apply {
                    put("Id", "source-a")
                    put("SupportsDirectPlay", false)
                    put("SupportsTranscoding", false)
                    put("MediaStreams", JSONArray().put(audioStream(index = 1, language = "eng")))
                }
            )
            .put(
                JSONObject().apply {
                    put("Id", "source-b")
                    put("SupportsDirectPlay", true)
                    put("Bitrate", 8_000_000L)
                    put("MediaStreams", JSONArray().put(audioStream(index = 7, language = "jpn")))
                }
            )

        val parsed = JellyfinPlaybackMetadataParser.parseSources(mediaSources)

        assertEquals(listOf("source-a", "source-b"), parsed.map { it.mediaSourceId })
        assertEquals(listOf(1), parsed[0].audioTracks.map { it.index })
        assertEquals(listOf(7), parsed[1].audioTracks.map { it.index })
        assertFalse(parsed[0].supportsDirectPlay)
        assertTrue(parsed[1].supportsDirectPlay)
        assertEquals(8_000_000L, parsed[1].bitrate)
        assertEquals(setOf(7), parsed[1].asCandidate()?.audioStreamIndices)
    }

    @Test
    fun `requested media source returns only its matching metadata`() {
        val mediaSources = JSONArray()
            .put(
                JSONObject().apply {
                    put("Id", "source-a")
                    put("MediaStreams", JSONArray().put(audioStream(index = 1, language = "eng")))
                }
            )
            .put(
                JSONObject().apply {
                    put("Id", "source-b")
                    put("MediaStreams", JSONArray().put(audioStream(index = 9, language = "fra")))
                }
            )

        val parsed = JellyfinPlaybackMetadataParser.parseSource(mediaSources, "SOURCE-B")

        assertEquals("source-b", parsed?.mediaSourceId)
        assertEquals(listOf(9), parsed?.audioTracks?.map { it.index })
    }

    private fun audioStream(index: Int, language: String) = JSONObject().apply {
        put("Type", "Audio")
        put("Index", index)
        put("Language", language)
        put("Codec", "aac")
        put("Channels", 2)
    }
}
