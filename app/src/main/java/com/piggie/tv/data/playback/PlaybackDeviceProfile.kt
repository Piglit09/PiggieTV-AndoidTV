package com.piggie.tv.data.playback

import org.json.JSONArray
import org.json.JSONObject

/** Jellyfin capabilities corresponding to the player's HLS/TS h264+aac fallback. */
object PlaybackDeviceProfile {
    private val externalTextSubtitleFormats = listOf(
        "srt",
        "subrip",
        "vtt",
        "webvtt",
        "ttml",
        "dfxp",
        "ass",
        "ssa"
    )
    private val encodedImageSubtitleFormats = listOf("pgssub", "dvdsub", "dvbsub")

    fun build(maxBitrate: Int): JSONObject = JSONObject().apply {
        require(maxBitrate > 0) { "maxBitrate must be positive" }
        put("MaxStreamingBitrate", maxBitrate)
        put("MaxVideoBitrate", maxBitrate)
        put("MusicStreamingTranscodingBitrate", 320_000)
        put("DirectPlayProfiles", JSONArray().apply {
            put(JSONObject().apply {
                put("Container", "mp4,m4v,mov,mkv")
                put("Type", "Video")
            })
            put(JSONObject().apply {
                put("Container", "mp3,aac,flac")
                put("Type", "Audio")
            })
        })
        put("CodecProfiles", JSONArray().apply {
            put(JSONObject().apply {
                put("Type", "Video")
                put("Codec", "h264,hevc,vp9")
            })
        })
        put("TranscodingProfiles", JSONArray().apply {
            put(JSONObject().apply {
                put("Container", "ts")
                put("Type", "Video")
                put("Context", "Streaming")
                put("Protocol", "hls")
                put("VideoCodec", "h264")
                put("AudioCodec", "aac")
                put("MaxAudioChannels", "2")
                put("MinSegments", 1)
                put("BreakOnNonKeyFrames", true)
            })
        })
        put("SubtitleProfiles", JSONArray().apply {
            externalTextSubtitleFormats.forEach { format ->
                put(JSONObject().put("Format", format).put("Method", "External"))
            }
            encodedImageSubtitleFormats.forEach { format ->
                put(JSONObject().put("Format", format).put("Method", "Encode"))
            }
        })
    }
}
