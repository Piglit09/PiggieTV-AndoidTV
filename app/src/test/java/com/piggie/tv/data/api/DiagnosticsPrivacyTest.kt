package com.piggie.tv.data.api

import com.piggie.tv.diagnostics.PtvRedactor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsPrivacyTest {
    @Test
    fun sensitiveIdentifiersAreMasked() {
        val server = PtvRedactor.identifier("server123456789").orEmpty()
        val user = PtvRedactor.identifier("user123456789").orEmpty()
        assertFalse(server.contains("server123456789"))
        assertFalse(user.contains("user123456789"))
        assertTrue(server.startsWith("[ID:"))
        assertTrue(user.startsWith("[ID:"))
    }

    @Test
    fun shortIdentifiersAreFullyMasked() {
        assertTrue(PtvRedactor.identifier("s") == "[ID]")
        assertTrue(PtvRedactor.identifier("u") == "[ID]")
    }
}
