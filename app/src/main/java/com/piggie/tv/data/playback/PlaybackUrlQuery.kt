package com.piggie.tv.data.playback

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Deterministically sets one playback URL query parameter.
 *
 * Jellyfin may return either an absolute URL or a relative path. Absolute HTTP(S) URLs are
 * rebuilt with [HttpUrl], while relative paths retain their original path, unrelated encoded
 * query components, and fragment. Existing parameter names are matched case-insensitively and
 * duplicate matches are collapsed into the requested value.
 */
object PlaybackUrlQuery {
    private val encodingBase = "https://playback.invalid/".toHttpUrl()

    fun set(url: String, name: String, value: String): String {
        val absoluteUrl = url.toHttpUrlOrNull()
        return if (absoluteUrl != null) {
            setAbsolute(absoluteUrl, name, value)
        } else {
            setRelative(url, name, value)
        }
    }

    private fun setAbsolute(url: HttpUrl, name: String, value: String): String {
        val parameters = (0 until url.querySize).map { index ->
            QueryParameter(
                name = url.queryParameterName(index),
                value = url.queryParameterValue(index)
            )
        }
        val builder = url.newBuilder().query(null)
        var replaced = false

        parameters.forEach { parameter ->
            if (parameter.name.equals(name, ignoreCase = true)) {
                if (!replaced) {
                    builder.addQueryParameter(name, value)
                    replaced = true
                }
            } else {
                builder.addQueryParameter(parameter.name, parameter.value)
            }
        }

        if (!replaced) {
            builder.addQueryParameter(name, value)
        }
        return builder.build().toString()
    }

    private fun setRelative(url: String, name: String, value: String): String {
        val fragmentStart = url.indexOf('#')
        val fragment = if (fragmentStart >= 0) url.substring(fragmentStart) else ""
        val withoutFragment = if (fragmentStart >= 0) url.substring(0, fragmentStart) else url
        val queryStart = withoutFragment.indexOf('?')
        val path = if (queryStart >= 0) withoutFragment.substring(0, queryStart) else withoutFragment
        val query = if (queryStart >= 0) withoutFragment.substring(queryStart + 1) else ""
        val encodedReplacement = encodeParameter(name, value)

        if (query.isEmpty()) {
            return "$path?$encodedReplacement$fragment"
        }

        var replaced = false
        val rewritten = splitQuery(query).mapNotNull { component ->
            val encodedName = component.substringBefore('=')
            if (decodeName(encodedName).equals(name, ignoreCase = true)) {
                if (!replaced) {
                    replaced = true
                    encodedReplacement
                } else {
                    null
                }
            } else {
                component
            }
        }.toMutableList()

        if (!replaced) {
            rewritten += encodedReplacement
        }
        return "$path?${rewritten.joinToString("&")}$fragment"
    }

    private fun encodeParameter(name: String, value: String): String =
        requireNotNull(
            encodingBase.newBuilder()
                .query(null)
                .addQueryParameter(name, value)
                .build()
                .encodedQuery
        )

    private fun decodeName(encodedName: String): String {
        val parsed = encodingBase.newBuilder()
            .encodedQuery("$encodedName=")
            .build()
        return parsed.queryParameterName(0)
    }

    private fun splitQuery(query: String): List<String> {
        val components = mutableListOf<String>()
        var start = 0
        query.forEachIndexed { index, character ->
            if (character == '&') {
                components += query.substring(start, index)
                start = index + 1
            }
        }
        components += query.substring(start)
        return components
    }

    private data class QueryParameter(
        val name: String,
        val value: String?
    )
}
