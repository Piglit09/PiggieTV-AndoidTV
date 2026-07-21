package com.piggie.tv.data.api

import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinAuthorizationHeaderTest {
    @Test
    fun headerContainsRequiredFields() {
        val header = JellyfinAuthorizationHeader.build("device1", "v1.0", "token123")
        assertTrue(header.contains("DeviceId=\"device1\""))
        assertTrue(header.contains("Version=\"v1.0\""))
        assertTrue(header.contains("Token=\"token123\""))
        assertTrue(header.contains("PiggieTV Native"))
    }

    @Test
    fun headerWithoutTokenOmitsTokenField() {
        val header = JellyfinAuthorizationHeader.build("device1", "v1.0", null)
        assertTrue(header.contains("DeviceId=\"device1\""))
        assertTrue(!header.contains("Token="))
    }
}
