package com.piggie.tv.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiagnosticsPrivacyTest {
    @Test
    fun sensitiveIdentifiersAreMasked() {
        val session = com.piggie.tv.data.models.NativeSession("token123456789", "server123456789", "user123456789", "Piggie", "https://piggietv.com")
        val context = RuntimeEnvironment.getApplication()
        val api = JellyfinNativeApi(context)
        val diag = api.diagnostics(session, SessionOrigin.STORED)
        
        // Assert that IDs in diagnostics are masked
        assertFalse("Should not contain full server ID", diag.serverId.contains("server123456789"))
        assertFalse("Should not contain full user ID", diag.userId.contains("user123456789"))
        assertTrue("Should contain ellipsis for long IDs", diag.serverId.contains("..."))
        assertTrue("Should contain ellipsis for long IDs", diag.userId.contains("..."))
    }

    @Test
    fun shortIdentifiersAreFullyMasked() {
        val session = com.piggie.tv.data.models.NativeSession("t", "s", "u", "n", "https://piggietv.com")
        val api = JellyfinNativeApi(RuntimeEnvironment.getApplication())
        val diag = api.diagnostics(session, SessionOrigin.STORED)
        
        assertEquals("****", diag.serverId)
        assertEquals("****", diag.userId)
    }
}
