package com.piggie.tv.data.playback

import android.content.ComponentName
import android.content.Context
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

object MusicPlaybackManager {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    
    private val _currentTrack = MutableStateFlow<com.piggie.tv.data.models.MediaItem?>(null)
    val currentTrack: StateFlow<com.piggie.tv.data.models.MediaItem?> = _currentTrack
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private var playlist: List<com.piggie.tv.data.models.MediaItem> = emptyList()

    fun init(context: Context) {
        if (controller != null) return
        val sessionToken = SessionToken(context, ComponentName(context, AudioPlayerService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
            setupController()
        }, MoreExecutors.directExecutor())
    }

    private fun setupController() {
        controller?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _currentTrack.value = playlist.find { it.id == mediaItem?.mediaId }
            }
        })
    }

    fun play(context: Context, session: NativeSession, items: List<com.piggie.tv.data.models.MediaItem>, startIndex: Int = 0) {
        init(context)
        playlist = items
        controller?.let { c ->
            c.stop()
            c.clearMediaItems()
            val media3Items = items.map { item ->
                MediaItem.Builder()
                    .setMediaId(item.id)
                    .setUri(session.serverUrl + "/Audio/" + item.id + "/stream?Static=true&api_key=" + session.token)
                    .build()
            }
            c.setMediaItems(media3Items, startIndex, 0L)
            c.prepare()
            c.play()
            _currentTrack.value = items[startIndex]
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
        controller?.stop()
        _currentTrack.value = null
    }
}
