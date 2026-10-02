package com.piggie.tv.services

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.playback.MUSIC_QUEUE_ENTRY_ID_EXTRA
import com.piggie.tv.data.playback.MusicPlaybackReportEvent
import com.piggie.tv.data.playback.MusicPlaybackReportTracker
import com.piggie.tv.data.playback.PlaybackOriginPolicy
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.diagnostics.PtvAudioTrace
import com.piggie.tv.diagnostics.PtvDiagnosticsManager

private const val AUDIO_MEDIA_SESSION_ID = "ptv-audio-session"

@OptIn(UnstableApi::class)
class AudioPlayerService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: Player
    private val api by lazy { JellyfinNativeApi(this) }
    private val store by lazy { SecureSessionStore(this) }
    private val reportingHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reportingIntervalMs = 15_000L
    private val reportTracker = MusicPlaybackReportTracker()
    private var reportingSession: NativeSession? = null

    override fun onCreate() {
        super.onCreate()
        PtvDiagnosticsManager.recordAudio(PtvAudioTrace(event = "service_created", serviceState = "created"))
        
        val client = okhttp3.OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                val original = chain.request()
                val requestBuilder = original.newBuilder()
                    .removeHeader("Authorization")
                    .removeHeader("X-Emby-Authorization")
                    .removeHeader("X-MediaBrowser-Token")
                val session = store.read()
                if (
                    session != null &&
                    PlaybackOriginPolicy.shouldAttachCredentials(
                        session.serverUrl,
                        original.url.toString()
                    )
                ) {
                    val authorization = api.authorization(session.token)
                    requestBuilder
                        .header("Authorization", authorization)
                        .header("X-Emby-Authorization", authorization)
                        .header("X-MediaBrowser-Token", session.token)
                }
                val request = requestBuilder.build()
                chain.proceed(request)
            }
            .build()

        val dataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(client)
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        MUSIC_MIN_BUFFER_MS,
                        MUSIC_MAX_BUFFER_MS,
                        MUSIC_PLAYBACK_BUFFER_MS,
                        MUSIC_REBUFFER_MS
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build()
            .apply {
                PtvDiagnosticsManager.event(
                    "music",
                    "playback_buffer_policy",
                    mapOf(
                        "minBufferMs" to MUSIC_MIN_BUFFER_MS.toString(),
                        "maxBufferMs" to MUSIC_MAX_BUFFER_MS.toString(),
                        "playbackBufferMs" to MUSIC_PLAYBACK_BUFFER_MS.toString(),
                        "rebufferMs" to MUSIC_REBUFFER_MS.toString(),
                        "queuePrefetch" to "media3_timeline"
                    )
                )
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        PtvDiagnosticsManager.recordAudio(PtvAudioTrace(event = "service_play_state", serviceState = "running", mediaSessionState = if (isPlaying) "playing" else "paused"))
                        if (isPlaying) startReporting() else stopReporting()
                    }
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (playWhenReady) ensureCurrentItemIsReported()
                        if (reportTracker.activeItemId != null) {
                            dispatchReports(
                                reportTracker.progress(
                                    positionTicks = player.currentPosition.toTicks(),
                                    isPaused = !playWhenReady
                                )
                            )
                        }
                    }
                    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                        PtvDiagnosticsManager.recordAudio(PtvAudioTrace(event = "service_track_transition", currentIndex = this@AudioPlayerService.player.currentMediaItemIndex))
                        dispatchReports(
                            reportTracker.transitionTo(
                                itemId = mediaItem?.mediaId,
                                // Universal audio owns its internal transcode session and does not
                                // expose that identifier to clients.
                                playSessionId = "",
                                entryId = mediaItem?.queueEntryId()
                            )
                        )
                    }
                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        val oldItemId = oldPosition.mediaItem?.mediaId
                        val newItemId = newPosition.mediaItem?.mediaId
                        val oldEntryId = oldPosition.mediaItem?.queueEntryId()
                        if (
                            oldItemId != null &&
                            oldItemId == reportTracker.activeItemId &&
                            oldEntryId == reportTracker.activeEntryId &&
                            (
                                oldItemId != newItemId ||
                                oldPosition.mediaItemIndex != newPosition.mediaItemIndex
                            )
                        ) {
                            dispatchReports(
                                reportTracker.finish(oldPosition.positionMs.toTicks())
                            )
                        }
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_IDLE,
                            Player.STATE_ENDED -> dispatchReports(
                                reportTracker.finish(player.currentPosition.toTicks())
                            )
                            Player.STATE_BUFFERING,
                            Player.STATE_READY -> if (player.playWhenReady) {
                                ensureCurrentItemIsReported()
                            }
                        }
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        android.util.Log.e("AudioPlayer", "ExoPlayer Error: ${error.message}", error)
                        dispatchReports(
                            reportTracker.finish(player.currentPosition.toTicks())
                        )
                        PtvDiagnosticsManager.recordAudio(PtvAudioTrace(event = "service_player_error", error = error.errorCodeName))
                    }
                })
            }

        val intent = Intent(this, PtvHostActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setId(AUDIO_MEDIA_SESSION_ID)
            .setSessionActivity(pendingIntent)
            .build()
    }

    private val reportRunnable = object : Runnable {
        override fun run() {
            if (player.currentMediaItem == null || reportTracker.activeItemId == null) return
            dispatchReports(
                reportTracker.progress(
                    positionTicks = player.currentPosition.toTicks(),
                    isPaused = !player.playWhenReady
                )
            )
            if (player.isPlaying) {
                reportingHandler.postDelayed(this, reportingIntervalMs)
            }
        }
    }

    private fun ensureCurrentItemIsReported() {
        if (reportTracker.activeItemId != null) return
        val mediaItem = player.currentMediaItem ?: return
        dispatchReports(
            reportTracker.transitionTo(
                itemId = mediaItem.mediaId,
                playSessionId = "",
                entryId = mediaItem.queueEntryId()
            )
        )
    }

    private fun dispatchReports(events: List<MusicPlaybackReportEvent>) {
        events.forEach { event ->
            when (event) {
                is MusicPlaybackReportEvent.Playing -> {
                    val session = store.read() ?: return@forEach
                    reportingSession = session
                    api.reportPlaying(
                        session,
                        event.itemId,
                        event.playSessionId,
                        event.positionTicks
                    )
                }
                is MusicPlaybackReportEvent.Progress -> {
                    val session = reportingSession ?: store.read() ?: return@forEach
                    reportingSession = session
                    api.reportProgress(
                        session,
                        event.itemId,
                        event.playSessionId,
                        event.positionTicks,
                        event.isPaused
                    )
                }
                is MusicPlaybackReportEvent.Stopped -> {
                    val session = reportingSession ?: store.read()
                    if (session != null) {
                        api.reportStopped(
                            session,
                            event.itemId,
                            event.playSessionId,
                            event.positionTicks
                        )
                    }
                    reportingSession = null
                }
            }
        }
    }

    private fun startReporting() {
        reportingHandler.removeCallbacks(reportRunnable)
        reportingHandler.postDelayed(reportRunnable, reportingIntervalMs)
    }

    private fun stopReporting() {
        reportingHandler.removeCallbacks(reportRunnable)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        PtvDiagnosticsManager.recordAudio(PtvAudioTrace(event = "service_destroyed", serviceState = "destroyed"))
        stopReporting()
        if (::player.isInitialized) {
            dispatchReports(reportTracker.finish(player.currentPosition.toTicks()))
        }
        mediaSession?.release()
        mediaSession = null
        if (::player.isInitialized) {
            player.release()
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player != null) {
            if (!player.playWhenReady || player.mediaItemCount == 0) {
                stopSelf()
            }
        }
    }

    private fun Long.toTicks(): Long = coerceAtLeast(0L) * 10_000L

    private fun androidx.media3.common.MediaItem.queueEntryId(): String? =
        mediaMetadata.extras?.getString(MUSIC_QUEUE_ENTRY_ID_EXTRA)

    private companion object {
        const val MUSIC_MIN_BUFFER_MS = 30_000
        const val MUSIC_MAX_BUFFER_MS = 120_000
        const val MUSIC_PLAYBACK_BUFFER_MS = 1_000
        const val MUSIC_REBUFFER_MS = 2_500
    }
}
