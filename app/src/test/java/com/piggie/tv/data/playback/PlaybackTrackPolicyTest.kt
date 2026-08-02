package com.piggie.tv.data.playback

import com.piggie.tv.data.models.AudioTrack
import com.piggie.tv.data.models.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTrackPolicyTest {
    @Test
    fun `sidecar MIME follows delivered format before source codec`() {
        assertEquals(
            PlaybackSubtitleSidecarPolicy.MIME_WEBVTT,
            PlaybackSubtitleSidecarPolicy.mimeType(
                codec = "subrip",
                deliveryUrl = "/Videos/item/source/Subtitles/4/Stream.vtt?api_key=redacted"
            )
        )
        assertEquals(
            PlaybackSubtitleSidecarPolicy.MIME_SUBRIP,
            PlaybackSubtitleSidecarPolicy.mimeType(
                codec = "webvtt",
                deliveryUrl = "/Videos/item/source/Subtitles/4/Stream.srt#caption"
            )
        )
    }

    @Test
    fun `sidecar MIME supports native Jellyfin text codecs and rejects image subtitles`() {
        assertEquals(
            PlaybackSubtitleSidecarPolicy.MIME_SSA,
            PlaybackSubtitleSidecarPolicy.mimeType("ass", null)
        )
        assertEquals(
            PlaybackSubtitleSidecarPolicy.MIME_TTML,
            PlaybackSubtitleSidecarPolicy.mimeType("ttml", null)
        )
        assertEquals(null, PlaybackSubtitleSidecarPolicy.mimeType("pgssub", null))
    }

    @Test
    fun `external sidecar fallback is attempted once and never from a server route`() {
        assertTrue(
            PlaybackSubtitleSidecarPolicy.shouldAttemptServerFallback(
                hasServerSelection = false,
                hasExplicitExternalSubtitle = true,
                alreadyAttempted = false
            )
        )
        assertFalse(
            PlaybackSubtitleSidecarPolicy.shouldAttemptServerFallback(
                hasServerSelection = false,
                hasExplicitExternalSubtitle = true,
                alreadyAttempted = true
            )
        )
        assertFalse(
            PlaybackSubtitleSidecarPolicy.shouldAttemptServerFallback(
                hasServerSelection = true,
                hasExplicitExternalSubtitle = true,
                alreadyAttempted = false
            )
        )
    }

    @Test
    fun `route result requires matching selection generation and value`() {
        assertTrue(PlaybackTrackRouteResultPolicy.isCurrent(3, 3, selectionMatches = true))
        assertFalse(PlaybackTrackRouteResultPolicy.isCurrent(3, 4, selectionMatches = true))
        assertFalse(PlaybackTrackRouteResultPolicy.isCurrent(3, 3, selectionMatches = false))
    }

    @Test
    fun `embedded Jellyfin index is not mistaken for a Media3 extractor id`() {
        val japanese = audioCandidate(
            group = 1,
            track = 0,
            externalId = "2",
            language = "jpn",
            label = "Japanese",
            codec = "aac"
        )
        val english = audioCandidate(
            group = 1,
            track = 1,
            externalId = "3",
            language = "eng",
            label = "English",
            codec = "aac"
        )

        assertEquals(
            PlaybackTrackResolution.Selected(english, PlaybackTrackMatchKind.METADATA),
            PlaybackTrackPolicy.selectAudio(
                track = audio(index = 2, language = "eng", title = "English", codec = "aac"),
                candidates = listOf(japanese, english),
                targetOrdinal = 1
            )
        )
    }

    @Test
    fun `default and off do not require player candidates`() {
        assertEquals(
            PlaybackTrackResolution.UseDefault,
            PlaybackTrackPolicy.resolve(PlaybackTrackSelection.Default, emptyList())
        )
        assertEquals(
            PlaybackTrackResolution.Disabled,
            PlaybackTrackPolicy.resolve(PlaybackTrackSelection.Off, emptyList())
        )
    }

    @Test
    fun `stable external id wins over a stronger metadata match`() {
        val selected = PlaybackTrackPolicy.selectAudio(
            track = audio(index = 7, language = "eng", title = "Commentary", codec = "aac"),
            externalId = "7",
            candidates = listOf(
                audioCandidate(
                    group = 0,
                    track = 0,
                    externalId = "7",
                    language = "spa",
                    label = "Spanish",
                    codec = "ac3"
                ),
                audioCandidate(
                    group = 0,
                    track = 1,
                    externalId = "2",
                    language = "en",
                    label = "Commentary",
                    codec = "aac"
                )
            )
        )

        assertEquals(
            PlaybackTrackResolution.Selected(
                audioCandidate(
                    group = 0,
                    track = 0,
                    externalId = "7",
                    language = "spa",
                    label = "Spanish",
                    codec = "ac3"
                ),
                PlaybackTrackMatchKind.EXTERNAL_ID
            ),
            selected
        )
    }

    @Test
    fun `metadata fallback understands language and codec aliases`() {
        val selected = PlaybackTrackPolicy.selectAudio(
            track = audio(
                index = 4,
                language = "eng",
                title = "Main Audio",
                codec = "aac",
                channels = 6
            ),
            candidates = listOf(
                audioCandidate(
                    group = 0,
                    track = 0,
                    externalId = null,
                    language = "en-US",
                    label = "Main Audio",
                    mimeType = "audio/mp4a-latm",
                    channels = 6
                )
            )
        )

        assertTrue(selected is PlaybackTrackResolution.Selected)
        selected as PlaybackTrackResolution.Selected
        assertEquals(PlaybackTrackMatchKind.METADATA, selected.matchedBy)
        assertEquals(0, selected.candidate.trackIndex)
    }

    @Test
    fun `duplicate language tracks are disambiguated by label codec and channels`() {
        val commentary = audioCandidate(
            group = 0,
            track = 2,
            externalId = null,
            language = "en",
            label = "Director Commentary",
            codec = "aac",
            channels = 2
        )
        val main = audioCandidate(
            group = 0,
            track = 1,
            externalId = null,
            language = "en",
            label = "English Main",
            codec = "eac3",
            channels = 6
        )

        val selected = PlaybackTrackPolicy.selectAudio(
            audio(
                index = 8,
                language = "eng",
                title = "Director Commentary",
                codec = "aac",
                channels = 2
            ),
            listOf(main, commentary)
        )

        assertEquals(
            PlaybackTrackResolution.Selected(commentary, PlaybackTrackMatchKind.METADATA),
            selected
        )
    }

    @Test
    fun `otherwise identical duplicates use stable player order`() {
        val later = audioCandidate(
            group = 2,
            track = 0,
            externalId = null,
            language = "en",
            codec = "aac"
        )
        val earlier = audioCandidate(
            group = 1,
            track = 3,
            externalId = null,
            language = "en",
            codec = "aac"
        )

        val selected = PlaybackTrackPolicy.selectAudio(
            audio(index = 9, language = "eng", codec = "aac"),
            listOf(later, earlier)
        )

        assertEquals(
            PlaybackTrackResolution.Selected(earlier, PlaybackTrackMatchKind.METADATA),
            selected
        )
    }

    @Test
    fun `target ordinal resolves otherwise identical ordered candidates`() {
        val first = audioCandidate(
            group = 0,
            track = 0,
            externalId = null,
            language = "en",
            codec = "aac"
        )
        val second = audioCandidate(
            group = 0,
            track = 1,
            externalId = null,
            language = "en",
            codec = "aac"
        )

        val selected = PlaybackTrackPolicy.selectAudio(
            track = audio(index = 9, language = "eng", codec = "aac"),
            candidates = listOf(first, second),
            externalId = null,
            targetOrdinal = 1
        )

        assertEquals(
            PlaybackTrackResolution.Selected(second, PlaybackTrackMatchKind.METADATA),
            selected
        )
    }

    @Test
    fun `side-loaded subtitle id uses the ptv stable id convention`() {
        val sideLoaded = textCandidate(
            track = 0,
            language = "fr",
            label = "Unexpected player label",
            mimeType = "application/x-subrip"
        ).copy(externalId = "ptv-subtitle-12")

        assertEquals(
            PlaybackTrackResolution.Selected(sideLoaded, PlaybackTrackMatchKind.EXTERNAL_ID),
            PlaybackTrackPolicy.selectSubtitle(
                SubtitleTrack(
                    index = 12,
                    language = "eng",
                    title = "English",
                    isDefault = false,
                    type = "External",
                    codec = "subrip",
                    isExternal = true
                ),
                listOf(sideLoaded)
            )
        )
    }

    @Test
    fun `supported candidate wins an otherwise identical metadata tie`() {
        val unsupported = audioCandidate(
            group = 0,
            track = 0,
            externalId = null,
            language = "en",
            codec = "aac",
            supported = false
        )
        val supported = audioCandidate(
            group = 0,
            track = 1,
            externalId = null,
            language = "en",
            codec = "aac",
            supported = true
        )

        val selected = PlaybackTrackPolicy.selectAudio(
            audio(index = 3, language = "eng", codec = "aac"),
            listOf(unsupported, supported)
        )

        assertEquals(
            PlaybackTrackResolution.Selected(supported, PlaybackTrackMatchKind.METADATA),
            selected
        )
    }

    @Test
    fun `an exact but unsupported selection is reported distinctly`() {
        val candidate = audioCandidate(
            group = 0,
            track = 0,
            externalId = "11",
            language = "en",
            supported = false
        )

        assertEquals(
            PlaybackTrackResolution.Unsupported(candidate, PlaybackTrackMatchKind.EXTERNAL_ID),
            PlaybackTrackPolicy.selectAudio(
                audio(index = 11, language = "eng"),
                listOf(candidate),
                externalId = "11"
            )
        )
    }

    @Test
    fun `wrong language and wrong type produce a missing result`() {
        val request = PlaybackTrackRequest.from(audio(index = 5, language = "eng"))
        val result = PlaybackTrackPolicy.resolve(
            PlaybackTrackSelection.Explicit(request),
            listOf(
                audioCandidate(
                    group = 0,
                    track = 0,
                    externalId = null,
                    language = "fr"
                ),
                PlaybackTrackCandidate(
                    type = PlaybackTrackType.TEXT,
                    groupIndex = 1,
                    trackIndex = 0,
                    language = "en"
                )
            )
        )

        assertEquals(PlaybackTrackResolution.Missing(request), result)
    }

    @Test
    fun `subtitle metadata matches subrip MIME and duplicate language label`() {
        val full = textCandidate(
            track = 0,
            language = "en",
            label = "English",
            mimeType = "application/x-subrip"
        )
        val sdh = textCandidate(
            track = 1,
            language = "en",
            label = "English SDH",
            mimeType = "application/x-subrip"
        )

        val selected = PlaybackTrackPolicy.selectSubtitle(
            SubtitleTrack(
                index = 6,
                language = "eng",
                title = "English SDH",
                isDefault = false,
                type = "External",
                codec = "subrip"
            ),
            listOf(full, sdh),
            externalId = null
        )

        assertEquals(
            PlaybackTrackResolution.Selected(sdh, PlaybackTrackMatchKind.METADATA),
            selected
        )
    }

    private fun audio(
        index: Int,
        language: String? = null,
        title: String? = null,
        codec: String? = null,
        channels: Int? = null
    ) = AudioTrack(
        index = index,
        language = language,
        title = title,
        codec = codec,
        isDefault = false,
        channels = channels
    )

    private fun audioCandidate(
        group: Int,
        track: Int,
        externalId: String?,
        language: String? = null,
        label: String? = null,
        mimeType: String? = null,
        codec: String? = null,
        channels: Int? = null,
        supported: Boolean = true
    ) = PlaybackTrackCandidate(
        type = PlaybackTrackType.AUDIO,
        groupIndex = group,
        trackIndex = track,
        language = language,
        label = label,
        mimeType = mimeType,
        codec = codec,
        channelCount = channels,
        isSupported = supported,
        externalId = externalId
    )

    private fun textCandidate(
        track: Int,
        language: String? = null,
        label: String? = null,
        mimeType: String? = null,
        supported: Boolean = true
    ) = PlaybackTrackCandidate(
        type = PlaybackTrackType.TEXT,
        groupIndex = 0,
        trackIndex = track,
        language = language,
        label = label,
        mimeType = mimeType,
        isSupported = supported
    )
}
