package com.piggie.tv.ui.player

import android.view.LayoutInflater
import android.os.Looper
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import com.piggie.tv.R
import com.piggie.tv.ui.widgets.PtvSelectionDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class PlayerControlsTest {
    @Test
    fun `audio subtitle and connection speed controls are separate and described`() {
        val context = RuntimeEnvironment.getApplication()
        val controls = LayoutInflater.from(context).inflate(R.layout.tv_player_controls, null)

        val audio = controls.findViewById<ImageButton>(R.id.player_audio_btn)
        val subtitles = controls.findViewById<ImageButton>(R.id.player_subtitles_btn)
        val speed = controls.findViewById<ImageButton>(R.id.player_connection_speed_btn)

        assertNotNull(audio)
        assertNotNull(subtitles)
        assertNotNull(speed)
        assertEquals("Audio", audio.contentDescription)
        assertEquals("Subtitles", subtitles.contentDescription)
        assertEquals("Connection Speed", speed.contentDescription)
        assertEquals(R.id.player_subtitles_btn, audio.nextFocusRightId)
        assertEquals(R.id.player_connection_speed_btn, subtitles.nextFocusRightId)
    }

    @Test
    fun `restart planning preserves current position`() {
        assertEquals(123_450_000L, PlayerRestartPolicy.positionTicks(12_345L))
        assertEquals(0L, PlayerRestartPolicy.positionTicks(-1L))
        assertTrue(PlayerRestartPolicy.positionTicks(1L) > 0L)
    }

    @Test
    fun `dialog timeout chain preserves baseline and rejects stale restoration`() {
        val state = PlayerDialogTimeoutState()
        val firstDialog = state.begin(currentTimeoutMs = 5_000)
        val secondDialog = state.begin(currentTimeoutMs = 10_000)

        assertFalse(state.isCurrent(firstDialog))
        assertNull(state.finish(firstDialog))
        assertEquals(5_000, state.finish(secondDialog))

        val laterDialog = state.begin(currentTimeoutMs = 3_000)
        assertTrue(state.isCurrent(laterDialog))
        assertEquals(3_000, state.finish(laterDialog))
    }

    @Test
    fun `native player dialog restores focus to its opener`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val opener = Button(activity).apply {
            text = "Audio"
            isFocusable = true
        }
        activity.setContentView(opener)
        opener.requestFocus()
        val dialog = PtvSelectionDialog(
            activity,
            "Audio",
            listOf("Default / Auto"),
            selectedIndex = 0,
            restoreFocusTo = opener
        ) {}

        dialog.show()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        dialog.dismiss()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(opener.hasFocus())
        activity.finish()
    }
}
