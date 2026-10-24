package com.piggie.tv.fixture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureRapidInputProviderTest {
    @Test fun sequenceIsFixedAndActuallyOverlapsHeroMotion() {
        assertEquals(listOf("DOWN", "UP", "DOWN", "UP"), FixtureRapidInputProvider.sequenceLabels())
        assertTrue(FixtureRapidInputProvider.INTER_KEY_DELAY_MS < 250)
    }
}
