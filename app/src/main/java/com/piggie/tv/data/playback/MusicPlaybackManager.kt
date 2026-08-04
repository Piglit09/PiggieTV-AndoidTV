package com.piggie.tv.data.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.NativeSettings
import com.piggie.tv.services.AudioPlayerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.piggie.tv.diagnostics.PtvAudioTrace
import com.piggie.tv.diagnostics.PtvDiagnosticsManager

const val MUSIC_QUEUE_ENTRY_ID_EXTRA = "com.piggie.tv.music.QUEUE_ENTRY_ID"

@OptIn(UnstableApi::class)
object MusicPlaybackManager {
    private const val TAG = "MusicPlayback"
    private val controllerLock = Any()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    @Volatile private var controller: MediaController? = null
    @Volatile private var applicationContext: Context? = null
    
    private val _currentTrack = MutableStateFlow<com.piggie.tv.data.models.MediaItem?>(null)
    val currentTrack: StateFlow<com.piggie.tv.data.models.MediaItem?> = _currentTrack
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _progress = MutableStateFlow(MusicProgressSnapshot())
    val progress: StateFlow<MusicProgressSnapshot> = _progress

    private var playlist: List<com.piggie.tv.data.models.MediaItem> = emptyList()
    private var pendingPlay: PendingPlay? = null
    private var playbackFailed = false
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
        applicationContext = context.applicationContext
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
                if (future.isCancelled) return@addListener
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
                    activityPlay(it)
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
                publishPlayIntent()
                if (isPlaying) startProgressUpdates() else stopProgressUpdates()
                PtvDiagnosticsManager.recordAudio(PtvAudioTrace(event = "play_state", mediaSessionState = if (isPlaying) "playing" else "paused", queueSize = playlist.size))
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                publishPlayIntent()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                playbackFailed = false
                _currentTrack.value = playlist.find { it.id == mediaItem?.mediaId }
                publishPlayIntent()
                publishProgress()
                PtvDiagnosticsManager.recordAudio(PtvAudioTrace(event = "track_transition", queueSize = playlist.size, currentIndex = controller?.currentMediaItemIndex))
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) playbackFailed = false
                if (playbackState == Player.STATE_ENDED) {
                    stopProgressUpdates(reset = true)
                } else {
                    publishProgress()
                }
                publishPlayIntent()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playbackFailed = true
                publishPlayIntent()
                Log.e(TAG, "Player Error: ${error.message}", error)
                stopProgressUpdates()
                PtvDiagnosticsManager.recordAudio(PtvAudioTrace(event = "player_error", error = error.errorCodeName))
            }
        })
        publishPlayIntent()
        publishProgress()
        if (controller?.isPlaying == true) startProgressUpdates()
    }

    fun play(context: Context, session: NativeSession, items: List<com.piggie.tv.data.models.MediaItem>, startIndex: Int = 0) {
        val bounded = MusicQueuePolicy.bound(items, startIndex) ?: return
        val applicationContext = context.applicationContext
        this.applicationContext = applicationContext
        val request = PendingPlay(
            session = session,
            items = bounded.items,
            startIndex = bounded.startIndex,
            deviceId = JellyfinNativeApi(applicationContext).ensureDeviceId(),
            maxStreamingBitrate = ConnectionSpeed.fromStored(
                NativeSettings(applicationContext).connectionSpeed
            ).negotiationBitrate()
        )
        val activeController = synchronized(controllerLock) {
            controller.also {
                if (it == null) pendingPlay = request
            }
        }
        if (activeController == null) {
            init(context)
        } else {
            runOnUiThread {
                activityPlay(request)
            }
        }
    }

    private fun runOnUiThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            progressHandler.post(action)
        }
    }

    private fun activityPlay(request: PendingPlay) {
        val session = request.session
        val items = request.items
        val startIndex = request.startIndex
        playlist = items
        playbackFailed = false
        PtvDiagnosticsManager.recordAudio(PtvAudioTrace(event = "queue_build", queueSize = items.size, currentIndex = startIndex))
        controller?.let { c ->
            c.stop()
            c.clearMediaItems()
            val api = JellyfinNativeApi(requireNotNull(applicationContext))
            val media3Items = items.mapIndexed { queueIndex, item ->
                val metadata = MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setIsPlayable(true)
                    .setExtras(Bundle().apply {
                        putString(
                            MUSIC_QUEUE_ENTRY_ID_EXTRA,
                            "${item.id}:$queueIndex"
                        )
                    })
                    .apply {
                        val artist = item.artists.joinToString(", ")
                            .takeIf(String::isNotBlank)
                            ?: item.albumArtist
                        artist?.takeIf(String::isNotBlank)?.let(::setArtist)
                        item.album?.takeIf(String::isNotBlank)?.let(::setAlbumTitle)
                        item.runtimeTicks.takeIf { it > 0L }
                            ?.div(10_000L)
                            ?.let(::setDurationMs)
                        setArtworkUri(
                            Uri.parse(
                                api.imageUrl(
                                    session,
                                    item,
                                    MediaCardPresentation.SQUARE
                                )
                            )
                        )
                    }
                    .build()
                MediaItem.Builder()
                    .setMediaId(item.id)
                    .setUri(
                        JellyfinUniversalAudioUrl.build(
                            serverUrl = session.serverUrl,
                            itemId = item.id,
                            userId = session.userId,
                            deviceId = request.deviceId,
                            mediaSourceId = item.mediaSourceId,
                            maxStreamingBitrate = request.maxStreamingBitrate
                        )
                    )
                    .setMediaMetadata(metadata)
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
        controller?.let { activeController ->
            when (
                MusicPlaybackControlPolicy.toggleAction(
                    playWhenReady = activeController.playWhenReady,
                    playbackEnded = activeController.playbackState == Player.STATE_ENDED,
                    playbackFailed = playbackFailed
                )
            ) {
                MusicPlaybackToggleAction.PAUSE -> activeController.pause()
                MusicPlaybackToggleAction.PLAY -> activeController.play()
                MusicPlaybackToggleAction.RESTART -> {
                    playbackFailed = false
                    activeController.seekToDefaultPosition()
                    activeController.prepare()
                    activeController.play()
                }
            }
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
        runOnUiThread {
            synchronized(controllerLock) {
                pendingPlay = null
            }
            controller?.run {
                stop()
                clearMediaItems()
            }
            clearPlaybackState()
            PtvDiagnosticsManager.recordAudio(PtvAudioTrace(event = "stop", mediaSessionState = "stopped", queueSize = playlist.size))
        }
    }

    /** Releases the controller binding after an explicit account/app shutdown. */
    fun shutdown() {
        runOnUiThread {
            val activeController: MediaController?
            val pendingController: ListenableFuture<MediaController>?
            synchronized(controllerLock) {
                pendingPlay = null
                activeController = controller
                controller = null
                pendingController = controllerFuture
                controllerFuture = null
            }
            pendingController?.cancel(true)
            activeController?.run {
                runCatching { stop() }
                runCatching { clearMediaItems() }
                release()
            }
            clearPlaybackState()
            PtvDiagnosticsManager.recordAudio(
                PtvAudioTrace(
                    event = "controller_released",
                    serviceState = "released",
                    mediaSessionState = "stopped",
                    queueSize = 0
                )
            )
        }
    }

    private fun clearPlaybackState() {
        stopProgressUpdates(reset = true)
        _currentTrack.value = null
        _isPlaying.value = false
        playlist = emptyList()
        playbackFailed = false
    }

    private fun publishPlayIntent() {
        val activeController = controller
        _isPlaying.value = activeController != null &&
            activeController.playWhenReady &&
            activeController.playbackState != Player.STATE_ENDED &&
            !playbackFailed
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
        val startIndex: Int,
        val deviceId: String,
        val maxStreamingBitrate: Int
    )
}
