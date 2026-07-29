package com.piggie.tv.data.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeSettingsAutoplayTest {
    @Test fun autoplayDefaultsOnAndPersistsOff() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("ptv_settings", 0).edit().clear().commit()
        val settings = NativeSettings(context)
        assertTrue(settings.autoplayNextEpisode)
        settings.autoplayNextEpisode = false
        assertFalse(NativeSettings(context).autoplayNextEpisode)
    }
}
