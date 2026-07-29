package com.piggie.tv.data.playback

import android.content.Context
import com.piggie.tv.data.session.NativeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ConnectionSpeedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("ptv_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `all requested speed caps map to bits per second`() {
        assertNull(ConnectionSpeed.AUTO.maxBitrate)
        assertNull(ConnectionSpeed.ORIGINAL.maxBitrate)
        assertEquals(100_000_000, ConnectionSpeed.MBPS_100.maxBitrate)
        assertEquals(60_000_000, ConnectionSpeed.MBPS_60.maxBitrate)
        assertEquals(40_000_000, ConnectionSpeed.MBPS_40.maxBitrate)
        assertEquals(20_000_000, ConnectionSpeed.MBPS_20.maxBitrate)
        assertEquals(10_000_000, ConnectionSpeed.MBPS_10.maxBitrate)
        assertEquals(5_000_000, ConnectionSpeed.MBPS_5.maxBitrate)
        assertEquals(3_000_000, ConnectionSpeed.MBPS_3.maxBitrate)
    }

    @Test
    fun `auto and original do not force a bitrate transcode`() {
        assertFalse(ConnectionSpeed.AUTO.shouldCap(250_000_000))
        assertFalse(ConnectionSpeed.ORIGINAL.shouldCap(250_000_000))
        assertTrue(ConnectionSpeed.MBPS_20.shouldCap(25_000_000))
    }

    @Test
    fun `legacy resolution preferences migrate without being discarded`() {
        context.getSharedPreferences("ptv_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("playback_quality", "720p (4 Mbps)")
            .commit()

        val settings = NativeSettings(context)

        assertEquals("5 Mbps", settings.connectionSpeed)
        assertEquals(
            "5 Mbps",
            context.getSharedPreferences("ptv_settings", Context.MODE_PRIVATE)
                .getString("connection_speed", null)
        )
    }
}
