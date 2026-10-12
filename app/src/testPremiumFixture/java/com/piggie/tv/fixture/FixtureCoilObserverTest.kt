package com.piggie.tv.fixture

import coil.EventListener
import coil.request.ImageRequest
import com.piggie.tv.diagnostics.PtvCoilEventListenerFactory
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FixtureCoilObserverTest {
    @Test fun heroObserverWorksWhenNormalDiagnosticsAreDisabled() {
        PtvDiagnosticsManager.setEnabled(RuntimeEnvironment.getApplication(), false)
        assertFalse(PtvDiagnosticsManager.isEnabled())
        val events = mutableListOf<Pair<String, String>>()
        val request = ImageRequest.Builder(RuntimeEnvironment.getApplication())
            .data("fixture-only-hero")
            .setParameter(PtvCoilEventListenerFactory.CATEGORY_PARAMETER,
                          PtvCoilEventListenerFactory.CATEGORY_HERO, null)
            .build()
        PtvCoilEventListenerFactory.fixtureObserver = { category, event ->
            events += category to event
        }
        try {
            val listener = PtvCoilEventListenerFactory.create(request)
            assertNotSame(EventListener.NONE, listener)
            listener.onStart(request)
            assertTrue(events.contains("hero" to "request_start"))
        } finally {
            PtvCoilEventListenerFactory.fixtureObserver = null
        }
    }
}
