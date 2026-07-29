package com.piggie.tv.data.playback

import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.NativeSettings
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

data class PlaybackStream(
    val url: String,
    val method: String,
    val playSessionId: String,
    val mediaSourceId: String,
    val container: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val connectionSpeed: String = ConnectionSpeed.AUTO.wireValue,
    val negotiatedMaxBitrate: Int = ConnectionSpeed.AUTO_NEGOTIATION_BITRATE
)

enum class PlaybackRoute {
    DIRECT_PLAY,
    DIRECT_STREAM,
    TRANSCODE
}

object PlaybackRoutePolicy {
    fun choose(
        supportsDirectPlay: Boolean,
        supportsDirectStream: Boolean,
        hasExplicitAudioSelection: Boolean,
        hasExplicitSubtitleSelection: Boolean,
        bitrateCapExceeded: Boolean
    ): PlaybackRoute = when {
        supportsDirectPlay &&
            !hasExplicitAudioSelection &&
            !hasExplicitSubtitleSelection &&
            !bitrateCapExceeded ->
            PlaybackRoute.DIRECT_PLAY
        supportsDirectStream &&
            !hasExplicitSubtitleSelection &&
            !bitrateCapExceeded ->
            PlaybackRoute.DIRECT_STREAM
        else -> PlaybackRoute.TRANSCODE
    }
}

object PlaybackSubtitlePolicy {
    fun applyServerEncoding(url: String, subtitleIndex: Int?): String {
        if (subtitleIndex == null) return url
        return appendQuery(
            appendQuery(url, "SubtitleStreamIndex", subtitleIndex.toString()),
            "SubtitleMethod",
            "Encode"
        )
    }

    private fun appendQuery(url: String, key: String, value: String): String {
        if (Regex("([?&])${Regex.escape(key)}=").containsMatchIn(url)) return url
        return url + if ('?' in url) "&$key=$value" else "?$key=$value"
    }
}

/**
 * Media URLs and credential attachment are constrained to the authenticated Jellyfin origin.
 *
 * PlaybackInfo normally returns relative paths. Rejecting an unexpected foreign absolute URL
 * prevents a malformed or compromised response from turning the media client into a credential
 * forwarding proxy. Comparing parsed scheme/host/effective-port also avoids prefix tricks such as
 * `https://trusted.example.attacker.invalid`.
 */
object PlaybackOriginPolicy {
    fun resolve(serverUrl: String, value: String): String {
        val candidate = if (
            value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            value
        } else {
            serverUrl.trimEnd('/') + "/" + value.trimStart('/')
        }
        require(shouldAttachCredentials(serverUrl, candidate)) {
            "Playback URL origin does not match the Jellyfin server"
        }
        return requireNotNull(candidate.toHttpUrlOrNull()) { "Invalid playback URL" }.toString()
    }

    fun shouldAttachCredentials(serverUrl: String, requestUrl: String): Boolean {
        val server = serverUrl.toHttpUrlOrNull() ?: return false
        val request = requestUrl.toHttpUrlOrNull() ?: return false
        return server.scheme == request.scheme &&
            server.host.equals(request.host, ignoreCase = true) &&
            server.port == request.port
    }
}

class PlaybackNegotiator(
    private val api: JellyfinNativeApi,
    private val settings: NativeSettings
) {

    fun getPlaybackStream(
        session: NativeSession,
        itemId: String,
        positionTicks: Long,
        audioIndex: Int? = null,
        subtitleIndex: Int? = null
    ): PlaybackStream {
        val profile = buildDeviceProfile()
        
        val connectionSpeed = ConnectionSpeed.fromStored(settings.connectionSpeed)
        val maxBitrate = connectionSpeed.negotiationBitrate()
        profile.put("MaxStreamingBitrate", maxBitrate)
        profile.put("MaxVideoBitrate", maxBitrate)

        val info = JSONObject(
            api.requestPlaybackInfo(
                session,
                itemId,
                profile,
                audioStreamIndex = audioIndex,
                subtitleStreamIndex = subtitleIndex
            )
        )
        
        val sources = info.optJSONArray("MediaSources") ?: JSONArray()
        val source = (0 until sources.length())
            .mapNotNull(sources::optJSONObject)
            .maxByOrNull { candidate ->
                when {
                    candidate.optBoolean("SupportsDirectPlay", false) -> 3
                    candidate.optBoolean("SupportsDirectStream", false) -> 2
                    candidate.optBoolean("SupportsTranscoding", true) -> 1
                    else -> 0
                }
            }
            ?: throw IllegalStateException("No playable media sources found")
        val sourceId = source.optString("Id")
        val playSessionId = info.optString("PlaySessionId")
        val streams = source.optJSONArray("MediaStreams") ?: JSONArray()
        val video = (0 until streams.length()).mapNotNull(streams::optJSONObject).firstOrNull { it.optString("Type") == "Video" }
        val audioStreams = (0 until streams.length()).mapNotNull(streams::optJSONObject)
            .filter { it.optString("Type") == "Audio" }
        val audio = audioStreams.firstOrNull { it.optInt("Index", -1) == audioIndex }
            ?: audioStreams.firstOrNull()
        fun playbackStream(url: String, method: String) = PlaybackStream(
            url = url,
            method = method,
            playSessionId = playSessionId,
            mediaSourceId = sourceId,
            container = source.optString("Container").ifBlank { null },
            videoCodec = video?.optString("Codec")?.ifBlank { null },
            audioCodec = audio?.optString("Codec")?.ifBlank { null },
            width = video?.optInt("Width", 0)?.takeIf { it > 0 },
            height = video?.optInt("Height", 0)?.takeIf { it > 0 },
            bitrate = source.optLong("Bitrate", 0L).takeIf { it > 0 },
            connectionSpeed = connectionSpeed.wireValue,
            negotiatedMaxBitrate = maxBitrate
        )

        val canDirectPlay = source.optBoolean("SupportsDirectPlay", false)
        val canDirectStream = source.optBoolean("SupportsDirectStream", false)
        val sourceBitrate = source.optLong("Bitrate", 0L)
        val forceTranscodeByBitrate = connectionSpeed.shouldCap(sourceBitrate)
        val route = PlaybackRoutePolicy.choose(
            supportsDirectPlay = canDirectPlay,
            supportsDirectStream = canDirectStream,
            hasExplicitAudioSelection = audioIndex != null,
            hasExplicitSubtitleSelection = subtitleIndex != null,
            bitrateCapExceeded = forceTranscodeByBitrate
        )

        if (route == PlaybackRoute.DIRECT_PLAY) {
            val url = session.serverUrl + "/Videos/" + itemId +
                "/stream?Static=true&MediaSourceId=" + sourceId
            return playbackStream(url, "DirectPlay")
        }

        if (route == PlaybackRoute.DIRECT_STREAM) {
            var url = source.optString("DirectStreamUrl").takeIf(String::isNotBlank)
                ?.let { absoluteUrl(session, it) }
                ?: session.serverUrl + "/Videos/" + itemId + "/stream?Static=false&MediaSourceId=" + sourceId
            audioIndex?.let { url = appendQuery(url, "AudioStreamIndex", it.toString()) }
            subtitleIndex?.let { url = appendQuery(url, "SubtitleStreamIndex", it.toString()) }
            return playbackStream(url, "DirectStream")
        }

        var url = source.optString("TranscodingUrl").takeIf(String::isNotBlank)
            ?.let { absoluteUrl(session, it) }
            ?: session.serverUrl + "/Videos/" + itemId + "/master.m3u8" +
            "?MediaSourceId=" + sourceId + 
            "&PlaySessionId=" + playSessionId + 
            "&VideoCodec=h264" +
            "&AudioCodec=aac" +
            "&AudioChannels=2" +
            "&MaxVideoBitrate=$maxBitrate" +
            "&TranscodingMaxAudioChannels=2" +
            "&SegmentContainer=ts" +
            "&BreakOnNonKeyFrames=true"

        url = appendQuery(url, "MaxVideoBitrate", maxBitrate.toString())
        if (audioIndex != null) url = appendQuery(url, "AudioStreamIndex", audioIndex.toString())
        url = PlaybackSubtitlePolicy.applyServerEncoding(url, subtitleIndex)
        
        return playbackStream(url, "Transcode")
    }

    private fun absoluteUrl(session: NativeSession, value: String): String =
        PlaybackOriginPolicy.resolve(session.serverUrl, value)

    private fun appendQuery(url: String, key: String, value: String): String {
        val existing = Regex("([?&])${Regex.escape(key)}=").containsMatchIn(url)
        if (existing) return url
        return url + if ('?' in url) "&$key=$value" else "?$key=$value"
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
