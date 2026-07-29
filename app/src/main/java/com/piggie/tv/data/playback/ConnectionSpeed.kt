package com.piggie.tv.data.playback

/**
 * User-facing playback policy. A speed is a bitrate ceiling, never a resolution promise.
 */
enum class ConnectionSpeed(
    val wireValue: String,
    val displayLabel: String,
    val maxBitrate: Int?,
    val isOriginal: Boolean = false
) {
    AUTO("Auto", "Auto", null),
    ORIGINAL("Original", "Original / Unlimited", null, isOriginal = true),
    MBPS_100("100 Mbps", "Very Fast — 100 Mbps", 100_000_000),
    MBPS_60("60 Mbps", "Very Fast — 60 Mbps", 60_000_000),
    MBPS_40("40 Mbps", "Fast — 40 Mbps", 40_000_000),
    MBPS_20("20 Mbps", "Standard — 20 Mbps", 20_000_000),
    MBPS_10("10 Mbps", "Limited — 10 Mbps", 10_000_000),
    MBPS_5("5 Mbps", "Slow — 5 Mbps", 5_000_000),
    MBPS_3("3 Mbps", "Very Slow — 3 Mbps", 3_000_000);

    /** Jellyfin device profiles require a concrete integer even for automatic/original modes. */
    fun negotiationBitrate(autoDefault: Int = AUTO_NEGOTIATION_BITRATE): Int = when {
        maxBitrate != null -> maxBitrate
        isOriginal -> Int.MAX_VALUE
        else -> autoDefault
    }

    fun shouldCap(sourceBitrate: Long): Boolean =
        maxBitrate?.let { sourceBitrate > it } == true

    companion object {
        const val AUTO_NEGOTIATION_BITRATE = 100_000_000

        fun fromStored(value: String?): ConnectionSpeed {
            val migrated = migrateLegacy(value)
            return entries.firstOrNull { it.wireValue.equals(migrated, ignoreCase = true) } ?: AUTO
        }

        fun migrateLegacy(value: String?): String = when (value?.trim()) {
            null, "", "Auto" -> AUTO.wireValue
            "Original", "Prefer Direct Play", "Original / Unlimited" -> ORIGINAL.wireValue
            "4K (120 Mbps)", "120 Mbps" -> MBPS_100.wireValue
            "1080p (20 Mbps)" -> MBPS_20.wireValue
            "720p (4 Mbps)", "4 Mbps" -> MBPS_5.wireValue
            "480p (1.5 Mbps)", "1.5 Mbps" -> MBPS_3.wireValue
            else -> entries.firstOrNull {
                it.wireValue.equals(value, ignoreCase = true) ||
                    it.displayLabel.equals(value, ignoreCase = true)
            }?.wireValue ?: AUTO.wireValue
        }
    }
}

