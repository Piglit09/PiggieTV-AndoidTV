package com.piggie.tv.ui.music

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import coil.load
import coil.transform.CircleCropTransformation
import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.playback.MusicPlaybackManager
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.ui.shared.TextSanitizer
import com.piggie.tv.util.dim
import com.piggie.tv.util.dimFloat
import com.piggie.tv.util.setTextSizeRes
import kotlin.concurrent.thread

class MusicDetailsActivity : AppCompatActivity() {
    private val api by lazy { JellyfinNativeApi(this) }
    private val store by lazy { SecureSessionStore(this) }
    private lateinit var session: NativeSession
    private lateinit var itemId: String
    private var item: MediaItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = store.read() ?: run { finish(); return }
        itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: run { finish(); return }
        
        setupInitialView()
        loadDetails()
    }

    private fun setupInitialView() {
        val root = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.tv_app_background)
        }
        val loading = TextView(this).apply {
            text = "Loading music details..."
            textSize = 18f
            setTextColor(getColor(R.color.tv_text_secondary))
            gravity = Gravity.CENTER
        }
        root.addView(loading)
        setContentView(root)
    }

    private fun loadDetails() {
        thread(start = true) {
            runCatching {
                val details = api.loadItem(session, itemId)
                val songs = when(details.type) {
                    "MusicAlbum" -> api.loadAlbumSongs(session, details.id)
                    "MusicArtist" -> api.loadArtistSongs(session, details.id)
                    "Playlist" -> api.loadPlaylistSongs(session, details.id)
                    else -> emptyList()
                }
                details to songs
            }.onSuccess { (details, songs) ->
                runOnUiThread {
                    item = details
                    renderDetails(details, songs)
                }
            }.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(this, error.message ?: "Error loading music details", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderDetails(item: MediaItem, songs: List<MediaItem>) {
        val root = FrameLayout(this)
        
        val backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            load(api.backdropUrl(session, item, 1920)) {
                crossfade(true)
            }
        }
        val gradient = View(this).apply {
            setBackgroundResource(R.drawable.hero_gradient_overlay)
        }
        root.addView(backdrop)
        root.addView(gradient)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dim(R.dimen.tv_spacing_large))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dim(R.dimen.tv_screen_margin_horizontal), dim(R.dimen.tv_screen_margin_vertical), dim(R.dimen.tv_screen_margin_horizontal), dim(R.dimen.tv_spacing_large))
        }
        scroll.addView(content)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            load(api.imageUrl(session, item, MediaCardPresentation.SQUARE)) {
                crossfade(true)
                if (item.type == "MusicArtist") transformations(CircleCropTransformation())
            }
        }
        headerRow.addView(art, LinearLayout.LayoutParams(dim(R.dimen.tv_hero_height) / 2, dim(R.dimen.tv_hero_height) / 2))

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dim(R.dimen.tv_spacing_large), 0, 0, 0)
        }
        titleCol.addView(label(item.title, dimFloat(R.dimen.tv_text_size_hero_title), R.color.tv_text_primary, true, isPx = true))
        titleCol.addView(label(item.albumArtist ?: item.artists.firstOrNull() ?: "Artist", dimFloat(R.dimen.tv_text_size_hero_meta), R.color.tv_text_secondary, margin = 8, isPx = true))
        
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dim(R.dimen.tv_spacing_medium), 0, 0)
        }
        actions.addView(actionButton("Play All") { MusicPlaybackManager.play(this, session, songs) })
        actions.addView(actionButton("Shuffle", true) { MusicPlaybackManager.play(this, session, songs.shuffled()) }.apply { layoutParams = LinearLayout.LayoutParams(-2, dim(R.dimen.tv_nav_button_height) + dim(R.dimen.tv_spacing_small)).apply { marginStart = dim(R.dimen.tv_spacing_medium) } })
        
        titleCol.addView(actions)
        headerRow.addView(titleCol)
        content.addView(headerRow)

        if (songs.isNotEmpty()) {
            content.addView(label("Tracks", dimFloat(R.dimen.tv_text_size_section_title), R.color.tv_text_primary, true, margin = 32, isPx = true))
            songs.forEachIndexed { index, song ->
                content.addView(createTrackItem(song, songs, index), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dim(R.dimen.tv_spacing_small) })
            }
        }

        root.addView(scroll)
        setContentView(root)
    }

    private fun createTrackItem(song: MediaItem, allSongs: List<MediaItem>, index: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundResource(R.drawable.tv_button_secondary)
            setPadding(dim(R.dimen.tv_spacing_medium), dim(R.dimen.tv_spacing_small), dim(R.dimen.tv_spacing_medium), dim(R.dimen.tv_spacing_small))
            setOnClickListener { MusicPlaybackManager.play(this@MusicDetailsActivity, session, allSongs, index) }
            setOnFocusChangeListener { view, focused ->
                view.animate().scaleX(if (focused) 1.01f else 1f).setDuration(120).start()
            }
        }

        row.addView(label((index + 1).toString(), 16f, R.color.tv_text_secondary).apply {
            minWidth = dim(R.dimen.tv_spacing_large)
            gravity = Gravity.CENTER
        })

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dim(R.dimen.tv_spacing_medium), 0, 0, 0)
        }
        info.addView(label(song.title, 16f, R.color.tv_text_primary, bold = true))
        info.addView(label(song.artists.firstOrNull() ?: "Artist", 14f, R.color.tv_text_secondary))
        
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

        val duration = song.runtimeTicks.takeIf { it > 0 }?.let { ticks ->
            val totalSeconds = ticks / 10_000_000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            "$minutes:${seconds.toString().padStart(2, '0')}"
        } ?: ""
        row.addView(label(duration, 14f, R.color.tv_text_secondary))

        return row
    }

    private fun actionButton(title: String, secondary: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = title
            setTextSizeRes(R.dimen.tv_nav_text_size)
            isAllCaps = false
            setTextColor(getColor(R.color.tv_text_primary))
            setBackgroundResource(if (secondary) R.drawable.tv_button_secondary else R.drawable.tv_button_primary)
            setPadding(dim(R.dimen.tv_spacing_medium), 0, dim(R.dimen.tv_spacing_medium), 0)
            setOnClickListener { onClick() }
        }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false, margin: Int = 0, isPx: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            if (isPx) setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size) else textSize = size
            setTextColor(getColor(color))
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            if (margin > 0) layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = margin }
        }

    companion object {
        private const val EXTRA_ITEM_ID = "extra_item_id"
        fun start(context: Context, itemId: String) {
            context.startActivity(Intent(context, MusicDetailsActivity::class.java).apply {
                putExtra(EXTRA_ITEM_ID, itemId)
            })
        }
    }
}
