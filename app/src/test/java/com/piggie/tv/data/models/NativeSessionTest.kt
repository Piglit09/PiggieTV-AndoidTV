package com.piggie.tv.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSessionTest {
    @Test
    fun completeSessionRequiresValidHttpServerContext() {
        val session = NativeSession("token", "server", "user", "Piggie", "https://piggietv.com")
        assertTrue(session.isComplete())
        assertFalse(session.copy(token = "").isComplete())
        assertTrue(session.copy(serverUrl = "http://10.16.0.50:8096").isComplete())
    }

    @Test
    fun testSessionEquality() {
        val s1 = NativeSession("t", "s", "u", "n", "url")
        val s2 = NativeSession("t", "s", "u", "n", "url")
        assertEquals(s1, s2)
        assertEquals(s1.hashCode(), s2.hashCode())
    }

    @Test
    fun testIncompleteSessions() {
        assertFalse(NativeSession("", "s", "u", "n", "url").isComplete())
        assertFalse(NativeSession("t", "", "u", "n", "url").isComplete())
        assertFalse(NativeSession("t", "s", "", "n", "url").isComplete())
    }

    @Test
    fun testSessionPropertiesAccess() {
        val session = NativeSession("t", "s", "u", "n", "url")
        assertEquals("t", session.token)
        assertEquals("s", session.serverId)
    }

    @Test
    fun testServerUrlProtocolCheck() {
        assertTrue(NativeSession("t", "s", "u", "n", "http://10.0.0.2:8096").isComplete())
        assertFalse(NativeSession("t", "s", "u", "n", "ftp://insecure.example").isComplete())
        assertFalse(NativeSession("t", "s", "u", "n", "not a url").isComplete())
    }
}
