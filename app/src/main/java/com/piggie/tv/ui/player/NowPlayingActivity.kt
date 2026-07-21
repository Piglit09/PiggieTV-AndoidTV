package com.piggie.tv.ui.player

import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.playback.MusicPlaybackManager
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes
import com.piggie.tv.util.sp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NowPlayingActivity : AppCompatActivity() {
    private val playback = MusicPlaybackManager
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var art: ImageView
    private lateinit var playPause: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupView()
        observePlayback()
    }

    private fun setupView() {
        val root = FrameLayout(this).apply { setBackgroundResource(R.drawable.tv_app_background) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dim(R.dimen.tv_screen_margin_horizontal), dim(R.dimen.tv_screen_margin_vertical), dim(R.dimen.tv_screen_margin_horizontal), dim(R.dimen.tv_screen_margin_vertical))
        }
        root.addView(content)

        art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val artSize = dim(R.dimen.tv_hero_height) * 0.75
        content.addView(art, LinearLayout.LayoutParams(artSize.toInt(), artSize.toInt()))

        title = label("", sp(R.dimen.tv_text_size_hero_title), R.color.tv_text_primary, true, margin = 24).apply { gravity = Gravity.CENTER }
        content.addView(title)

        artist = label("", sp(R.dimen.tv_text_size_hero_body), R.color.tv_text_secondary, margin = 8).apply { gravity = Gravity.CENTER }
        content.addView(artist)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dim(R.dimen.tv_spacing_large), 0, 0)
        }
        
        controls.addView(controlButton(android.R.drawable.ic_media_previous) { playback.previous() })
        playPause = controlButton(android.R.drawable.ic_media_play) { playback.togglePlayPause() }
        val playSize = dim(R.dimen.tv_hero_button_height) * 1.5
        controls.addView(playPause, LinearLayout.LayoutParams(playSize.toInt(), playSize.toInt()).apply { marginStart = dim(R.dimen.tv_spacing_large); marginEnd = dim(R.dimen.tv_spacing_large) })
        controls.addView(controlButton(android.R.drawable.ic_media_next) { playback.next() })
        
        content.addView(controls)

        setContentView(root)
    }

    private fun observePlayback() {
        lifecycleScope.launch {
            playback.currentTrack.collectLatest { item ->
                if (item != null) {
                    title.text = item.title
                    artist.text = item.artists.firstOrNull() ?: item.albumArtist
                    val store = SecureSessionStore(this@NowPlayingActivity)
                    val session = store.read()!!
                    val api = JellyfinNativeApi(this@NowPlayingActivity)
                    art.load(api.imageUrl(session, item, MediaCardPresentation.SQUARE)) {
                        crossfade(true)
                        placeholder(ColorDrawable(0xFF1A0A33.toInt()))
                    }
                } else {
                    finish()
                }
            }
        }
        lifecycleScope.launch {
            playback.isPlaying.collectLatest { isPlaying ->
                playPause.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            }
        }
    }

    private fun controlButton(icon: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        setBackgroundResource(R.drawable.tv_button_secondary)
        setPadding(dim(R.dimen.tv_spacing_medium), dim(R.dimen.tv_spacing_medium), dim(R.dimen.tv_spacing_medium), dim(R.dimen.tv_spacing_medium))
        setOnClickListener { onClick() }
        isFocusable = true
        setOnFocusChangeListener { view, focused ->
            view.animate().scaleX(if (focused) 1.05f else 1f).scaleY(if (focused) 1.05f else 1f).start()
        }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false, margin: Int = 0): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(getColor(color))
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            if (margin > 0) layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = margin }
        }
}
