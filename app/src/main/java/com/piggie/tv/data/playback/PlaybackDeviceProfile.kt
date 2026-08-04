package com.piggie.tv.data.playback

import android.media.MediaCodecList
import org.json.JSONArray
import org.json.JSONObject

internal data class PlaybackCodecCapabilities(
    val videoCodecs: Set<String>,
    val audioCodecs: Set<String>
)

/** Maps the decoders Media3 can actually use on this TV to Jellyfin codec names. */
internal object PlaybackCodecCapabilityDetector {
    private val detected by lazy {
        runCatching {
            val mimeTypes = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .asSequence()
                .filterNot { it.isEncoder }
                .flatMap { it.supportedTypes.asSequence() }
                .toSet()
            fromMimeTypes(mimeTypes)
        }.getOrElse {
            // AVC/AAC are mandatory baseline Android TV media capabilities.
            PlaybackCodecCapabilities(setOf("h264"), setOf("aac", "mp3", "flac"))
        }
    }

    fun detect(): PlaybackCodecCapabilities = detected

    fun fromMimeTypes(mimeTypes: Iterable<String>): PlaybackCodecCapabilities {
        val video = linkedSetOf<String>()
        val audio = linkedSetOf<String>()
        mimeTypes.forEach { mimeType ->
            when (mimeType.lowercase()) {
                "video/avc" -> video += "h264"
                "video/hevc" -> video += "hevc"
                "video/x-vnd.on2.vp9" -> video += "vp9"
                "video/x-vnd.on2.vp8" -> video += "vp8"
                "video/av01" -> video += "av1"
                "video/mpeg2" -> video += "mpeg2video"
                "video/mp4v-es" -> video += "mpeg4"
                "audio/mp4a-latm" -> audio += listOf("aac", "aac_latm")
                "audio/mpeg" -> audio += "mp3"
                "audio/flac" -> audio += "flac"
                "audio/opus" -> audio += "opus"
                "audio/vorbis" -> audio += "vorbis"
                "audio/ac3" -> audio += "ac3"
                "audio/eac3", "audio/eac3-joc" -> audio += "eac3"
                "audio/alac" -> audio += "alac"
                "audio/vnd.dts", "audio/vnd.dts.hd" -> audio += "dts"
                "audio/true-hd" -> audio += "truehd"
            }
        }
        return PlaybackCodecCapabilities(video, audio)
    }
}

/** Jellyfin capabilities corresponding to the detected decoders and HLS/AVC fallback. */
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

    fun build(maxBitrate: Int): JSONObject =
        build(maxBitrate, PlaybackCodecCapabilityDetector.detect())

    internal fun build(
        maxBitrate: Int,
        capabilities: PlaybackCodecCapabilities
    ): JSONObject = JSONObject().apply {
        require(maxBitrate > 0) { "maxBitrate must be positive" }
        val videoCodecs = capabilities.videoCodecs.ifEmpty { setOf("h264") }.sorted()
        val audioCodecs = capabilities.audioCodecs.ifEmpty { setOf("aac") }.sorted()
        put("MaxStreamingBitrate", maxBitrate)
        put("MaxVideoBitrate", maxBitrate)
        put("MusicStreamingTranscodingBitrate", 320_000)
        put("DirectPlayProfiles", JSONArray().apply {
            put(JSONObject().apply {
                put("Container", "mp4,m4v,mov,mkv")
                put("Type", "Video")
                put("VideoCodec", videoCodecs.joinToString(","))
                put("AudioCodec", audioCodecs.joinToString(","))
            })
            audioDirectPlayProfiles(audioCodecs.toSet()).forEach(::put)
        })
        put("CodecProfiles", JSONArray().apply {
            put(JSONObject().apply {
                put("Type", "Video")
                put("Codec", videoCodecs.joinToString(","))
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

    private fun audioDirectPlayProfiles(audioCodecs: Set<String>): List<JSONObject> = buildList {
        fun addProfile(container: String, codec: String? = null) {
            add(JSONObject().apply {
                put("Container", container)
                put("Type", "Audio")
                codec?.let { put("AudioCodec", it) }
            })
        }

        if ("mp3" in audioCodecs) addProfile("mp3")
        if ("aac" in audioCodecs || "aac_latm" in audioCodecs) {
            addProfile("aac")
            addProfile("m4a,m4b", "aac,aac_latm")
        }
        if ("flac" in audioCodecs) addProfile("flac")
        if ("opus" in audioCodecs) {
            addProfile("opus")
            addProfile("webm,ogg", "opus")
        }
        if ("vorbis" in audioCodecs) addProfile("ogg,webm", "vorbis")
        if ("alac" in audioCodecs) addProfile("m4a", "alac")
        if ("ac3" in audioCodecs) addProfile("ac3")
        if ("eac3" in audioCodecs) addProfile("eac3")
    }
}
