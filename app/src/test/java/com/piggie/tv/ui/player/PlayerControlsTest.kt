package com.piggie.tv.ui.player

import android.view.LayoutInflater
import android.os.Looper
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import com.piggie.tv.R
import com.piggie.tv.data.models.PlaybackSkipSegment
import com.piggie.tv.data.models.PlaybackSkipSegmentType
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

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class PlayerControlsTest {
    @Test
    fun `audio subtitle and connection speed controls are separate and described`() {
        val context = RuntimeEnvironment.getApplication()
        val controls = LayoutInflater.from(context).inflate(R.layout.tv_player_controls, null)

        val audio = controls.findViewById<ImageButton>(R.id.player_audio_btn)
        val subtitles = controls.findViewById<ImageButton>(R.id.player_subtitles_btn)
        val speed = controls.findViewById<ImageButton>(R.id.player_connection_speed_btn)
        val previous = controls.findViewById<ImageButton>(R.id.player_previous_episode_btn)
        val restart = controls.findViewById<ImageButton>(R.id.player_restart_episode_btn)
        val next = controls.findViewById<ImageButton>(R.id.player_next_episode_btn)

        assertNotNull(audio)
        assertNotNull(subtitles)
        assertNotNull(speed)
        assertNotNull(previous)
        assertNotNull(restart)
        assertNotNull(next)
        assertEquals("Previous Episode", previous.contentDescription)
        assertEquals("Restart Episode", restart.contentDescription)
        assertEquals("Next Episode", next.contentDescription)
        assertEquals("Audio", audio.contentDescription)
        assertEquals("Subtitles", subtitles.contentDescription)
        assertEquals("Connection Speed", speed.contentDescription)
        assertEquals(R.id.player_subtitles_btn, audio.nextFocusRightId)
        assertEquals(R.id.player_connection_speed_btn, subtitles.nextFocusRightId)
        assertEquals(androidx.media3.ui.R.id.exo_rew, previous.nextFocusRightId)
        assertEquals(
            R.id.player_restart_episode_btn,
            controls.findViewById<ImageButton>(androidx.media3.ui.R.id.exo_play_pause).nextFocusRightId
        )
        assertEquals(R.id.player_audio_btn, next.nextFocusRightId)
    }

    @Test
    fun `skip segment control uses exact active server range`() {
        val intro = PlaybackSkipSegment(
            PlaybackSkipSegmentType.INTRO,
            startTicks = 10_000_000L,
            endTicks = 75_000_000L
        )
        val outro = PlaybackSkipSegment(
            PlaybackSkipSegmentType.OUTRO,
            startTicks = 500_000_000L,
            endTicks = 600_000_000L
        )

        assertNull(PlayerSkipSegmentPolicy.activeSegment(listOf(intro, outro), 999L))
        assertEquals(intro, PlayerSkipSegmentPolicy.activeSegment(listOf(intro, outro), 1_000L))
        assertEquals("Skip Intro", PlayerSkipSegmentPolicy.label(intro))
        assertEquals("Skip Outro", PlayerSkipSegmentPolicy.label(outro))
        assertEquals(7_500L, PlayerSkipSegmentPolicy.targetPositionMs(intro, durationMs = 60_000L))
        assertNull(
            PlayerSkipSegmentPolicy.targetPositionMs(
                intro.copy(endTicks = intro.startTicks),
                durationMs = 60_000L
            )
        )
    }

    @Test
    fun `continue watching natural end opens details while explicit back returns home`() {
        assertEquals(
            PlaybackExitDestination.DETAILS,
            PlaybackExitPolicy.afterNaturalCompletion(PlaybackLaunchOrigin.CONTINUE_WATCHING)
        )
        assertEquals(
            PlaybackExitDestination.HOME,
            PlaybackExitPolicy.afterUserBack(PlaybackLaunchOrigin.CONTINUE_WATCHING)
        )
        assertEquals(
            PlaybackExitDestination.BACK_STACK,
            PlaybackExitPolicy.afterNaturalCompletion(PlaybackLaunchOrigin.DEFAULT)
        )
        assertEquals(
            PlaybackExitDestination.BACK_STACK,
            PlaybackExitPolicy.afterUserBack(PlaybackLaunchOrigin.DEFAULT)
        )

        val completion = PlaybackNaturalCompletionState()
        assertTrue(completion.claim())
        assertFalse(completion.claim())
        completion.reset()
        assertTrue(completion.claim())
    }

    @Test
    fun `restart planning preserves current position`() {
        assertEquals(123_450_000L, PlayerRestartPolicy.positionTicks(12_345L))
        assertEquals(0L, PlayerRestartPolicy.positionTicks(-1L))
        assertTrue(PlayerRestartPolicy.positionTicks(1L) > 0L)
    }

    @Test
    fun `fully backgrounded player releases its decoder ownership`() {
        assertTrue(
            PlayerBackgroundReleasePolicy.shouldRelease(
                changingConfigurations = false,
                inPictureInPicture = false,
                finishing = false,
                userExitRequested = false
            )
        )
    }

    @Test
    fun `configuration picture in picture and exits retain their existing lifecycle owner`() {
        assertFalse(
            PlayerBackgroundReleasePolicy.shouldRelease(
                changingConfigurations = true,
                inPictureInPicture = false,
                finishing = false,
                userExitRequested = false
            )
        )
        assertFalse(
            PlayerBackgroundReleasePolicy.shouldRelease(
                changingConfigurations = false,
                inPictureInPicture = true,
                finishing = false,
                userExitRequested = false
            )
        )
        assertFalse(
            PlayerBackgroundReleasePolicy.shouldRelease(
                changingConfigurations = false,
                inPictureInPicture = false,
                finishing = true,
                userExitRequested = false
            )
        )
        assertFalse(
            PlayerBackgroundReleasePolicy.shouldRelease(
                changingConfigurations = false,
                inPictureInPicture = false,
                finishing = false,
                userExitRequested = true
            )
        )
    }

    @Test
    fun `network recovery uses the bounded stall bridging schedule`() {
        val state = PlayerNetworkRetryState()

        assertEquals(
            PlayerNetworkRetry(attempt = 1, delayMs = 2_000L),
            state.claim(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        )
        assertEquals(
            PlayerNetworkRetry(attempt = 2, delayMs = 10_000L),
            state.claim(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        )
        assertEquals(
            PlayerNetworkRetry(attempt = 3, delayMs = 30_000L),
            state.claim(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        )
        assertNull(state.claim(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(PlayerNetworkRetryState.maxAttempts, state.attemptsUsed)

        state.reset()

        assertEquals(0, state.attemptsUsed)
        assertEquals(
            PlayerNetworkRetry(attempt = 1, delayMs = 2_000L),
            state.claim(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        )
    }

    @Test
    fun `non-network errors do not consume current-route retries`() {
        val state = PlayerNetworkRetryState()

        assertNull(state.claim(PlaybackException.ERROR_CODE_TIMEOUT))
        assertNull(state.claim(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertNull(state.claim(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals(0, state.attemptsUsed)
    }

    @Test
    fun `transcode fallback is limited to container and decoder compatibility failures`() {
        val compatibleFallbackErrors = listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        )
        compatibleFallbackErrors.forEach { errorCode ->
            assertTrue(
                "expected transcode fallback for error code $errorCode",
                PlayerErrorRecoveryPolicy.shouldAttemptTranscode(errorCode)
            )
        }

        val excludedErrors = listOf(
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED
        )
        excludedErrors.forEach { errorCode ->
            assertFalse(
                "did not expect transcode fallback for error code $errorCode",
                PlayerErrorRecoveryPolicy.shouldAttemptTranscode(errorCode)
            )
        }
    }

    @Test
    fun `natural end and user back share one stop report with the final position`() {
        val state = PlaybackStopReportState()
        val finalTicks = PlayerRestartPolicy.positionTicks(maxOf(12_000L, 12_345L))

        val first = state.begin("movie-1", "session-1", finalTicks)
        assertTrue(first is PlaybackStopReportDecision.Claimed)
        val report = (first as PlaybackStopReportDecision.Claimed).report
        assertEquals("movie-1", report.itemId)
        assertEquals("session-1", report.playSessionId)
        assertEquals(123_450_000L, report.positionTicks)

        assertEquals(
            PlaybackStopReportDecision.InFlight,
            state.begin("movie-1", "session-1", 999_000_000L)
        )
        state.complete(report)
        assertEquals(
            PlaybackStopReportDecision.Complete,
            state.begin("movie-1", "session-1", 999_000_000L)
        )
    }

    @Test
    fun `user back waits when an autoplay stop report is already in flight`() {
        val state = PlaybackStopReportState()
        val autoplay = state.begin("episode-1", "autoplay-session", 500L)
            as PlaybackStopReportDecision.Claimed

        assertEquals(
            PlaybackStopReportDecision.InFlight,
            state.begin("episode-1", "autoplay-session", 700L)
        )
        state.complete(autoplay.report)
        assertEquals(
            PlaybackStopReportDecision.Complete,
            state.begin("episode-1", "autoplay-session", 700L)
        )
    }

    @Test
    fun `dialog restart can stop a new play session for the same item`() {
        val state = PlaybackStopReportState()
        val original = state.begin("movie-1", "session-before-restart", 10L)
            as PlaybackStopReportDecision.Claimed
        state.complete(original.report)

        val restarted = state.begin("movie-1", "session-after-restart", 20L)
        assertTrue(restarted is PlaybackStopReportDecision.Claimed)
        assertEquals(
            "session-after-restart",
            (restarted as PlaybackStopReportDecision.Claimed).report.playSessionId
        )
    }

    @Test
    fun `stop report is not created before Jellyfin assigns a play session`() {
        val state = PlaybackStopReportState()

        assertEquals(
            PlaybackStopReportDecision.NoActiveSession,
            state.begin("movie-1", "", 123L)
        )
        assertEquals(
            PlaybackStopReportDecision.NoActiveSession,
            state.begin("", "session-1", 123L)
        )
    }

    @Test
    fun `stop report position cannot be negative`() {
        val result = PlaybackStopReportState().begin("movie-1", "session-1", -1L)
            as PlaybackStopReportDecision.Claimed

        assertEquals(0L, result.report.positionTicks)
    }

    @Test
    fun `failed stop report can be claimed for retry`() {
        val state = PlaybackStopReportState()
        val first = state.begin("movie-1", "session-1", 123L)
            as PlaybackStopReportDecision.Claimed

        state.failed(first.report)

        val retry = state.begin("movie-1", "session-1", 456L)
            as PlaybackStopReportDecision.Claimed
        assertEquals(456L, retry.report.positionTicks)
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
