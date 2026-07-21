package com.piggie.tv.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.playback.MusicPlaybackManager
import com.piggie.tv.data.playback.PlaybackNegotiator
import com.piggie.tv.data.session.NativeSettings
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.ui.shared.TextSanitizer
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes
import okhttp3.OkHttpClient
import kotlin.concurrent.thread

@OptIn(UnstableApi::class)
class VideoPlayerActivity : AppCompatActivity() {
    private val api by lazy { JellyfinNativeApi(this) }
    private val store by lazy { SecureSessionStore(this) }
    private val settings by lazy { NativeSettings(this) }
    private val negotiator by lazy { PlaybackNegotiator(api, settings) }
    private lateinit var session: NativeSession
    private lateinit var itemId: String
    private var playSessionId: String? = null
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var reportingHandler = Handler(Looper.getMainLooper())
    private val reportingIntervalMs = 15_000L
    private var currentItem: com.piggie.tv.data.models.MediaItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_video_player)
        playerView = findViewById(R.id.player_view)
        
        MusicPlaybackManager.stop()
        
        session = store.read() ?: run { finish(); return }
        itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: run { finish(); return }
        val startTicks = intent.getLongExtra(EXTRA_START_TICKS, 0L)
        Log.d("VideoPlayer", "Starting player for itemId=$itemId ticks=$startTicks")
        
        loadMetadata()
        initializePlayer(startTicks)
    }

    private fun loadMetadata() {
        thread(start = true) {
            runCatching { api.loadItem(session, itemId) }.onSuccess { details ->
                currentItem = details
                runOnUiThread {
                    findViewById<TextView>(R.id.player_title)?.text = details.title
                    findViewById<TextView>(R.id.player_subtitle)?.text = details.seriesName ?: details.year
                }
            }
        }
    }

    private fun initializePlayer(startTicks: Long) {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", api.authorization(session.token))
                    .addHeader("X-Emby-Authorization", api.authorization(session.token))
                    .addHeader("X-MediaBrowser-Token", session.token)
                    .build()
                chain.proceed(request)
            }
            .build()

        val dataSourceFactory = OkHttpDataSource.Factory(client)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                playWhenReady = true
                playerView.player = this
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            startReporting()
                        } else if (state == Player.STATE_ENDED) {
                            handlePlaybackEnded()
                        }
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Toast.makeText(this@VideoPlayerActivity, "Playback error: ${error.message}", Toast.LENGTH_LONG).show()
                        finish()
                    }
                })
            }

        thread(start = true) {
            runCatching {
                negotiator.getPlaybackStream(session, itemId, startTicks)
            }.onSuccess { stream ->
                playSessionId = stream.playSessionId
                Log.d("VideoPlayer", "Negotiated stream URL: ${stream.url} (PlaySessionId=$playSessionId)")
                runOnUiThread {
                    val mediaItem = MediaItem.Builder()
                        .setMediaId(itemId)
                        .setUri(stream.url)
                        .build()
                    player?.setMediaItem(mediaItem, startTicks / 10_000)
                    player?.prepare()
                    player?.play()
                    api.reportPlaying(session, itemId, playSessionId ?: "", startTicks)
                }
            }.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(this, "Failed to negotiate stream: ${error.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private val reportRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            val pos = p.currentPosition
            val ticks = pos * 10_000
            val paused = !p.playWhenReady
            api.reportProgress(session, itemId, playSessionId ?: "", ticks, paused)
            reportingHandler.postDelayed(this, reportingIntervalMs)
        }
    }

    private fun startReporting() {
        reportingHandler.removeCallbacks(reportRunnable)
        reportingHandler.postDelayed(reportRunnable, reportingIntervalMs)
    }

    private fun handlePlaybackEnded() {
        reportingHandler.removeCallbacks(reportRunnable)
        val finalPosTicks = (player?.currentPosition ?: 0L) * 10_000
        thread(start = true) {
            runCatching { api.reportStopped(session, itemId, playSessionId ?: "", finalPosTicks) }
            
            runCatching { 
                val current = api.loadItem(session, itemId)
                if (current.type == "Episode") {
                    val next = api.loadNextUpForSeries(session, current.seriesId ?: "")
                    if (next != null && next.id != itemId) {
                        runOnUiThread { promptNextEpisode(next) }
                        return@thread
                    }
                }
                runOnUiThread { finish() }
            }.onFailure { runOnUiThread { finish() } }
        }
    }

    private fun promptNextEpisode(next: com.piggie.tv.data.models.MediaItem) {
        val root = findViewById<ViewGroup>(android.R.id.content)
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC1A0A33.toInt())
            isFocusable = true
            isClickable = true
        }
        overlay.addView(TextView(this).apply {
            text = "Up Next"
            setTextSizeRes(R.dimen.tv_text_size_page_title)
            setTextColor(0xFFFFFFFF.toInt())
        })
        overlay.addView(TextView(this).apply {
            text = TextSanitizer.formatMetadata(next.episodeLabel, next.title)
            setTextSizeRes(R.dimen.tv_text_size_section_title)
            setTextColor(0xFFBBBBBB.toInt())
            setPadding(0, dim(R.dimen.tv_spacing_medium), 0, dim(R.dimen.tv_spacing_large))
        })
        val playBtn = Button(this).apply {
            text = "Play Now"
            setTextSizeRes(R.dimen.tv_nav_text_size)
            isAllCaps = false
            setBackgroundResource(R.drawable.tv_button_primary)
            setOnClickListener {
                (overlay.parent as? ViewGroup)?.removeView(overlay)
                itemId = next.id
                initializePlayer(0L)
                loadMetadata()
            }
        }
        overlay.addView(playBtn, LinearLayout.LayoutParams(dim(R.dimen.tv_hero_button_width), dim(R.dimen.tv_nav_button_height) + dim(R.dimen.tv_spacing_small)))
        val closeBtn = Button(this).apply {
            text = "Close"
            setTextSizeRes(R.dimen.tv_nav_text_size)
            isAllCaps = false
            setBackgroundResource(R.drawable.tv_button_secondary)
            setOnClickListener { finish() }
        }
        overlay.addView(closeBtn, LinearLayout.LayoutParams(dim(R.dimen.tv_hero_button_width), dim(R.dimen.tv_nav_button_height) + dim(R.dimen.tv_spacing_small)).apply { topMargin = dim(R.dimen.tv_spacing_medium) })
        
        root.addView(overlay, ViewGroup.LayoutParams(-1, -1))
        playBtn.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        reportingHandler.removeCallbacks(reportRunnable)
        val finalPosTicks = (player?.currentPosition ?: 0L) * 10_000
        thread(start = true) {
            runCatching { api.reportStopped(session, itemId, playSessionId ?: "", finalPosTicks) }
        }
        player?.release()
        player = null
    }

    companion object {
        private const val EXTRA_ITEM_ID = "extra_item_id"
        private const val EXTRA_START_TICKS = "extra_start_ticks"
        fun start(context: Context, itemId: String, startTicks: Long = 0) {
            context.startActivity(Intent(context, VideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_ITEM_ID, itemId)
                putExtra(EXTRA_START_TICKS, startTicks)
            })
        }
    }
}
