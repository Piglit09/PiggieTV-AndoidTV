package com.piggie.tv.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.api.NativeRequestScope
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.playback.AutoplayGuardState
import com.piggie.tv.data.playback.AutoplayTrigger
import com.piggie.tv.data.playback.ConnectionSpeed
import com.piggie.tv.data.playback.MusicPlaybackManager
import com.piggie.tv.data.playback.NextEpisodeAutoplayPolicy
import com.piggie.tv.data.playback.PendingPlaybackPreference
import com.piggie.tv.data.playback.PendingPlaybackPreferences
import com.piggie.tv.data.playback.PlaybackNegotiator
import com.piggie.tv.data.playback.PlaybackStream
import com.piggie.tv.data.playback.SubtitleSelectionMode
import com.piggie.tv.data.playback.UP_NEXT_THRESHOLD_MS
import com.piggie.tv.data.playback.UpNextOverlayAction
import com.piggie.tv.data.session.NativeSettings
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import com.piggie.tv.diagnostics.PtvPlaybackTrace
import com.piggie.tv.ui.shared.TextSanitizer
import com.piggie.tv.ui.widgets.PtvSelectionDialog
import com.piggie.tv.util.PTVLog
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

@OptIn(UnstableApi::class)
class VideoPlayerActivity : AppCompatActivity() {
    private val api by lazy { JellyfinNativeApi(this) }
    private val store by lazy { SecureSessionStore(this) }
    private val settings by lazy { NativeSettings(this) }
    private val negotiator by lazy { PlaybackNegotiator(api, settings) }

    private lateinit var session: NativeSession
    private lateinit var itemId: String
    private lateinit var playerView: PlayerView
    private lateinit var statusView: TextView
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null

    private val handler = Handler(Looper.getMainLooper())
    private val playerDialogTimeoutState = PlayerDialogTimeoutState()
    private var pendingPlayerDialogTimeoutRestoreToken: Int? = null
    private val restorePlayerDialogControllerTimeout = Runnable {
        pendingPlayerDialogTimeoutRestoreToken?.let { token ->
            pendingPlayerDialogTimeoutRestoreToken = null
            val baselineTimeoutMs = playerDialogTimeoutState.finish(token)
            if (!destroyed && ::playerView.isInitialized && baselineTimeoutMs != null) {
                playerView.controllerShowTimeoutMs = baselineTimeoutMs
            }
        }
    }
    private val requestGeneration = AtomicInteger(0)
    private val transitionInFlight = AtomicBoolean(false)
    private val stoppedItems = ConcurrentHashMap.newKeySet<String>()
    private val nextCallbacks = mutableListOf<(com.piggie.tv.data.models.MediaItem?) -> Unit>()
    private val apiWorkers = ConcurrentHashMap<Thread, NativeRequestScope>()
    private val apiWorkerKeys = ConcurrentHashMap<String, Thread>()

    private var playSessionId: String? = null
    private var currentItem: com.piggie.tv.data.models.MediaItem? = null
    private var nextEpisode: com.piggie.tv.data.models.MediaItem? = null
    private var nextLookupInFlight = false
    private var nextLookupCompleted = false
    private var upNextOverlay: View? = null
    private var countdownText: TextView? = null
    private var upNextPlayNow: Button? = null
    private var upNextCancel: Button? = null
    private var upNextClose: Button? = null
    private var overlayWasShown = false
    private var overlayDismissed = false
    private var autoplayCanceled = false
    private var playbackError = false
    private var activityForeground = false
    private var destroyed = false
    private var lastKnownPositionMs = 0L
    private var bufferingEvents = 0
    private val hideTemporaryStatus = Runnable { statusView.isVisible = false }

    private var audioIndex: Int? = null
    private var subtitleIndex: Int? = null
    private var subtitleMode: SubtitleSelectionMode = SubtitleSelectionMode.DEFAULT
    private var currentStream: PlaybackStream? = null

    private fun launchApiWork(key: String, name: String, action: () -> Unit) {
        if (destroyed) return
        cancelApiWork(key)
        val scope = NativeRequestScope()
        val worker = Thread(
            {
                try {
                    api.withRequestScope(scope) {
                        if (!scope.isCancelled && !Thread.currentThread().isInterrupted) {
                            action()
                        }
                    }
                } finally {
                    val current = Thread.currentThread()
                    apiWorkers.remove(current)
                    apiWorkerKeys.remove(key, current)
                }
            },
            name
        ).apply { isDaemon = true }
        apiWorkers[worker] = scope
        apiWorkerKeys[key] = worker
        worker.start()
    }

    private fun cancelApiWork(key: String) {
        val worker = apiWorkerKeys.remove(key) ?: return
        apiWorkers.remove(worker)?.cancel()
        worker.interrupt()
    }

    private fun cancelAllApiWork() {
        val workers = apiWorkers.entries.toList()
        apiWorkerKeys.clear()
        apiWorkers.clear()
        workers.forEach { (worker, scope) ->
            scope.cancel()
            worker.interrupt()
        }
        api.cancelInFlightRequests()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_video_player)
        playerView = findViewById(R.id.player_view)
        statusView = findViewById(R.id.player_status)

        MusicPlaybackManager.stop()
        session = store.read() ?: run { finish(); return }
        itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: run { finish(); return }
        audioIndex = intent.getIntExtra(EXTRA_AUDIO_INDEX, -1).takeIf { it != -1 }
        subtitleIndex = intent.getIntExtra(EXTRA_SUBTITLE_INDEX, -1).takeIf { it != -1 }
        subtitleMode = intent.getStringExtra(EXTRA_SUBTITLE_MODE)
            ?.let { runCatching { SubtitleSelectionMode.valueOf(it) }.getOrNull() }
            ?: if (subtitleIndex != null) SubtitleSelectionMode.TRACK else SubtitleSelectionMode.DEFAULT

        createSinglePlayer()
        bindIndependentControlActions()

        val startTicks = intent.getLongExtra(EXTRA_START_TICKS, 0L)
        prepareItem(itemId, startTicks)
    }

    private fun bindIndependentControlActions() {
        playerView.findViewById<ImageButton>(R.id.player_audio_btn)?.setOnClickListener { opener ->
            currentItem?.let { showAudioSelection(it, opener) }
        }
        playerView.findViewById<ImageButton>(R.id.player_subtitles_btn)?.setOnClickListener { opener ->
            currentItem?.let { showSubtitleSelection(it, opener) }
        }
        playerView.findViewById<ImageButton>(R.id.player_connection_speed_btn)?.setOnClickListener { opener ->
            showConnectionSpeedSelection(opener)
        }
        updateControlState()
    }

    private fun updateControlState() {
        val item = currentItem
        playerView.findViewById<ImageButton>(R.id.player_audio_btn)?.apply {
            isEnabled = item?.audioTracks?.isNotEmpty() == true
            alpha = if (isEnabled) 1f else 0.55f
            isSelected = audioIndex != null
            val selected = item?.audioTracks?.firstOrNull { it.index == audioIndex }
            contentDescription = "Audio, ${selected?.language ?: selected?.title ?: "Default"}"
        }
        playerView.findViewById<ImageButton>(R.id.player_subtitles_btn)?.apply {
            isEnabled = item != null
            alpha = if (isEnabled) 1f else 0.55f
            isSelected = subtitleMode != SubtitleSelectionMode.DEFAULT
            val selected = item?.subtitleTracks?.firstOrNull { it.index == subtitleIndex }
            val label = when (subtitleMode) {
                SubtitleSelectionMode.DEFAULT -> "Default"
                SubtitleSelectionMode.OFF -> "Off"
                SubtitleSelectionMode.TRACK -> selected?.language ?: selected?.title ?: "Track"
            }
            contentDescription = "Subtitles, $label"
        }
        playerView.findViewById<ImageButton>(R.id.player_connection_speed_btn)?.apply {
            val speed = ConnectionSpeed.fromStored(settings.connectionSpeed)
            isSelected = speed != ConnectionSpeed.AUTO
            contentDescription = "Connection Speed, ${speed.wireValue}"
        }
    }

    private fun createSinglePlayer() {
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .removeHeader("Authorization")
                    .removeHeader("X-Emby-Authorization")
                    .removeHeader("X-MediaBrowser-Token")
                    .apply {
                        if (
                            com.piggie.tv.data.playback.PlaybackOriginPolicy
                                .shouldAttachCredentials(
                                    session.serverUrl,
                                    original.url.toString()
                                )
                        ) {
                            val authorization = api.authorization(session.token)
                            header("Authorization", authorization)
                            header("X-Emby-Authorization", authorization)
                            header("X-MediaBrowser-Token", session.token)
                        }
                    }
                    .build()
                chain.proceed(request)
            }
            .build()
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(OkHttpDataSource.Factory(client))

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        playerView.player = player
        mediaSession = MediaSession.Builder(this, player)
            .setId(VIDEO_MEDIA_SESSION_ID)
            .build()
        PtvDiagnosticsManager.routeRequested("player")
        PtvDiagnosticsManager.recordPlayback(PtvPlaybackTrace(itemId = itemId, event = "player_created"))

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        bufferingEvents++
                        showStatus("Loading video…")
                        PtvDiagnosticsManager.recordPlayback(
                            PtvPlaybackTrace(itemId = itemId, event = "buffering", bufferCount = bufferingEvents)
                        )
                    }
                    Player.STATE_READY -> {
                        statusView.isVisible = false
                        startProgressLoops()
                        PtvDiagnosticsManager.routeVisible("player", "player_view")
                        PtvDiagnosticsManager.routeInteractive("player", "player_view")
                        PtvDiagnosticsManager.recordPlayback(
                            PtvPlaybackTrace(itemId = itemId, event = "ready", bufferCount = bufferingEvents)
                        )
                    }
                    Player.STATE_ENDED -> {
                        PtvDiagnosticsManager.recordPlayback(PtvPlaybackTrace(itemId = itemId, event = "ended"))
                        handlePlaybackEnded()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = true
                stopProgressLoops()
                PTVLog.e("Player error item=${PTVLog.mask(itemId)} code=${error.errorCodeName}", error)
                PtvDiagnosticsManager.recordPlayback(
                    PtvPlaybackTrace(itemId = itemId, event = "player_error", detail = error.errorCodeName)
                )
                showStatus("Playback error. Press Select to retry or Back to exit.")
            }
        })
    }

    /** Negotiates and prepares an item on the one activity-owned ExoPlayer. */
    private fun prepareItem(newItemId: String, startTicks: Long) {
        cancelAllApiWork()
        val requestedAt = android.os.SystemClock.elapsedRealtime()
        val generation = requestGeneration.incrementAndGet()
        resetNextEpisodeState()
        playbackError = false
        showStatus("Loading video…")
        stopProgressLoops()
        player.stop()
        player.clearMediaItems()
        PtvDiagnosticsManager.recordPlayback(
            PtvPlaybackTrace(itemId = newItemId, event = "negotiation_started", audioTrack = audioIndex?.toString(), subtitleTrack = subtitleIndex?.toString())
        )

        launchApiWork(WORK_NEGOTIATION, "ptv-playback-negotiate") {
            runCatching {
                val details = api.loadItem(session, newItemId)
                val validAudio = audioIndex?.takeIf { selected -> details.audioTracks.any { it.index == selected } }
                val validSubtitle = subtitleIndex?.takeIf { selected -> details.subtitleTracks.any { it.index == selected } }
                val stream = negotiator.getPlaybackStream(session, newItemId, startTicks, validAudio, validSubtitle)
                Triple(details, stream, validAudio to validSubtitle)
            }.onSuccess { (details, stream, selections) ->
                runOnUiThread {
                    if (destroyed || generation != requestGeneration.get()) return@runOnUiThread
                    itemId = newItemId
                    currentItem = details
                    currentStream = stream
                    playSessionId = stream.playSessionId
                    audioIndex = selections.first
                    subtitleIndex = selections.second
                    if (subtitleMode == SubtitleSelectionMode.TRACK && subtitleIndex == null) {
                        subtitleMode = SubtitleSelectionMode.DEFAULT
                    }
                    lastKnownPositionMs = startTicks / TICKS_PER_MILLISECOND

                    findViewById<TextView>(R.id.player_title)?.text = details.title
                    findViewById<TextView>(R.id.player_subtitle)?.text = details.seriesName ?: details.year

                    val mediaItem = MediaItem.Builder()
                        .setMediaId(newItemId)
                        .setUri(stream.url)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(details.title)
                                .setSubtitle(details.seriesName ?: details.episodeLabel)
                                .build()
                        )
                        .build()
                    player.setMediaItem(mediaItem, startTicks / TICKS_PER_MILLISECOND)
                    applySubtitleSelectionMode()
                    player.prepare()
                    player.playWhenReady = true
                    player.play()
                    api.reportPlaying(session, newItemId, stream.playSessionId, startTicks)
                    PtvDiagnosticsManager.recordPlayback(
                        PtvPlaybackTrace(
                            itemId = newItemId,
                            event = "prepared",
                            playMethod = stream.method,
                            mediaSourceId = PTVLog.mask(stream.mediaSourceId),
                            codecs = listOfNotNull(stream.videoCodec, stream.audioCodec).joinToString("/"),
                            container = stream.container,
                            resolution = if (stream.width != null && stream.height != null) "${stream.width}x${stream.height}" else null,
                            bitrate = stream.bitrate,
                            connectionSpeed = stream.connectionSpeed,
                            negotiatedBitrate = stream.negotiatedMaxBitrate,
                            startupMs = android.os.SystemClock.elapsedRealtime() - requestedAt,
                            bufferCount = bufferingEvents,
                            audioTrack = audioIndex?.toString(),
                            subtitleTrack = subtitleIndex?.toString()
                        )
                    )
                    transitionInFlight.set(false)
                    updateControlState()
                    PTVLog.i("Playback prepared item=${PTVLog.mask(newItemId)} session=${PTVLog.mask(stream.playSessionId)}")
                }
            }.onFailure { error ->
                runOnUiThread {
                    if (generation != requestGeneration.get()) return@runOnUiThread
                    transitionInFlight.set(false)
                    playbackError = true
                    PTVLog.e("Playback negotiation failed item=${PTVLog.mask(newItemId)}", error)
                    PtvDiagnosticsManager.recordPlayback(
                        PtvPlaybackTrace(itemId = newItemId, event = "negotiation_error", detail = error.javaClass.simpleName)
                    )
                    showStatus("Unable to start playback. Press Select to retry or Back to exit.")
                }
            }
        }
    }

    private fun applySubtitleSelectionMode() {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(
                C.TRACK_TYPE_TEXT,
                subtitleMode == SubtitleSelectionMode.OFF
            )
            .build()
    }

    private val reportRunnable = object : Runnable {
        override fun run() {
            if (destroyed) return
            lastKnownPositionMs = player.currentPosition.coerceAtLeast(0L)
            api.reportProgress(
                session,
                itemId,
                playSessionId.orEmpty(),
                lastKnownPositionMs * TICKS_PER_MILLISECOND,
                !player.playWhenReady
            )
            handler.postDelayed(this, REPORTING_INTERVAL_MS)
        }
    }

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (destroyed) return
            inspectUpNextCountdown()
            handler.postDelayed(this, COUNTDOWN_TICK_MS)
        }
    }

    private fun startProgressLoops() {
        handler.removeCallbacks(reportRunnable)
        handler.removeCallbacks(countdownRunnable)
        handler.postDelayed(reportRunnable, REPORTING_INTERVAL_MS)
        handler.post(countdownRunnable)
    }

    private fun stopProgressLoops() {
        handler.removeCallbacks(reportRunnable)
        handler.removeCallbacks(countdownRunnable)
    }

    private fun inspectUpNextCountdown() {
        val current = currentItem ?: return
        if (!current.type.equals("Episode", ignoreCase = true)) return
        val duration = player.duration.takeIf { it > 0 } ?: (current.runtimeTicks / TICKS_PER_MILLISECOND)
        if (duration <= 0) return
        val remainingMs = (duration - player.currentPosition).coerceAtLeast(0L)
        if (remainingMs <= UP_NEXT_THRESHOLD_MS + NEXT_LOOKUP_HEAD_START_MS) ensureNextEpisodeResolved()

        val next = nextEpisode
        val guard = guardState(next != null)
        if (NextEpisodeAutoplayPolicy.shouldShowOverlay(remainingMs, guard, overlayWasShown) && !overlayDismissed) {
            showUpNextOverlay(next!!)
        }
        if (next != null && remainingMs <= UP_NEXT_THRESHOLD_MS) {
            countdownText?.text = "Playing in ${ceil(remainingMs / 1000.0).toInt().coerceAtLeast(0)}s"
        }
        if (remainingMs == 0L && NextEpisodeAutoplayPolicy.shouldStart(AutoplayTrigger.COUNTDOWN, guard)) {
            transitionTo(next!!, AutoplayTrigger.COUNTDOWN)
        }
    }

    private fun ensureNextEpisodeResolved(onResolved: ((com.piggie.tv.data.models.MediaItem?) -> Unit)? = null) {
        onResolved?.let(nextCallbacks::add)
        nextEpisode?.let { next ->
            drainNextCallbacks(next)
            return
        }
        if (nextLookupCompleted) {
            drainNextCallbacks(null)
            return
        }
        if (nextLookupInFlight) return
        val current = currentItem
        if (current == null || !current.type.equals("Episode", ignoreCase = true)) {
            drainNextCallbacks(null)
            return
        }
        nextLookupInFlight = true
        val lookupForId = current.id
        val generation = requestGeneration.get()
        launchApiWork(WORK_NEXT_LOOKUP, "ptv-next-episode") {
            val next = runCatching { api.loadNextEpisode(session, current) }
                .onFailure {
                    if (!Thread.currentThread().isInterrupted) {
                        PTVLog.e("Next episode lookup failed item=${PTVLog.mask(lookupForId)}", it)
                    }
                }
                .getOrNull()
            runOnUiThread {
                if (
                    destroyed ||
                    generation != requestGeneration.get() ||
                    currentItem?.id != lookupForId
                ) return@runOnUiThread
                nextLookupInFlight = false
                nextLookupCompleted = true
                nextEpisode = next
                PTVLog.i("Next episode decision current=${PTVLog.mask(lookupForId)} next=${PTVLog.mask(next?.id)}")
                PtvDiagnosticsManager.recordPlayback(
                    PtvPlaybackTrace(
                        itemId = lookupForId,
                        event = "next_episode_decision",
                        detail = if (next == null) "no next episode" else "next=${PTVLog.mask(next.id)} autoplay=${settings.autoplayNextEpisode} canceled=$autoplayCanceled"
                    )
                )
                drainNextCallbacks(next)
            }
        }
    }

    private fun drainNextCallbacks(next: com.piggie.tv.data.models.MediaItem?) {
        val callbacks = nextCallbacks.toList()
        nextCallbacks.clear()
        callbacks.forEach { it(next) }
    }

    private fun handlePlaybackEnded() {
        stopProgressLoops()
        lastKnownPositionMs = player.currentPosition.coerceAtLeast(0L)
        val current = currentItem
        PTVLog.i(
            "Playback ended item=${PTVLog.mask(itemId)} canceled=$autoplayCanceled " +
                "nextResolved=${nextEpisode != null} transitionStarted=${transitionInFlight.get()}"
        )
        PtvDiagnosticsManager.recordPlayback(
            PtvPlaybackTrace(
                itemId = itemId,
                event = "playback_ended_policy",
                detail = "canceled=$autoplayCanceled nextResolved=${nextEpisode != null} transitionStarted=${transitionInFlight.get()}"
            )
        )
        // ExoPlayer can deliver STATE_ENDED after countdown/Play Now has already
        // claimed the transition. That callback must not finish this Activity
        // while the reporting thread is preparing the next item.
        if (transitionInFlight.get()) {
            PtvDiagnosticsManager.recordPlayback(
                PtvPlaybackTrace(itemId = itemId, event = "ended_ignored_transition_in_flight")
            )
            return
        }
        if (current == null || !current.type.equals("Episode", ignoreCase = true)) {
            reportStoppedAsyncIfNeeded()
            finish()
            return
        }

        ensureNextEpisodeResolved { next ->
            val trigger = NextEpisodeAutoplayPolicy.endTrigger(overlayWasShown, overlayDismissed)
            if (next != null && NextEpisodeAutoplayPolicy.shouldStart(trigger, guardState(true))) {
                transitionTo(next, trigger)
            } else {
                reportStoppedAsyncIfNeeded()
                if (!isFinishing) finish()
            }
        }
    }

    private fun guardState(hasNext: Boolean) = AutoplayGuardState(
        enabled = settings.autoplayNextEpisode,
        canceled = autoplayCanceled,
        hasNextItem = hasNext,
        appForeground = activityForeground,
        playbackError = playbackError,
        manuallyStoppedEarly = false,
        transitionAlreadyStarted = transitionInFlight.get()
    )

    private fun transitionTo(next: com.piggie.tv.data.models.MediaItem, trigger: AutoplayTrigger) {
        val guard = guardState(hasNext = true)
        if (!NextEpisodeAutoplayPolicy.shouldStart(trigger, guard)) return
        if (!transitionInFlight.compareAndSet(false, true)) return

        removeUpNextOverlay()
        stopProgressLoops()
        val oldItemId = itemId
        val oldSessionId = playSessionId.orEmpty()
        val finalTicks = player.currentPosition.coerceAtLeast(0L) * TICKS_PER_MILLISECOND
        val generation = requestGeneration.get()
        player.pause()
        PTVLog.i("Autoplay transition trigger=$trigger from=${PTVLog.mask(oldItemId)} to=${PTVLog.mask(next.id)}")
        PtvDiagnosticsManager.recordPlayback(
            PtvPlaybackTrace(itemId = oldItemId, event = "next_item_transition", detail = "trigger=$trigger next=${PTVLog.mask(next.id)}")
        )

        launchApiWork(WORK_NEXT_TRANSITION, "ptv-next-transition") {
            reportStoppedBlockingOnce(oldItemId, oldSessionId, finalTicks)
            runOnUiThread {
                if (!destroyed && generation == requestGeneration.get()) {
                    prepareItem(next.id, 0L)
                }
            }
        }
    }

    private fun reportStoppedBlockingOnce(id: String, sessionId: String, ticks: Long) {
        if (!stoppedItems.add(id)) return
        runCatching { api.reportStoppedNow(session, id, sessionId, ticks) }
            .onFailure {
                PTVLog.e("Stopped report failed item=${PTVLog.mask(id)}", it)
                if (!destroyed && !Thread.currentThread().isInterrupted) {
                    api.reportStopped(session, id, sessionId, ticks)
                }
            }
    }

    private fun reportStoppedAsyncIfNeeded() {
        val id = itemId
        if (!stoppedItems.add(id)) return
        api.reportStopped(
            session,
            id,
            playSessionId.orEmpty(),
            lastKnownPositionMs.coerceAtLeast(player.currentPosition) * TICKS_PER_MILLISECOND
        )
    }

    private fun showUpNextOverlay(next: com.piggie.tv.data.models.MediaItem) {
        if (upNextOverlay != null || overlayDismissed) return
        overlayWasShown = true
        PtvDiagnosticsManager.recordPlayback(
            PtvPlaybackTrace(itemId = itemId, event = "autoplay_countdown", detail = "thresholdMs=$UP_NEXT_THRESHOLD_MS")
        )
        val root = findViewById<ViewGroup>(android.R.id.content)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dim(R.dimen.tv_spacing_large), dim(R.dimen.tv_spacing_medium), dim(R.dimen.tv_spacing_large), dim(R.dimen.tv_spacing_medium))
            setBackgroundResource(R.drawable.tv_panel_background)
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        card.addView(TextView(this).apply {
            text = "Up Next"
            setTextSizeRes(R.dimen.tv_text_size_section_title)
            setTextColor(0xFFFFFFFF.toInt())
        })
        card.addView(TextView(this).apply {
            text = TextSanitizer.formatMetadata(next.episodeLabel, next.title)
            setTextSizeRes(R.dimen.tv_text_size_body)
            setTextColor(0xFFDDDDDD.toInt())
            maxLines = 2
        })
        countdownText = TextView(this).apply {
            text = "Playing soon"
            setTextSizeRes(R.dimen.tv_text_size_metadata)
            setTextColor(0xFFBBBBBB.toInt())
        }
        card.addView(countdownText)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dim(R.dimen.tv_spacing_medium), 0, 0)
        }
        val playNow = compactButton("Play Now", primary = true) {
            performUpNextAction(UpNextOverlayAction.PLAY_NOW, next)
        }.apply {
            id = R.id.up_next_play_now
            contentDescription = "Play Now"
        }
        val cancel = compactButton("Cancel Autoplay") {
            performUpNextAction(UpNextOverlayAction.CANCEL_AUTOPLAY, next)
        }.apply {
            id = R.id.up_next_cancel
            contentDescription = "Cancel Autoplay"
        }
        actions.addView(playNow, LinearLayout.LayoutParams(0, dim(R.dimen.tv_nav_button_height), 1f))
        actions.addView(cancel, LinearLayout.LayoutParams(0, dim(R.dimen.tv_nav_button_height), 1.4f).apply {
            marginStart = dim(R.dimen.tv_spacing_small)
        })
        card.addView(actions)
        val close = compactButton("Close") {
            performUpNextAction(UpNextOverlayAction.CLOSE, next)
        }.apply {
            id = R.id.up_next_close
            contentDescription = "Close; autoplay continues"
        }
        card.addView(close, LinearLayout.LayoutParams(-1, dim(R.dimen.tv_nav_button_height)).apply {
            topMargin = dim(R.dimen.tv_spacing_small)
        })

        val params = android.widget.FrameLayout.LayoutParams(
            dim(R.dimen.tv_player_up_next_width),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.BOTTOM
        ).apply {
            marginEnd = dim(R.dimen.tv_spacing_large)
            bottomMargin = dim(R.dimen.tv_spacing_large)
        }
        root.addView(card, params)
        upNextOverlay = card
        upNextPlayNow = playNow
        upNextCancel = cancel
        upNextClose = close
        // PlayerView can restore focus to its last controller button during the
        // same traversal. Hide it and request overlay focus after attachment.
        playerView.hideController()
        card.post {
            val requested = playNow.requestFocus()
            card.post {
                recordOverlayTrace(
                    event = "up_next_focus",
                    detail = "shown=true requested=play_now accepted=$requested actual=${overlayFocusLabel()}"
                )
            }
        }
    }

    private fun performUpNextAction(action: UpNextOverlayAction, next: com.piggie.tv.data.models.MediaItem) {
        val effect = NextEpisodeAutoplayPolicy.effect(action)
        recordOverlayTrace(
            event = "up_next_action",
            detail = "action=${action.name} actual=${overlayFocusLabel()} cancel=${effect.cancelAutoplay} dismissOnly=${effect.dismissOnly}"
        )
        when {
            effect.trigger != null -> transitionTo(next, effect.trigger)
            effect.cancelAutoplay -> {
                autoplayCanceled = true
                PTVLog.i("Autoplay canceled item=${PTVLog.mask(itemId)} action=${action.name}")
                PtvDiagnosticsManager.recordPlayback(PtvPlaybackTrace(itemId = itemId, event = "autoplay_canceled"))
                removeUpNextOverlay()
                showTemporaryStatus("Autoplay canceled for this episode")
                Toast.makeText(this, "Autoplay canceled for this episode", Toast.LENGTH_SHORT).show()
            }
            effect.dismissOnly -> {
                overlayDismissed = true
                PtvDiagnosticsManager.recordPlayback(
                    PtvPlaybackTrace(itemId = itemId, event = "up_next_closed", detail = "autoplay continues")
                )
                removeUpNextOverlay()
            }
        }
    }

    private fun compactButton(label: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSizeRes(R.dimen.tv_nav_text_size)
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundResource(if (primary) R.drawable.tv_button_primary else R.drawable.tv_button_secondary)
        setOnClickListener { action() }
    }

    private fun removeUpNextOverlay() {
        (upNextOverlay?.parent as? ViewGroup)?.removeView(upNextOverlay)
        upNextOverlay = null
        countdownText = null
        upNextPlayNow = null
        upNextCancel = null
        upNextClose = null
    }

    private fun resetNextEpisodeState() {
        removeUpNextOverlay()
        nextEpisode = null
        nextLookupInFlight = false
        nextLookupCompleted = false
        nextCallbacks.clear()
        overlayWasShown = false
        overlayDismissed = false
        autoplayCanceled = false
        bufferingEvents = 0
    }

    private fun showStatus(message: String) {
        handler.removeCallbacks(hideTemporaryStatus)
        statusView.text = message
        statusView.isVisible = true
    }

    private fun showTemporaryStatus(message: String) {
        showStatus(message)
        handler.postDelayed(hideTemporaryStatus, CANCEL_CONFIRMATION_MS)
    }

    override fun onResume() {
        super.onResume()
        activityForeground = true
    }

    override fun onPause() {
        activityForeground = false
        player.pause()
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (upNextOverlay != null && handleUpNextKey(keyCode)) return true
        if (playbackError && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            prepareItem(itemId, lastKnownPositionMs * TICKS_PER_MILLISECOND)
            return true
        }
        if (playerView.isControllerFullyVisible) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                playerView.hideController()
                return true
            }
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            playerView.showController()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleUpNextKey(keyCode: Int): Boolean {
        val playNow = upNextPlayNow ?: return false
        val cancel = upNextCancel ?: return false
        val close = upNextClose ?: return false
        recordOverlayTrace(
            event = "up_next_input",
            detail = "key=${KeyEvent.keyCodeToString(keyCode)} actual=${overlayFocusLabel()}"
        )
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                playNow.requestFocus()
                recordOverlayFocusResult("left")
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                cancel.requestFocus()
                recordOverlayFocusResult("right")
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                close.requestFocus()
                recordOverlayFocusResult("down")
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                playNow.requestFocus()
                recordOverlayFocusResult("up")
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (currentFocus) {
                    cancel -> cancel.performClick()
                    close -> close.performClick()
                    else -> playNow.performClick()
                }
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                performUpNextAction(UpNextOverlayAction.CLOSE, nextEpisode ?: return true)
                true
            }
            else -> false
        }
    }

    private fun recordOverlayFocusResult(input: String) {
        upNextOverlay?.post {
            recordOverlayTrace(
                event = "up_next_focus",
                detail = "input=$input actual=${overlayFocusLabel()}"
            )
        }
    }

    private fun recordOverlayTrace(event: String, detail: String) {
        PtvDiagnosticsManager.recordPlayback(PtvPlaybackTrace(itemId = itemId, event = event, detail = detail))
        PTVLog.i("Up Next $event $detail item=${PTVLog.mask(itemId)}")
    }

    private fun overlayFocusLabel(): String = when (currentFocus) {
        upNextPlayNow -> "up_next_play_now"
        upNextCancel -> "up_next_cancel"
        upNextClose -> "up_next_close"
        null -> "none"
        else -> "outside_overlay:${currentFocus?.javaClass?.simpleName}"
    }

    private fun showAudioSelection(
        item: com.piggie.tv.data.models.MediaItem,
        opener: View
    ) {
        val options = listOf("Default / Auto") + item.audioTracks.map { track ->
            buildList {
                track.language?.takeIf(String::isNotBlank)?.let(::add)
                track.title
                    ?.takeIf(String::isNotBlank)
                    ?.takeUnless { it.equals(track.language, ignoreCase = true) }
                    ?.let(::add)
                if (isEmpty()) add("Unknown")
                track.codec?.takeIf(String::isNotBlank)?.let(::add)
                track.channels?.let { add("$it ch") }
                if (track.isDefault) add("Default")
            }.joinToString(" · ")
        }
        val selected = audioIndex?.let { index ->
            item.audioTracks.indexOfFirst { it.index == index }.takeIf { it >= 0 }?.plus(1)
        } ?: 0
        val startedAt = SystemClock.elapsedRealtime()
        val dialog = PtvSelectionDialog(
            this,
            "Audio",
            options,
            selectedIndex = selected,
            defaultIndex = 0,
            restoreFocusTo = opener
        ) { choice ->
            switchAudio(if (choice == 0) null else item.audioTracks[choice - 1].index)
            recordPlayerDialog("audio", startedAt)
        }
        showPlayerSelectionDialog(dialog, opener)
    }

    private fun showSubtitleSelection(
        item: com.piggie.tv.data.models.MediaItem,
        opener: View
    ) {
        val options = listOf("Default / Auto", "Off") + item.subtitleTracks.map { track ->
            buildList {
                track.language?.takeIf(String::isNotBlank)?.let(::add)
                track.title
                    ?.takeIf(String::isNotBlank)
                    ?.takeUnless { it.equals(track.language, ignoreCase = true) }
                    ?.let(::add)
                if (isEmpty()) add("Unknown")
                track.codec?.takeIf(String::isNotBlank)?.let(::add)
                if (track.isForced) add("Forced")
                if (track.isDefault) add("Default")
                track.type?.takeIf(String::isNotBlank)?.let(::add)
            }.joinToString(" · ")
        }
        val selected = when (subtitleMode) {
            SubtitleSelectionMode.DEFAULT -> 0
            SubtitleSelectionMode.OFF -> 1
            SubtitleSelectionMode.TRACK -> item.subtitleTracks
                .indexOfFirst { it.index == subtitleIndex }
                .takeIf { it >= 0 }
                ?.plus(2)
                ?: 0
        }
        val startedAt = SystemClock.elapsedRealtime()
        val dialog = PtvSelectionDialog(
            this,
            "Subtitles",
            options,
            selectedIndex = selected,
            defaultIndex = 0,
            restoreFocusTo = opener
        ) { choice ->
            when (choice) {
                0 -> switchSubtitle(SubtitleSelectionMode.DEFAULT, null)
                1 -> switchSubtitle(SubtitleSelectionMode.OFF, null)
                else -> switchSubtitle(
                    SubtitleSelectionMode.TRACK,
                    item.subtitleTracks[choice - 2].index
                )
            }
            recordPlayerDialog("subtitles", startedAt)
        }
        showPlayerSelectionDialog(dialog, opener)
    }

    private fun showConnectionSpeedSelection(opener: View) {
        val options = ConnectionSpeed.entries
        val current = ConnectionSpeed.fromStored(settings.connectionSpeed)
        val method = currentStream?.method ?: "Negotiating"
        val startedAt = SystemClock.elapsedRealtime()
        val dialog = PtvSelectionDialog(
            this,
            "Connection Speed · $method",
            options.map { it.displayLabel },
            selectedIndex = options.indexOf(current),
            defaultIndex = options.indexOf(ConnectionSpeed.AUTO),
            restoreFocusTo = opener
        ) { choice ->
            settings.connectionSpeed = options[choice].wireValue
            updateControlState()
            recordPlayerDialog("connection_speed", startedAt)
            restartPlayback()
        }
        showPlayerSelectionDialog(dialog, opener)
    }

    /**
     * Media3 normally starts its controller auto-hide timer while a modal dialog owns focus.
     * Keep the controller stable for the dialog lifetime, then restore its normal timeout and the
     * exact independent control that opened the dialog.
     */
    private fun showPlayerSelectionDialog(dialog: PtvSelectionDialog, opener: View) {
        handler.removeCallbacks(restorePlayerDialogControllerTimeout)
        pendingPlayerDialogTimeoutRestoreToken = null
        val dialogToken = playerDialogTimeoutState.begin(playerView.controllerShowTimeoutMs)
        playerView.controllerShowTimeoutMs = 0
        playerView.showController()
        dialog.setOnDismissListener {
            handler.postDelayed({
                if (
                    !destroyed &&
                    ::playerView.isInitialized &&
                    playerDialogTimeoutState.isCurrent(dialogToken)
                ) {
                    // Timeout restoration is independent of opener validity. The controller must
                    // never be left at the dialog-lifetime value of zero.
                    playerView.controllerShowTimeoutMs = PLAYER_DIALOG_RESTORE_TIMEOUT_MS
                    playerView.showController()
                    if (
                        opener.isAttachedToWindow &&
                        opener.visibility == View.VISIBLE &&
                        opener.isEnabled
                    ) {
                        opener.requestFocus()
                    }
                    pendingPlayerDialogTimeoutRestoreToken = dialogToken
                    handler.removeCallbacks(restorePlayerDialogControllerTimeout)
                    handler.postDelayed(
                        restorePlayerDialogControllerTimeout,
                        PLAYER_DIALOG_RESTORE_TIMEOUT_MS.toLong()
                    )
                }
            }, PLAYER_DIALOG_WINDOW_SETTLE_MS)
        }
        dialog.show()
    }

    private fun recordPlayerDialog(kind: String, startedAt: Long) {
        PtvDiagnosticsManager.event(
            "player_controls",
            "${kind}_selected",
            mapOf(
                "item" to PTVLog.mask(itemId),
                "connectionSpeed" to settings.connectionSpeed,
                "playMethod" to (currentStream?.method ?: "unknown"),
                "audioIndex" to (audioIndex?.toString() ?: "default"),
                "subtitleMode" to subtitleMode.name,
                "subtitleIndex" to (subtitleIndex?.toString() ?: "none"),
                "dialogMs" to (SystemClock.elapsedRealtime() - startedAt).toString(),
                "positionMs" to player.currentPosition.coerceAtLeast(0L).toString()
            )
        )
    }

    private fun switchAudio(index: Int?) {
        audioIndex = index
        PendingPlaybackPreferences.setAudio(itemId, index)
        updateControlState()
        restartPlayback()
    }

    private fun switchSubtitle(mode: SubtitleSelectionMode, index: Int?) {
        subtitleMode = mode
        subtitleIndex = index.takeIf { mode == SubtitleSelectionMode.TRACK }
        PendingPlaybackPreferences.setSubtitle(itemId, mode, subtitleIndex)
        updateControlState()
        restartPlayback()
    }

    private fun restartPlayback() {
        if (!transitionInFlight.compareAndSet(false, true)) return
        val positionTicks = PlayerRestartPolicy.positionTicks(player.currentPosition)
        val currentId = itemId
        val currentSession = playSessionId.orEmpty()
        val generation = requestGeneration.get()
        stopProgressLoops()
        player.pause()
        launchApiWork(WORK_RESTART, "ptv-player-restart") {
            reportStoppedBlockingOnce(currentId, currentSession, positionTicks)
            // A restart is a new Jellyfin session for the same item.
            stoppedItems.remove(currentId)
            runOnUiThread {
                if (!destroyed && generation == requestGeneration.get()) {
                    prepareItem(currentId, positionTicks)
                }
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        requestGeneration.incrementAndGet()
        handler.removeCallbacks(restorePlayerDialogControllerTimeout)
        pendingPlayerDialogTimeoutRestoreToken = null
        playerDialogTimeoutState.clear()
        stopProgressLoops()
        removeUpNextOverlay()
        if (::player.isInitialized) {
            lastKnownPositionMs = player.currentPosition.coerceAtLeast(lastKnownPositionMs)
        }
        cancelAllApiWork()
        mediaSession?.release()
        mediaSession = null
        if (::player.isInitialized) player.release()
        if (::itemId.isInitialized) {
            PtvDiagnosticsManager.recordPlayback(PtvPlaybackTrace(itemId = itemId, event = "player_released"))
        }
        super.onDestroy()
    }

    companion object {
        internal const val VIDEO_MEDIA_SESSION_ID = "ptv-video-session"
        private const val EXTRA_ITEM_ID = "extra_item_id"
        private const val EXTRA_START_TICKS = "extra_start_ticks"
        private const val EXTRA_AUDIO_INDEX = "extra_audio_index"
        private const val EXTRA_SUBTITLE_INDEX = "extra_subtitle_index"
        private const val EXTRA_SUBTITLE_MODE = "extra_subtitle_mode"
        private const val REPORTING_INTERVAL_MS = 15_000L
        private const val COUNTDOWN_TICK_MS = 500L
        private const val NEXT_LOOKUP_HEAD_START_MS = 5_000L
        private const val TICKS_PER_MILLISECOND = 10_000L
        private const val CANCEL_CONFIRMATION_MS = 8_000L
        private const val PLAYER_DIALOG_WINDOW_SETTLE_MS = 100L
        private const val PLAYER_DIALOG_RESTORE_TIMEOUT_MS = 10_000
        private const val WORK_NEGOTIATION = "negotiation"
        private const val WORK_NEXT_LOOKUP = "next_lookup"
        private const val WORK_NEXT_TRANSITION = "next_transition"
        private const val WORK_RESTART = "restart"

        fun start(
            context: Context,
            itemId: String,
            startTicks: Long,
            preference: PendingPlaybackPreference
        ) {
            start(
                context,
                itemId,
                startTicks,
                preference.audioIndex,
                preference.subtitleIndex,
                preference.subtitleMode
            )
        }

        fun start(
            context: Context,
            itemId: String,
            startTicks: Long = 0,
            audioIndex: Int? = null,
            subtitleIndex: Int? = null,
            subtitleMode: SubtitleSelectionMode =
                if (subtitleIndex != null) SubtitleSelectionMode.TRACK else SubtitleSelectionMode.DEFAULT
        ) {
            context.startActivity(Intent(context, VideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_ITEM_ID, itemId)
                putExtra(EXTRA_START_TICKS, startTicks)
                if (audioIndex != null) putExtra(EXTRA_AUDIO_INDEX, audioIndex)
                if (subtitleIndex != null) putExtra(EXTRA_SUBTITLE_INDEX, subtitleIndex)
                putExtra(EXTRA_SUBTITLE_MODE, subtitleMode.name)
            })
        }
    }
}
