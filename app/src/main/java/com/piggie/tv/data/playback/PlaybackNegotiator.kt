package com.piggie.tv.data.playback

import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.NativeSettings
import org.json.JSONArray
import org.json.JSONObject

data class PlaybackStream(
    val url: String,
    val method: String,
    val playSessionId: String,
    val mediaSourceId: String
)

class PlaybackNegotiator(
    private val api: JellyfinNativeApi,
    private val settings: NativeSettings
) {

    fun getPlaybackStream(session: NativeSession, itemId: String, positionTicks: Long): PlaybackStream {
        val profile = buildDeviceProfile()
        
        val maxBitrate = when (settings.playbackQuality) {
            "4K (120 Mbps)" -> 120_000_000
            "1080p (20 Mbps)" -> 20_000_000
            "720p (4 Mbps)" -> 4_000_000
            else -> 100_000_000 // Default
        }
        profile.put("MaxStreamingBitrate", maxBitrate)

        val info = JSONObject(api.requestPlaybackInfo(session, itemId, profile))
        
        val sources = info.optJSONArray("MediaSources") ?: JSONArray()
        val source = sources.optJSONObject(0) ?: throw IllegalStateException("No playable media sources found")
        val sourceId = source.optString("Id")
        val playSessionId = info.optString("PlaySessionId")

        val canDirectPlay = source.optBoolean("SupportsDirectPlay", false)
        val forceTranscode = settings.playbackQuality.contains("Mbps") && 
                             source.optLong("Bitrate", 0L) > maxBitrate

        if (canDirectPlay && !forceTranscode) {
            val url = session.serverUrl + "/Videos/" + itemId + "/stream?Static=true&MediaSourceId=" + sourceId + "&api_key=" + session.token
            return PlaybackStream(url, "DirectPlay", playSessionId, sourceId)
        }

        val url = session.serverUrl + "/Videos/" + itemId + "/master.m3u8" +
            "?MediaSourceId=" + sourceId + 
            "&PlaySessionId=" + playSessionId + 
            "&VideoCodec=h264" +
            "&AudioCodec=aac" +
            "&AudioChannels=2" +
            "&MaxVideoBitrate=$maxBitrate" +
            "&TranscodingMaxAudioChannels=2" +
            "&SegmentContainer=ts" +
            "&BreakOnNonKeyFrames=true" +
            "&api_key=" + session.token
        
        return PlaybackStream(url, "Transcode", playSessionId, sourceId)
    }

    private fun buildDeviceProfile() = JSONObject().apply {
        put("MaxStreamingBitrate", 100_000_000)
        put("MusicStreamingTranscodingBitrate", 320000)
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
    }
}
