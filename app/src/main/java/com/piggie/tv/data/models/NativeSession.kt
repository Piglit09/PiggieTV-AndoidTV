package com.piggie.tv.data.models

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class NativeSession(
    val token: String,
    val serverId: String,
    val userId: String,
    val userName: String,
    val serverUrl: String,
    val isAdministrator: Boolean = false
) {
    fun isComplete(): Boolean {
        val server = serverUrl.toHttpUrlOrNull()
        return token.isNotBlank() &&
            serverId.isNotBlank() &&
            userId.isNotBlank() &&
            userName.isNotBlank() &&
            server != null &&
            (server.isHttps || server.scheme == "http") &&
            server.username.isEmpty() &&
            server.password.isEmpty() &&
            server.query == null &&
            server.fragment == null
    }

    /** Prevent generated data-class output from disclosing the access token or user identity. */
    override fun toString(): String =
        "NativeSession(token=[REDACTED], identity=[REDACTED], isAdministrator=$isAdministrator)"
}
