package com.piggie.tv.util

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CrashReporterTest {
    @Test
    fun crashTextIsRedactedBeforePersistence() {
        val sanitized = CrashReporter.sanitize(
            "Authentication failed token=private-token password=hunter2 " +
                "Pw=short-password Code=quick-code Authorization: Bearer bearer-token " +
                "at https://user:pass@example.test/path?api_key=url-secret"
        ).orEmpty()

        listOf(
            "private-token",
            "hunter2",
            "short-password",
            "quick-code",
            "bearer-token",
            "user:pass",
            "example.test",
            "url-secret",
        ).forEach {
            assertFalse(sanitized.contains(it))
        }
        assertTrue(sanitized.contains("token=[REDACTED]"))
        assertTrue(sanitized.contains("password=[REDACTED]"))
    }

    @Test
    fun preHardeningCrashAndDiagnosticExportsArePurged() {
        val context = RuntimeEnvironment.getApplication()
        val crashFile = File(context.filesDir, "ptv_crash_reports.json")
        val diagnostics = File(context.filesDir, "diagnostics").apply { mkdirs() }
        val jsonExport = File(diagnostics, "ptv-diagnostics.json")
        val textExport = File(diagnostics, "ptv-diagnostics.txt")
        crashFile.writeText("{\"crashes\":[{\"message\":\"token=legacy-value\"}]}")
        jsonExport.writeText("token=legacy-value")
        textExport.writeText("Authorization: Bearer legacy-value")

        CrashReporter.purgeLegacySensitiveFiles(context)

        assertFalse(crashFile.exists())
        assertFalse(jsonExport.exists())
        assertFalse(textExport.exists())
        assertNull(CrashReporter.getReports(context))
    }
}
