package com.piggie.tv.ui.player

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
import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.playback.PlaybackProgress
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.ui.shared.TextSanitizer
import com.piggie.tv.util.dim
import com.piggie.tv.util.dimFloat
import com.piggie.tv.util.setTextSizeRes
import kotlin.concurrent.thread

class MediaDetailsActivity : AppCompatActivity() {
    private val api by lazy { JellyfinNativeApi(this) }
    private val store by lazy { SecureSessionStore(this) }
    private lateinit var session: NativeSession
    private lateinit var itemId: String
    private var item: MediaItem? = null
    private var episodesContainer: LinearLayout? = null
    private var currentSeasonId: String? = null
    private var overviewExpanded = false

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
            text = "Loading details..."
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
                val nextUp = if (details.type == "Series") api.loadNextUpForSeries(session, details.id) else null
                details to nextUp
            }.onSuccess { (details, nextUp) ->
                runOnUiThread {
                    item = details
                    renderDetails(details, nextUp)
                }
            }.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(this, error.message ?: "Error loading details", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderDetails(item: MediaItem, nextUp: MediaItem?) {
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

        if (item.logoTag != null) {
            val logo = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_START
                adjustViewBounds = true
                load(api.logoUrl(session, item))
            }
            content.addView(logo, LinearLayout.LayoutParams(dim(R.dimen.tv_hero_logo_max_width), dim(R.dimen.tv_hero_logo_max_height)))
        } else {
            content.addView(label(item.title, dimFloat(R.dimen.tv_text_size_hero_title), R.color.tv_text_primary, true, isPx = true))
        }

        val runtime = item.runtimeTicks.takeIf { it > 0 && item.type != "Series" }?.let { (it / 10_000_000 / 60).toString() + " min" }
        val metaText = TextSanitizer.formatMetadata(
            item.productionYear,
            item.officialRating,
            runtime,
            item.genres.firstOrNull()
        )
        content.addView(label(metaText, dimFloat(R.dimen.tv_text_size_metadata), R.color.tv_text_secondary, margin = 10, isPx = true))

        val sanitizedOverview = TextSanitizer.sanitize(item.overview)
        val overview = TextView(this).apply {
            text = sanitizedOverview
            setTextSizeRes(R.dimen.tv_text_size_body)
            setTextColor(getColor(R.color.tv_text_secondary))
            setLineSpacing(0f, 1.1f)
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(dim(R.dimen.tv_hero_content_width), -2).apply { topMargin = dim(R.dimen.tv_spacing_medium) }
            
            setOnClickListener {
                overviewExpanded = !overviewExpanded
                maxLines = if (overviewExpanded) 100 else 4
            }
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundResource(R.drawable.tv_button_secondary_transparent)
            setPadding(dim(R.dimen.tv_spacing_small), dim(R.dimen.tv_spacing_small), dim(R.dimen.tv_spacing_small), dim(R.dimen.tv_spacing_small))
        }
        content.addView(overview)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dim(R.dimen.tv_spacing_medium), 0, 0)
        }
        
        if (item.type == "Series") {
            if (nextUp != null) {
                val actionPrefix = if (nextUp.playbackPositionTicks > 0) "Resume " else "Play Next "
                val epInfo = TextSanitizer.formatMetadata(nextUp.episodeLabel, nextUp.title)
                actions.addView(actionButton(actionPrefix + epInfo) { VideoPlayerActivity.start(this, nextUp.id, nextUp.playbackPositionTicks) })
            } else {
                actions.addView(actionButton("Start Series") { /* Start S1E1 logic */ })
            }
            actions.addView(playedButton(item))
            actions.addView(favoriteButton(item))
            content.addView(actions)
            loadSeriesContent(content, item)
        } else if (item.type == "Book") {
            val resumeTicks = item.playbackPositionTicks
            if (resumeTicks > 0) {
                actions.addView(actionButton("Resume Reading") { ReaderActivity.start(this, itemId) })
            } else {
                actions.addView(actionButton("Read") { ReaderActivity.start(this, itemId) })
            }
            actions.addView(favoriteButton(item))
            content.addView(actions)
        } else {
            val resumeTicks = item.playbackPositionTicks
            if (resumeTicks > 0) {
                actions.addView(actionButton("Resume") { startPlayback(itemId, resumeTicks) })
                actions.addView(actionButton("Restart", secondary = true) { startPlayback(itemId, 0L) }, LinearLayout.LayoutParams(-2, dim(R.dimen.tv_hero_button_height)).apply { marginStart = dim(R.dimen.tv_spacing_medium) })
            } else {
                actions.addView(actionButton("Play") { startPlayback(itemId, 0L) })
            }
            actions.addView(playedButton(item))
            actions.addView(favoriteButton(item))
            content.addView(actions)
        }

        root.addView(scroll)
        setContentView(root)
    }

    private fun playedButton(item: MediaItem) = actionButton(if (item.isPlayed) "Played" else "Mark Played", secondary = true) {
        val newState = !item.isPlayed
        thread {
            api.setPlayed(session, item.id, newState)
            runOnUiThread { loadDetails() }
        }
    }.apply { layoutParams = LinearLayout.LayoutParams(-2, dim(R.dimen.tv_hero_button_height)).apply { marginStart = dim(R.dimen.tv_spacing_medium) } }

    private fun favoriteButton(item: MediaItem) = actionButton(if (item.isFavorite) "Unfavorite" else "Favorite", secondary = true) {
        val newState = !item.isFavorite
        thread {
            api.setFavorite(session, item.id, newState)
            runOnUiThread { loadDetails() }
        }
    }.apply { layoutParams = LinearLayout.LayoutParams(-2, dim(R.dimen.tv_hero_button_height)).apply { marginStart = dim(R.dimen.tv_spacing_medium) } }

    private fun loadSeriesContent(parent: LinearLayout, series: MediaItem) {
        thread(start = true) {
            runCatching {
                api.loadSeasons(session, series.id)
            }.onSuccess { seasons ->
                runOnUiThread {
                    if (seasons.isNotEmpty()) {
                        addSeasonsRail(parent, seasons)
                    }
                }
            }
        }
    }

    private fun addSeasonsRail(parent: LinearLayout, seasons: List<MediaItem>) {
        parent.addView(label("Seasons", dimFloat(R.dimen.tv_text_size_section_title), R.color.tv_text_primary, true, margin = 32, isPx = true))
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dim(R.dimen.tv_spacing_small), 0, 0)
        }
        seasons.forEach { season ->
            val seasonButton = Button(this).apply {
                text = season.title
                isAllCaps = false
                setTextSizeRes(R.dimen.tv_nav_text_size)
                setBackgroundResource(R.drawable.tv_button_secondary)
                setTextColor(getColor(R.color.tv_text_primary))
                setOnClickListener { selectSeason(parent, season) }
            }
            container.addView(seasonButton, LinearLayout.LayoutParams(-2, dim(R.dimen.tv_nav_button_height)).apply { marginEnd = dim(R.dimen.tv_spacing_small) })
        }
        parent.addView(container)
        
        if (seasons.isNotEmpty()) selectSeason(parent, seasons.first())
    }

    private fun selectSeason(parent: LinearLayout, season: MediaItem) {
        if (currentSeasonId == season.id) return
        currentSeasonId = season.id
        
        episodesContainer?.let { parent.removeView(it) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dim(R.dimen.tv_spacing_medium), 0, 0)
        }
        episodesContainer = container
        parent.addView(container)
        
        container.addView(label(season.title + " Episodes", 18f, R.color.tv_text_primary, true))
        val loadingLabel = label("Loading episodes...", 16f, R.color.tv_text_secondary, margin = 12)
        container.addView(loadingLabel)

        thread(start = true) {
            runCatching {
                api.loadEpisodes(session, itemId, season.id)
            }.onSuccess { episodes ->
                if (currentSeasonId != season.id) return@onSuccess
                runOnUiThread {
                    container.removeView(loadingLabel)
                    if (episodes.isEmpty()) {
                        container.addView(label("No episodes found.", 16f, R.color.tv_text_secondary, margin = 12))
                    }
                    episodes.forEach { episode ->
                        container.addView(createEpisodeCard(episode), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dim(R.dimen.tv_spacing_small) })
                    }
                }
            }.onFailure { error ->
                runOnUiThread {
                    loadingLabel.text = error.message ?: "Unknown error"
                }
            }
        }
    }

    private fun createEpisodeCard(episode: MediaItem): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundResource(R.drawable.tv_button_secondary)
            setPadding(dim(R.dimen.tv_spacing_small), dim(R.dimen.tv_spacing_small), dim(R.dimen.tv_spacing_small), dim(R.dimen.tv_spacing_small))
            setOnClickListener { startPlayback(episode.id, episode.playbackPositionTicks) }
            setOnFocusChangeListener { view, focused ->
                view.animate().scaleX(if (focused) 1.01f else 1f).setDuration(120).start()
            }
        }
        
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            load(api.imageUrl(session, episode, MediaCardPresentation.LANDSCAPE)) {
                placeholder(ColorDrawable(0xFF1A0A33.toInt()))
                crossfade(true)
            }
        }
        card.addView(thumb, LinearLayout.LayoutParams(dim(R.dimen.tv_landscape_width) / 2, dim(R.dimen.tv_landscape_height) / 2))
        
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dim(R.dimen.tv_spacing_medium), 0, 0, 0)
        }
        val titleLine = TextSanitizer.formatMetadata(episode.episodeLabel, episode.title)
        info.addView(label(titleLine, 15f, R.color.tv_text_primary, bold = true))
        
        val duration = episode.runtimeTicks.takeIf { it > 0 }?.let { (it / 10_000_000 / 60).toString() + " min" }
        val progress = PlaybackProgress.fraction(episode.playbackPositionTicks, episode.runtimeTicks)
        val progressText = if (progress > 0) "  •  " + (progress * 100).toInt() + "% watched" else ""
        info.addView(label((duration ?: "") + progressText, 13f, R.color.tv_text_secondary, margin = 4))
        
        val overviewText = TextSanitizer.sanitize(episode.overview)
        if (overviewText.isNotBlank()) {
            val overview = TextView(this).apply {
                text = overviewText
                setTextSizeRes(R.dimen.tv_text_size_metadata)
                setTextColor(getColor(R.color.tv_text_secondary))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dim(R.dimen.tv_spacing_small) }
            }
            info.addView(overview)
        }
        
        card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        return card
    }

    private fun startPlayback(id: String, positionTicks: Long) {
        VideoPlayerActivity.start(this, id, positionTicks)
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
        const val EXTRA_ITEM_ID = "extra_item_id"
        fun start(context: Context, itemId: String) {
            context.startActivity(Intent(context, MediaDetailsActivity::class.java).apply {
                putExtra(EXTRA_ITEM_ID, itemId)
            })
        }
    }
}
