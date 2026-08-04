package com.piggie.tv.data.playback

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Builds Jellyfin's universal audio endpoint without putting the access token in the URL.
 *
 * The advertised container/codec pairs are the formats this app can direct play. Jellyfin may
 * serve compatible items from their original stream and transcodes everything else to AAC over
 * HTTP, which remains a progressive source that Media3 can sniff without a file extension.
 */
object JellyfinUniversalAudioUrl {
    const val DEFAULT_MAX_STREAMING_BITRATE = 100_000_000
    const val DEFAULT_TRANSCODING_BITRATE = 320_000

    private const val DIRECT_PLAY_PROFILES =
        "mp3|mp3,aac|aac,m4a|aac,mp4|aac,flac|flac"

    fun build(
        serverUrl: String,
        itemId: String,
        userId: String,
        deviceId: String,
        mediaSourceId: String? = null,
        maxStreamingBitrate: Int = DEFAULT_MAX_STREAMING_BITRATE,
        startTimeTicks: Long = 0L
    ): String {
        require(itemId.isNotBlank()) { "itemId must not be blank" }
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(maxStreamingBitrate > 0) { "maxStreamingBitrate must be positive" }

        val base = requireNotNull(serverUrl.trimEnd('/').toHttpUrlOrNull()) {
            "Invalid Jellyfin server URL"
        }
        return base.newBuilder()
            .addPathSegment("Audio")
            .addPathSegment(itemId)
            .addPathSegment("universal")
            .addQueryParameter("UserId", userId)
            .addQueryParameter("DeviceId", deviceId)
            .apply {
                mediaSourceId?.takeIf(String::isNotBlank)
                    ?.let { addQueryParameter("MediaSourceId", it) }
            }
            .addQueryParameter("MaxStreamingBitrate", maxStreamingBitrate.toString())
            .addQueryParameter("Container", DIRECT_PLAY_PROFILES)
            .addQueryParameter("TranscodingContainer", "aac")
            .addQueryParameter("TranscodingProtocol", "http")
            .addQueryParameter("AudioCodec", "aac")
            .addQueryParameter("AudioBitrate", DEFAULT_TRANSCODING_BITRATE.toString())
            .addQueryParameter("MaxAudioChannels", "2")
            .addQueryParameter("TranscodingAudioChannels", "2")
            .addQueryParameter("MaxAudioSampleRate", "48000")
            .addQueryParameter("StartTimeTicks", startTimeTicks.coerceAtLeast(0L).toString())
            .addQueryParameter("EnableRedirection", "true")
            .addQueryParameter("EnableRemoteMedia", "false")
            .build()
            .toString()
    }
}
