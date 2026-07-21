package com.piggie.tv.data.api

object JellyfinAuthorizationHeader {
    fun build(deviceId: String, appVersion: String, token: String?): String {
        val auth = "MediaBrowser Client=\"PiggieTV Native\", " +
            "Device=\"Android TV\", " +
            "DeviceId=\"$deviceId\", " +
            "Version=\"$appVersion\""
        return if (token.isNullOrBlank()) auth else "$auth, Token=\"$token\""
    }
}
