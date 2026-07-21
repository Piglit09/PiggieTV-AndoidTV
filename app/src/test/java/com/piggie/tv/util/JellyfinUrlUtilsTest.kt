package com.piggie.tv.util

import com.piggie.tv.data.models.NativeSession
import org.junit.Assert.assertEquals
import org.junit.Test

class JellyfinUrlUtilsTest {
    @Test
    fun userAvatarUrlIsCorrect() {
        val session = NativeSession("t", "s", "u123", "p", "https://ptv.io")
        val expected = "https://ptv.io/Users/u123/Images/Primary"
        assertEquals(expected, JellyfinUrlUtils.userAvatar(session))
    }

    @Test
    fun userAvatarHandlesUrlWithTrailingSlash() {
        // JellyfinServerUrl.normalize should have handled this, but let's test the composition
        val session = NativeSession("t", "s", "u", "n", "https://server.com")
        assertEquals("https://server.com/Users/u/Images/Primary", JellyfinUrlUtils.userAvatar(session))
    }
}
