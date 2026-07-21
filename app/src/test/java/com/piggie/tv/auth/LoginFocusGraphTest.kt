package com.piggie.tv.auth

import org.junit.Assert.assertEquals
import org.junit.Test

enum class LoginFocusTarget { SERVER, USERNAME, PASSWORD, CONNECT, QUICK_CONNECT }

object LoginFocusGraph {
    fun next(current: LoginFocusTarget): LoginFocusTarget? = when (current) {
        LoginFocusTarget.SERVER -> LoginFocusTarget.USERNAME
        LoginFocusTarget.USERNAME -> LoginFocusTarget.PASSWORD
        LoginFocusTarget.PASSWORD -> LoginFocusTarget.CONNECT
        LoginFocusTarget.CONNECT -> LoginFocusTarget.QUICK_CONNECT
        LoginFocusTarget.QUICK_CONNECT -> null
    }
}

class LoginFocusGraphTest {
    @Test
    fun testFocusFlow() {
        assertEquals(LoginFocusTarget.USERNAME, LoginFocusGraph.next(LoginFocusTarget.SERVER))
        assertEquals(LoginFocusTarget.PASSWORD, LoginFocusGraph.next(LoginFocusTarget.USERNAME))
        assertEquals(LoginFocusTarget.CONNECT, LoginFocusGraph.next(LoginFocusTarget.PASSWORD))
        assertEquals(LoginFocusTarget.QUICK_CONNECT, LoginFocusGraph.next(LoginFocusTarget.CONNECT))
        assertEquals(null, LoginFocusGraph.next(LoginFocusTarget.QUICK_CONNECT))
    }
}
