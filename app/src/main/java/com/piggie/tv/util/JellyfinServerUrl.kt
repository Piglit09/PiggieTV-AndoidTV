package com.piggie.tv.util

object JellyfinServerUrl {
    fun normalize(input: String): String {
        var url = input.trim().lowercase()
        if (url.endsWith("/")) url = url.dropLast(1)
        if (!url.startsWith("http")) url = "https://$url"
        return url
    }
}
