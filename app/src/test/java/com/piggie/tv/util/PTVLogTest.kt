package com.piggie.tv.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PTVLogTest {

    @Test
    fun testUrlRedaction() {
        val url = "https://server.com/Items/123/stream?api_key=token123&Static=true"
        val redacted = PTVLog.redactUrl(url)
        assertEquals("https://server.com/Items/123/stream", redacted)
    }

    @Test
    fun testUrlRedactionWithoutParams() {
        val url = "https://server.com/System/Info"
        val redacted = PTVLog.redactUrl(url)
        assertEquals("https://server.com/System/Info", redacted)
    }
}
