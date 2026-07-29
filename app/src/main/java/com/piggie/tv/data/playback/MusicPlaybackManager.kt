package com.piggie.tv.data.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.services.AudioPlayerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.piggie.tv.diagnostics.PtvAudioTrace
import com.piggie.tv.diagnostics.PtvDiagnosticsManager

object MusicPlaybackManager {
    private const val TAG = "MusicPlayback"
    private val controllerLock = Any()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    @Volatile private var controller: MediaController? = null
    
    private val _currentTrack = MutableStateFlow<com.piggie.tv.data.models.MediaItem?>(null)
    val currentTrack: StateFlow<com.piggie.tv.data.models.MediaItem?> = _currentTrack
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _progress = MutableStateFlow(MusicProgressSnapshot())
    val progress: StateFlow<MusicProgressSnapshot> = _progress

    private var playlist: List<com.piggie.tv.data.models.MediaItem> = emptyList()
    private var pendingPlay: PendingPlay? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            publishProgress()
            if (controller?.isPlaying == true) {
                progressHandler.postDelayed(this, WaveProgressModel.UPDATE_INTERVAL_MS)
            }
        }
    }

    fun init(context: Context) {
        val future = synchronized(controllerLock) {
            if (controller != null || controllerFuture != null) return
            PtvDiagnosticsManager.recordAudio(
                PtvAudioTrace(
                    event = "controller_bind_started",
                    serviceState = "binding"
                )
            )
            try {
                val sessionToken = SessionToken(
                    context.applicationContext,
                    ComponentName(context.applicationContext, AudioPlayerService::class.java)
                )
                MediaController.Builder(context.applicationContext, sessionToken)
                    .buildAsync()
                    .also { controllerFuture = it }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to init MusicPlaybackManager", error)
                PtvDiagnosticsManager.recordAudio(
                    PtvAudioTrace(
                        event = "controller_error",
                        serviceState = "error",
                        error = error.javaClass.simpleName
                    )
                )
                return
            }
        }
        future.addListener({
            val connected = try {
                future.get()
            } catch (error: Exception) {
                synchronized(controllerLock) {
                    if (controllerFuture === future) controllerFuture = null
                }
                Log.e(TAG, "Failed to connect MediaController", error)
                PtvDiagnosticsManager.recordAudio(
                    PtvAudioTrace(
                        event = "controller_error",
                        serviceState = "error",
                        error = error.javaClass.simpleName
                    )
                )
                return@addListener
            }
            runOnUiThread {
                var queued: PendingPlay? = null
                val adopted = synchronized(controllerLock) {
                    if (controllerFuture !== future || controller != null) {
                        false
                    } else {
                        controller = connected
                        controllerFuture = null
                        queued = pendingPlay
                        pendingPlay = null
                        true
                    }
                }
                if (!adopted) {
                    connected.release()
                    return@runOnUiThread
                }
                setupController()
                queued?.let {
                    activityPlay(it.session, it.items, it.startIndex)
                }
                Log.d(TAG, "MediaController connected")
                PtvDiagnosticsManager.recordAudio(
                    PtvAudioTrace(
                        event = "controller_connected",
                        serviceState = "connected"
                    )
                )
            }
        }, MoreExecutors.directExecutor())
    }

    private fun setupController() {
        controller?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) startProgressUpdates() else stopProgressUpdates()
                PtvDiagnosticsManager.recordAudio(PtvAudioTrace(event = "play_state", mediaSessionState = if (isPlaying) "playing" else "paused", queueSize = playlist.size))
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _currentTrack.value = playlist.find { it.id == mediaItem?.mediaId }
                publishProgress()
                PtvDiagnosticsManager.recordAudio(PtvAudioTrace(event = "track_transition", queueSize = playlist.size, currentIndex = controller?.currentMediaItemIndex))
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    stopProgressUpdates(reset = true)
                } else {
                    publishProgress()
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "Player Error: ${error.message}", error)
                stopProgressUpdates()
                PtvDiagnosticsManager.recordAudio(PtvAudioTrace(event = "player_error", error = error.errorCodeName))
            }
        })
        publishProgress()
        if (controller?.isPlaying == true) startProgressUpdates()
    }

    fun play(context: Context, session: NativeSession, items: List<com.piggie.tv.data.models.MediaItem>, startIndex: Int = 0) {
        val bounded = MusicQueuePolicy.bound(items, startIndex) ?: return
        val request = PendingPlay(session, bounded.items, bounded.startIndex)
        val activeController = synchronized(controllerLock) {
            controller.also {
                if (it == null) pendingPlay = request
            }
        }
        if (activeController == null) {
            init(context)
        } else {
            runOnUiThread {
                activityPlay(request.session, request.items, request.startIndex)
            }
        }
    }

    private fun runOnUiThread(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    private fun activityPlay(session: NativeSession, items: List<com.piggie.tv.data.models.MediaItem>, startIndex: Int) {
        playlist = items
        PtvDiagnosticsManager.recordAudio(PtvAudioTrace(event = "queue_build", queueSize = items.size, currentIndex = startIndex))
        controller?.let { c ->
            c.stop()
            c.clearMediaItems()
            val media3Items = items.map { item ->
                MediaItem.Builder()
                    .setMediaId(item.id)
                    .setUri(session.serverUrl + "/Audio/" + item.id + "/stream?Static=true")
                    .build()
            }
            c.setMediaItems(media3Items, startIndex, 0L)
            c.prepare()
            c.play()
            _currentTrack.value = items[startIndex]
            _progress.value = MusicProgressSnapshot()
        }
    }

    fun togglePlayPause() {
        controller?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun next() { 
        controller?.let {
            if (it.hasNextMediaItem()) it.seekToNext()
        }
    }
    fun previous() { 
        controller?.let {
            if (it.hasPreviousMediaItem()) it.seekToPrevious()
        }
    }

    fun stop() {
        synchronized(controllerLock) {
            pendingPlay = null
        }
        controller?.run {
            stop()
            clearMediaItems()
        }
        stopProgressUpdates(reset = true)
        _currentTrack.value = null
        _isPlaying.value = false
        playlist = emptyList()
        PtvDiagnosticsManager.recordAudio(PtvAudioTrace(event = "stop", mediaSessionState = "stopped", queueSize = playlist.size))
    }

    private fun startProgressUpdates() {
        progressHandler.removeCallbacks(progressTicker)
        publishProgress()
        if (controller?.isPlaying == true) {
            progressHandler.postDelayed(progressTicker, WaveProgressModel.UPDATE_INTERVAL_MS)
        }
    }

    private fun stopProgressUpdates(reset: Boolean = false) {
        progressHandler.removeCallbacks(progressTicker)
        if (reset) {
            _progress.value = MusicProgressSnapshot()
        } else {
            publishProgress()
        }
    }

    private fun publishProgress() {
        val activeController = controller ?: return
        val duration = activeController.duration
            .takeUnless { it == C.TIME_UNSET || it < 0L }
            ?: 0L
        _progress.value = WaveProgressModel.snapshot(
            positionMs = activeController.currentPosition,
            durationMs = duration,
            bufferedPositionMs = activeController.bufferedPosition
        )
    }

    private data class PendingPlay(
        val session: NativeSession,
        val items: List<com.piggie.tv.data.models.MediaItem>,
        val startIndex: Int
    )
}
