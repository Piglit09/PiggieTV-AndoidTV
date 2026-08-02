package com.piggie.tv.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PtvDiagnosticsReaderStateTest {
    @Test
    fun `reader live field includes one-based page suffix`() {
        val context = RuntimeEnvironment.getApplication()
        try {
            PtvDiagnosticsManager.setCollectionMode(context, DiagnosticsCollectionMode.SUMMARY)

            PtvDiagnosticsManager.recordReader(
                PtvReaderTrace(
                    itemId = "reader-item",
                    event = "page_rendered",
                    page = 2
                )
            )

            assertTrue(
                PtvDiagnosticsManager.overlaySummary().contains(
                    "Reader: page_rendered p3"
                )
            )
        } finally {
            PtvDiagnosticsManager.setCollectionMode(context, DiagnosticsCollectionMode.DISABLED)
        }
    }
}
