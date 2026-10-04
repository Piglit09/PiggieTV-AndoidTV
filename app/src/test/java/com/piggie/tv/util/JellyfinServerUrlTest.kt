package com.piggie.tv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun normalizeRejectsCredentialsInAuthority() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            JellyfinServerUrl.normalize("https://user:password@media.example.com")
        }

        assertEquals("Server URLs cannot contain credentials", error.message)
    }

    @Test
    fun normalizeRejectsQueryAndFragmentData() {
        assertThrows(IllegalArgumentException::class.java) {
            JellyfinServerUrl.normalize("https://media.example.com?api_key=secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            JellyfinServerUrl.normalize("https://media.example.com/#private")
        }
    }

    @Test
    fun invalidInputDoesNotEchoPotentialSecret() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            JellyfinServerUrl.normalize("not a valid host password=secret")
        }

        assertEquals("Enter a valid Jellyfin server URL", error.message)
    }
}
