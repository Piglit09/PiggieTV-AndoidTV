package com.piggie.tv.ui.shared

import androidx.core.text.HtmlCompat

object TextSanitizer {
    /**
     * Sanitizes Jellyfin metadata text by converting HTML breaks to newlines,
     * decoding entities, and stripping excessive whitespace.
     */
    fun sanitize(input: String?): String {
        if (input.isNullOrBlank()) return ""
        
        val marker = "___PIGGIE_BR___"
        var processed = input
            .replace(Regex("(?i)<br\\s*/?>"), marker)
            .replace(Regex("(?i)</p>"), marker + marker)
        
        processed = HtmlCompat.fromHtml(processed, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        
        return processed
            .replace(marker, "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Formats metadata items into a dot-separated string, filtering out nulls/blanks.
     */
    fun formatMetadata(vararg parts: Any?): String {
        return parts.asSequence()
            .map { it?.toString() }
            .filterNot { it.isNullOrBlank() }
            .joinToString("  •  ")
    }
}
