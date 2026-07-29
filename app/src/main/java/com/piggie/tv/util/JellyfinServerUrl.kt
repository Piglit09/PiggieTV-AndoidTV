package com.piggie.tv.util

object JellyfinServerUrl {
    fun normalize(input: String): String {
        var url = input.trim().lowercase()
        if (url.isEmpty()) return ""
        if (url.endsWith("/")) url = url.dropLast(1)
        // Secure by default. Explicit http:// remains available for LAN servers.
        if (!url.startsWith("http")) url = "https://$url"
        return url
    }
}
