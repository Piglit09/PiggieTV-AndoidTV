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
        // proxy base path. Keep a best-effort fallback so validation can surface malformed input.
        return candidate.toHttpUrlOrNull()
            ?.newBuilder()
            ?.fragment(null)
            ?.build()
            ?.toString()
            ?.trimEnd('/')
            ?: candidate.trimEnd('/')
    }
}
