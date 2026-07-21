package com.piggie.tv.data.models

data class NativeSession(
    val token: String,
    val serverId: String,
    val userId: String,
    val userName: String,
    val serverUrl: String
) {
    fun isComplete(): Boolean =
        token.isNotBlank() &&
            serverId.isNotBlank() &&
            userId.isNotBlank() &&
            userName.isNotBlank() &&
            serverUrl.isNotBlank() &&
            serverUrl.startsWith("https://")
}
