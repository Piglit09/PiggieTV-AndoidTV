package com.piggie.tv.services

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.session.SecureSessionStore

@OptIn(UnstableApi::class)
class AudioPlayerService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: Player
    private val api by lazy { JellyfinNativeApi(this) }
    private val store by lazy { SecureSessionStore(this) }
    private var reportingHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reportingIntervalMs = 15_000L

    override fun onCreate() {
        super.onCreate()
        
        val session = store.read()
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", api.authorization(session?.token))
                    .addHeader("X-Emby-Authorization", api.authorization(session?.token))
                    .addHeader("X-MediaBrowser-Token", session?.token ?: "")
                    .build()
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
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) startReporting() else stopReporting()
                    }
                    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                        if (mediaItem != null) reportItemStart(mediaItem.mediaId)
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        android.util.Log.e("AudioPlayer", "ExoPlayer Error: ${error.message}", error)
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
            .setSessionActivity(pendingIntent)
            .build()
    }

    private fun reportItemStart(itemId: String) {
        val session = store.read() ?: return
        api.reportPlaying(session, itemId, "", 0L)
    }

    private val reportRunnable = object : Runnable {
        override fun run() {
            val session = store.read() ?: return
            val itemId = player.currentMediaItem?.mediaId ?: return
            val posTicks = player.currentPosition * 10_000
            val paused = !player.isPlaying
            api.reportProgress(session, itemId, "", posTicks, paused)
            reportingHandler.postDelayed(this, reportingIntervalMs)
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
        val lastItemId = player.currentMediaItem?.mediaId
        val lastPos = player.currentPosition
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        val session = store.read()
        if (session != null && lastItemId != null) {
            api.reportStopped(session, lastItemId, "", lastPos * 10_000)
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
}
