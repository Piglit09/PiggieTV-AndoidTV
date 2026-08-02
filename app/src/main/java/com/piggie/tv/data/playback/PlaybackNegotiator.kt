package com.piggie.tv.data.playback

import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.NativeSettings
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
        return PlaybackUrlQuery.set(
            PlaybackUrlQuery.set(url, "SubtitleStreamIndex", subtitleIndex.toString()),
            "SubtitleMethod",
            "Encode"
        )
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
        subtitleIndex: Int? = null,
        preferredMediaSourceId: String? = null
    ): PlaybackStream {
        val connectionSpeed = ConnectionSpeed.fromStored(settings.connectionSpeed)
        val maxBitrate = connectionSpeed.negotiationBitrate()
        val profile = PlaybackDeviceProfile.build(maxBitrate)

        val info = JSONObject(
            api.requestPlaybackInfo(
                session,
                itemId,
                profile,
                // Discover every source for default playback so an unplayable preferred version
                // can fail over. Explicit stream indices remain pinned to their owning source.
                mediaSourceId = preferredMediaSourceId.takeIf {
                    audioIndex != null || subtitleIndex != null
                },
                startTimeTicks = positionTicks,
                audioStreamIndex = audioIndex,
                subtitleStreamIndex = subtitleIndex
            )
        )
        
        val sources = info.optJSONArray("MediaSources")
        val sourceEntries = if (sources == null) {
            emptyList()
        } else {
            (0 until sources.length()).mapNotNull { sourceIndex ->
                val source = sources.optJSONObject(sourceIndex) ?: return@mapNotNull null
                source.toSourceCandidate(sourceIndex)?.let { candidate ->
                    NegotiatedSource(source, candidate)
                }
            }
        }
        val plans = PlaybackMediaSourcePolicy.orderedPlans(
            candidates = sourceEntries.map { it.candidate },
            preferredMediaSourceId = preferredMediaSourceId,
            audioStreamIndex = audioIndex,
            subtitleStreamIndex = subtitleIndex,
            maxAllowedBitrate = connectionSpeed.maxBitrate?.toLong()
        )
        if (plans.isEmpty()) throw IllegalStateException("No playable media sources found")

        val entriesByIndex = sourceEntries.associateBy { it.candidate.sourceIndex }
        var lastFailure: RuntimeException? = null
        plans.forEach { plan ->
            val source = entriesByIndex[plan.source.sourceIndex]?.json ?: return@forEach
            try {
                return buildPlaybackStream(
                    session = session,
                    itemId = itemId,
                    info = info,
                    source = source,
                    plan = plan,
                    connectionSpeed = connectionSpeed,
                    maxBitrate = maxBitrate,
                    audioIndex = audioIndex,
                    subtitleIndex = subtitleIndex
                )
            } catch (failure: RuntimeException) {
                lastFailure = failure
            }
        }
        throw IllegalStateException(
            "No playable media source could be prepared",
            lastFailure
        )
    }

    private fun buildPlaybackStream(
        session: NativeSession,
        itemId: String,
        info: JSONObject,
        source: JSONObject,
        plan: PlaybackMediaSourcePlan,
        connectionSpeed: ConnectionSpeed,
        maxBitrate: Int,
        audioIndex: Int?,
        subtitleIndex: Int?
    ): PlaybackStream {
        val sourceId = plan.source.id
        val playSessionId = info.optString("PlaySessionId")
        val streams = source.optJSONArray("MediaStreams")
        val streamObjects = if (streams == null) {
            emptyList()
        } else {
            (0 until streams.length()).mapNotNull(streams::optJSONObject)
        }
        val video = streamObjects.firstOrNull { it.optString("Type") == "Video" }
        val audioStreams = streamObjects.filter { it.optString("Type") == "Audio" }
        val audio = audioStreams.firstOrNull { it.optInt("Index", -1) == audioIndex }
            ?: audioStreams.firstOrNull { it.optBoolean("IsDefault", false) }
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

        if (plan.route == PlaybackRoute.DIRECT_PLAY) {
            val url = PlaybackUrlQuery.set(
                session.serverUrl + "/Videos/" + itemId + "/stream?Static=true",
                "MediaSourceId",
                sourceId
            )
            return playbackStream(url, "DirectPlay")
        }

        if (plan.route == PlaybackRoute.DIRECT_STREAM) {
            var url = source.optString("DirectStreamUrl").takeIf(String::isNotBlank)
                ?.let { absoluteUrl(session, it) }
                ?: session.serverUrl + "/Videos/" + itemId + "/stream?Static=false"
            url = PlaybackUrlQuery.set(url, "MediaSourceId", sourceId)
            audioIndex?.let { url = PlaybackUrlQuery.set(url, "AudioStreamIndex", it.toString()) }
            subtitleIndex?.let { url = PlaybackUrlQuery.set(url, "SubtitleStreamIndex", it.toString()) }
            return playbackStream(url, "DirectStream")
        }

        var url = source.optString("TranscodingUrl").takeIf(String::isNotBlank)
            ?.let { absoluteUrl(session, it) }
            ?: session.serverUrl + "/Videos/" + itemId + "/master.m3u8"

        url = PlaybackUrlQuery.set(url, "MediaSourceId", sourceId)
        if (playSessionId.isNotBlank()) {
            url = PlaybackUrlQuery.set(url, "PlaySessionId", playSessionId)
        }
        url = PlaybackUrlQuery.set(url, "VideoCodec", "h264")
        url = PlaybackUrlQuery.set(url, "AudioCodec", "aac")
        url = PlaybackUrlQuery.set(url, "AudioChannels", "2")
        url = PlaybackUrlQuery.set(url, "MaxVideoBitrate", maxBitrate.toString())
        url = PlaybackUrlQuery.set(url, "TranscodingMaxAudioChannels", "2")
        url = PlaybackUrlQuery.set(url, "SegmentContainer", "ts")
        url = PlaybackUrlQuery.set(url, "BreakOnNonKeyFrames", "true")
        if (audioIndex != null) url = PlaybackUrlQuery.set(url, "AudioStreamIndex", audioIndex.toString())
        url = PlaybackSubtitlePolicy.applyServerEncoding(url, subtitleIndex)
        
        return playbackStream(url, "Transcode")
    }

    private fun absoluteUrl(session: NativeSession, value: String): String =
        PlaybackOriginPolicy.resolve(session.serverUrl, value)

    private fun JSONObject.toSourceCandidate(sourceIndex: Int): PlaybackMediaSourceCandidate? {
        val sourceId = optString("Id").takeIf(String::isNotBlank) ?: return null
        val streams = optJSONArray("MediaStreams")
        val audioIndices = mutableSetOf<Int>()
        val subtitleIndices = mutableSetOf<Int>()
        if (streams != null) {
            for (streamIndex in 0 until streams.length()) {
                val stream = streams.optJSONObject(streamIndex) ?: continue
                val index = stream.optInt("Index", -1).takeIf { it >= 0 } ?: continue
                when (stream.optString("Type")) {
                    "Audio" -> audioIndices += index
                    "Subtitle" -> subtitleIndices += index
                }
            }
        }
        return PlaybackMediaSourceCandidate(
            id = sourceId,
            sourceIndex = sourceIndex,
            supportsDirectPlay = optBoolean("SupportsDirectPlay", false),
            supportsDirectStream = optBoolean(
                "SupportsDirectStream",
                optString("DirectStreamUrl").isNotBlank()
            ),
            supportsTranscoding = optBoolean(
                "SupportsTranscoding",
                optString("TranscodingUrl").isNotBlank()
            ),
            bitrate = optLong("Bitrate", 0L).takeIf { it > 0L },
            audioStreamIndices = audioIndices,
            subtitleStreamIndices = subtitleIndices
        )
    }

    private data class NegotiatedSource(
        val json: JSONObject,
        val candidate: PlaybackMediaSourceCandidate
    )
}
