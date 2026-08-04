package com.piggie.tv.util

import org.junit.Assert.assertEquals
import org.junit.Test

class JellyfinServerUrlTest {
    @Test
    fun normalizePrependsHttps() {
        assertEquals("https://piggietv.com", JellyfinServerUrl.normalize("piggietv.com"))
        assertEquals("https://piggietv.com", JellyfinServerUrl.normalize("Piggietv.com"))
    }

    @Test
    fun normalizeRemovesTrailingSlash() {
        assertEquals("https://piggietv.com", JellyfinServerUrl.normalize("https://piggietv.com/"))
        assertEquals("http://10.0.0.1:8096", JellyfinServerUrl.normalize("http://10.0.0.1:8096/"))
    }

    @Test
    fun normalizePreservesExplicitHttp() {
        assertEquals("http://192.168.1.50:8096", JellyfinServerUrl.normalize("http://192.168.1.50:8096"))
    }

    @Test
    fun normalizePreservesCaseSensitiveReverseProxyPath() {
        assertEquals(
            "https://media.example.com/JellyFin",
            JellyfinServerUrl.normalize("HTTPS://Media.Example.COM/JellyFin/")
        )
    }
}
