package com.piggie.tv.data.api

import com.piggie.tv.data.models.AudioTrack
import com.piggie.tv.data.models.SubtitleTrack
import com.piggie.tv.data.playback.PlaybackMediaSourceCandidate
import org.json.JSONArray

internal data class ParsedPlaybackTracks(
    val mediaSourceId: String?,
    val audioTracks: List<AudioTrack>,
    val subtitleTracks: List<SubtitleTrack>,
    val sourceIndex: Int = -1,
    val supportsDirectPlay: Boolean = false,
    val supportsDirectStream: Boolean = false,
    val supportsTranscoding: Boolean = false,
    val bitrate: Long? = null
) {
    fun asCandidate(): PlaybackMediaSourceCandidate? {
        val id = mediaSourceId?.takeIf(String::isNotBlank) ?: return null
        if (sourceIndex < 0) return null
        return PlaybackMediaSourceCandidate(
            id = id,
            sourceIndex = sourceIndex,
            supportsDirectPlay = supportsDirectPlay,
            supportsDirectStream = supportsDirectStream,
            supportsTranscoding = supportsTranscoding,
            bitrate = bitrate,
            audioStreamIndices = audioTracks.mapTo(mutableSetOf()) { it.index },
            subtitleStreamIndices = subtitleTracks.mapTo(mutableSetOf()) { it.index }
        )
    }
}

/** Parses stream indices and source identity as one unit so they cannot drift across versions. */
internal object JellyfinPlaybackMetadataParser {
    fun parseSources(mediaSources: JSONArray?): List<ParsedPlaybackTracks> {
        if (mediaSources == null) return emptyList()
        return (0 until mediaSources.length()).mapNotNull { sourceIndex ->
            mediaSources.optJSONObject(sourceIndex)?.let { source ->
                parseSource(source, sourceIndex)
            }
        }
    }

    fun parseSource(
        mediaSources: JSONArray?,
        mediaSourceId: String
    ): ParsedPlaybackTracks? = parseSources(mediaSources).firstOrNull {
        it.mediaSourceId.equals(mediaSourceId, ignoreCase = true)
    }

    fun parseFirstSource(mediaSources: JSONArray?): ParsedPlaybackTracks =
        parseSources(mediaSources).firstOrNull() ?: ParsedPlaybackTracks(
            mediaSourceId = null,
            audioTracks = emptyList(),
            subtitleTracks = emptyList()
        )

    private fun parseSource(
        source: org.json.JSONObject,
        sourceIndex: Int
    ): ParsedPlaybackTracks {
        val streams = source?.optJSONArray("MediaStreams")
        val audioTracks = mutableListOf<AudioTrack>()
        val subtitleTracks = mutableListOf<SubtitleTrack>()

        if (streams != null) {
            for (position in 0 until streams.length()) {
                val stream = streams.optJSONObject(position) ?: continue
                val index = stream.optInt("Index")
                val language = stream.optString("Language").ifBlank { null }
                val title = stream.optString("DisplayTitle").ifBlank { null }
                val isDefault = stream.optBoolean("IsDefault", false)
                when (stream.optString("Type")) {
                    "Audio" -> audioTracks += AudioTrack(
                        index = index,
                        language = language,
                        title = title,
                        codec = stream.optString("Codec").ifBlank { null },
                        isDefault = isDefault,
                        channels = stream.optInt("Channels").takeIf { it > 0 }
                    )

                    "Subtitle" -> subtitleTracks += SubtitleTrack(
                        index = index,
                        language = language,
                        title = title,
                        isDefault = isDefault,
                        type = stream.optString("DeliveryMethod").ifBlank { null },
                        isForced = stream.optBoolean("IsForced", false),
                        codec = stream.optString("Codec").ifBlank { null },
                        isExternal = stream.optBoolean("IsExternal", false),
                        supportsExternalStream = stream.optBoolean(
                            "SupportsExternalStream",
                            false
                        ),
                        deliveryUrl = stream.optString("DeliveryUrl").ifBlank { null }
                    )
                }
            }
        }

        return ParsedPlaybackTracks(
            mediaSourceId = source.optString("Id").ifBlank { null },
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            sourceIndex = sourceIndex,
            supportsDirectPlay = source.optBoolean("SupportsDirectPlay", false),
            supportsDirectStream = source.optBoolean("SupportsDirectStream", false),
            supportsTranscoding = source.optBoolean("SupportsTranscoding", false),
            bitrate = source.optLong("Bitrate", 0L).takeIf { it > 0L }
        )
    }
}
