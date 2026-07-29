package com.piggie.tv.data.session

import com.piggie.tv.data.models.NativeSession
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReadingSettingsTest {

    @Test
    fun testProgressScopingByServerAndUser() {
        val context = RuntimeEnvironment.getApplication()
        val settings = ReadingSettings(context)

        val session1 = NativeSession("t1", "s1", "u1", "n", "url")
        val session2 = NativeSession("t2", "s2", "u2", "n", "url")

        settings.setLastPage(session1, "item1", 10)
        settings.setLastPage(session2, "item1", 20)

        assertEquals(10, settings.getLastPage(session1, "item1"))
        assertEquals(20, settings.getLastPage(session2, "item1"))
    }

    @Test
    fun testRtlPreferenceScoping() {
        val context = RuntimeEnvironment.getApplication()
        val settings = ReadingSettings(context)

        val session1 = NativeSession("t1", "s1", "u1", "n", "url")

        settings.setRtl(session1, "book1", true)
        assertEquals(true, settings.isRtl(session1, "book1"))
        assertEquals(false, settings.isRtl(session1, "book2"))
    }

    @Test
    fun fitPageIsDefaultAndPersists() {
        val context = RuntimeEnvironment.getApplication()
        val settings = ReadingSettings(context)
        val session = NativeSession("t", "fit-server", "fit-user", "n", "url")

        assertEquals("FIT_PAGE", settings.getFitMode(session, "book"))
        settings.setFitMode(session, "book", "FIT_WIDTH")
        assertEquals("FIT_WIDTH", settings.getFitMode(session, "book"))
    }
}
