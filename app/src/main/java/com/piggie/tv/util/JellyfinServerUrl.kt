package com.piggie.tv.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object JellyfinServerUrl {
    fun normalize(input: String): String {
        val value = input.trim()
        if (value.isEmpty()) return ""
        // Secure by default. Explicit http:// remains available for LAN servers.
        val candidate = if (
            value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            value
        } else {
            "https://$value"
        }
        // HttpUrl canonicalizes the scheme and host without corrupting a case-sensitive reverse
        // proxy base path. Credentials, query parameters, and fragments are not valid parts of a
        // Jellyfin server base URL: accepting them could persist secrets in the non-sensitive
        // last-server preference or redirect authenticated requests unexpectedly.
        val parsed = candidate.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Enter a valid Jellyfin server URL")
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
            "Server URLs cannot contain credentials"
        }
        require(parsed.query == null && parsed.fragment == null) {
            "Server URLs cannot contain query parameters or fragments"
        }
        return parsed.toString().trimEnd('/')
    }
}
