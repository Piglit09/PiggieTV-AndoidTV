package com.piggie.tv.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBrowserResultGateTest {
    @Test fun aSortChangeRejectsAnAlreadyQueuedOlderResult() {
        val gate = LibraryBrowserResultGate()
        val recent = gate.start()
        val title = gate.start()

        assertFalse(gate.accepts(recent))
        assertTrue(gate.accepts(title))
        gate.retire()
        assertFalse(gate.accepts(title))
    }
}
