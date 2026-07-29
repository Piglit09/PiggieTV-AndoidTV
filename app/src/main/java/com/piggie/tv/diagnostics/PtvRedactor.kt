package com.piggie.tv.diagnostics

import java.net.URI

object PtvRedactor {
    private val assignmentSecret = Regex(
        "(?i)([\\\"']?(?:api[_-]?key|access[_-]?token|token|password|authorization|" +
            "quick[_ -]?connect(?:[_ -]?(?:secret|code))?|secret|server[_-]?id|user[_-]?id)[\\\"']?" +
            "\\s*[:=]\\s*)([\\\"']?[^\\s,;}&]+)"
    )
    private val bearerSecret = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+")
    private val completeUrl = Regex("(?i)https?://[^\\s\\\"'<>]+")
    private val opaqueIdentifier = Regex(
        "(?i)^(?:[a-f0-9]{16,}|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})$"
    )

    fun endpoint(url: String): String = runCatching {
        val uri = URI(url)
        sanitizePath(uri.path.orEmpty())
    }.getOrElse { sanitizePath(url.substringBefore('?').substringBefore('#')) }

    fun text(value: String?): String? = value
        ?.let { completeUrl.replace(it) { match -> endpoint(match.value) } }
        ?.let { assignmentSecret.replace(it) { match -> match.groupValues[1] + "[REDACTED]" } }
        ?.let { bearerSecret.replace(it, "Bearer [REDACTED]") }
        ?.take(MAX_TEXT_LENGTH)

    fun identifier(value: String?): String? {
        value ?: return null
        if (value.length <= 8) return "[ID]"
        return "[ID:${value.take(4)}…${value.takeLast(4)}]"
    }

    fun attributes(values: Map<String, String>): Map<String, String> = values.mapValues { (key, value) ->
        if (isSecretKey(key)) "[REDACTED]" else text(value).orEmpty()
    }

    fun isSecretKey(key: String): Boolean {
        val normalized = key.lowercase().replace("_", "").replace("-", "")
        return normalized.contains("token") || normalized.contains("password") ||
            normalized.contains("apikey") || normalized.contains("authorization") ||
            normalized.contains("quickconnect") || normalized == "secret" ||
            normalized == "serverid" || normalized == "userid" ||
            normalized == "body"
    }

    private fun sanitizePath(path: String): String {
        val normalized = path.substringBefore('?').substringBefore('#')
        if (normalized.isBlank()) return "/"
        return normalized.split('/').joinToString("/") { segment ->
            if (opaqueIdentifier.matches(segment)) "[ID]" else segment
        }.let { if (it.startsWith('/')) it else "/$it" }
    }

    private const val MAX_TEXT_LENGTH = 2_000
}
