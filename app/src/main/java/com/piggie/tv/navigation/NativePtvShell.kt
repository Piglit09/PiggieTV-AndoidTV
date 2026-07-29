package com.piggie.tv.navigation

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.dispose
import coil.load
import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.playback.MusicPlaybackManager
import com.piggie.tv.data.playback.WaveProgressModel
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import com.piggie.tv.diagnostics.PtvRedactor
import com.piggie.tv.ui.player.NowPlayingActivity
import com.piggie.tv.ui.layout.TvLayoutProfileResolver
import com.piggie.tv.ui.rendering.TvRenderingRuntime
import com.piggie.tv.ui.widgets.PtvWaveProgressView
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.util.dim
import com.piggie.tv.util.PTVLog
import com.piggie.tv.util.setTextSizeRes
import kotlinx.coroutines.launch

data class NativePtvShellHost(
    val content: FrameLayout,
    val navigation: Map<NativeRoute, Button>
)

object NativePtvShell {
    fun create(
        activity: Activity,
        selected: NativeRoute,
        onRouteSelected: (NativeRoute) -> Unit
    ): NativePtvShellHost {
        val (profile, viewport) = TvLayoutProfileResolver.from(activity)
        PTVLog.i("TV layout profile=$profile logical=${viewport.widthDp}x${viewport.heightDp}dp sw=${viewport.smallestWidthDp}dp")

        val root = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            if (TvRenderingRuntime.features().opaqueRoots) {
                setBackgroundColor(PTVColors.background)
            } else {
                setBackgroundResource(R.drawable.tv_app_background)
            }
        }

        if (TvRenderingRuntime.features().opaqueRoots) {
            activity.window.setBackgroundDrawable(null)
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(container, ViewGroup.LayoutParams(-1, -1))

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                activity.dim(R.dimen.tv_screen_margin_horizontal),
                activity.dim(R.dimen.tv_screen_margin_vertical),
                activity.dim(R.dimen.tv_screen_margin_horizontal),
                activity.dim(R.dimen.tv_spacing_medium)
            )
        }

        // 1. Logo
        header.addView(ImageView(activity).apply {
            contentDescription = activity.getString(R.string.app_name)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.app_logo)
        }, LinearLayout.LayoutParams(
            activity.dim(R.dimen.tv_header_logo_width),
            activity.dim(R.dimen.tv_header_logo_height)
        ))

        // 2. Navigation Rail (In between Logo and Profile)
        val buttons = linkedMapOf<NativeRoute, Button>()
        val rail = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val primaryRoutes = NativeRoute.entries.filter { it != NativeRoute.PROFILE }
        primaryRoutes.forEachIndexed { index, route ->
            val button = Button(activity).apply {
                id = View.generateViewId()
                text = route.label
                setTextSizeRes(R.dimen.tv_nav_text_size)
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setTextColor(activity.getColor(R.color.tv_text_primary))
                setBackgroundResource(R.drawable.tv_nav_button)
                isSelected = route == selected
                setOnClickListener { onRouteSelected(route) }
            }
            buttons[route] = button
            rail.addView(
                button,
                LinearLayout.LayoutParams(
                    activity.dim(R.dimen.tv_nav_button_width),
                    activity.dim(R.dimen.tv_nav_button_height)
                ).apply {
                    if (index < primaryRoutes.lastIndex) {
                        marginEnd = activity.dim(R.dimen.tv_nav_button_spacing)
                    }
                }
            )
        }
        
        header.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            isSmoothScrollingEnabled = false
            isFocusable = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(activity.dim(R.dimen.tv_spacing_large), 0, activity.dim(R.dimen.tv_spacing_large), 0)
            addView(rail)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 3. Profile Button
        val profileButton = Button(activity).apply {
            id = View.generateViewId()
            text = "Profile"
            setTextSizeRes(R.dimen.tv_nav_text_size)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAllCaps = false
            setTextColor(activity.getColor(R.color.tv_text_primary))
            setBackgroundResource(R.drawable.tv_nav_button)
            contentDescription = activity.getString(R.string.profile_button_description)
            isSelected = selected == NativeRoute.PROFILE
            setOnClickListener { onRouteSelected(NativeRoute.PROFILE) }
        }
        buttons[NativeRoute.PROFILE] = profileButton
        header.addView(
            profileButton,
            LinearLayout.LayoutParams(
                activity.dim(R.dimen.tv_profile_button_width),
                activity.dim(R.dimen.tv_profile_button_height)
            )
        )
        
        container.addView(header)

        val content = FrameLayout(activity).apply {
            id = R.id.ptv_content_frame
        }
        container.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        
        val miniPlayer = createMiniPlayer(activity)
        container.addView(miniPlayer)
        
        activity.setContentView(root)
        return NativePtvShellHost(content, buttons)
    }

    private fun createMiniPlayer(activity: Activity): View {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.tv_media_card_background)
            setPadding(activity.dim(R.dimen.tv_spacing_medium), activity.dim(R.dimen.tv_spacing_small), activity.dim(R.dimen.tv_spacing_medium), activity.dim(R.dimen.tv_spacing_small))
            visibility = View.GONE
            isFocusable = true
            isClickable = true
            setOnClickListener {
                activity.startActivity(Intent(activity, NowPlayingActivity::class.java))
            }
            layoutParams = LinearLayout.LayoutParams(-1, activity.dim(R.dimen.tv_mini_player_height)).apply { topMargin = activity.dim(R.dimen.tv_spacing_medium) }
        }

        val art = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val artSize = (activity.dim(R.dimen.tv_mini_player_height) * 0.8).toInt()
        container.addView(art, LinearLayout.LayoutParams(artSize, artSize))

        val info = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dim(R.dimen.tv_spacing_medium), 0, 0, 0)
        }
        val title = TextView(activity).apply {
            setTextColor(activity.getColor(R.color.tv_text_primary))
            setTextSizeRes(R.dimen.tv_nav_text_size)
            maxLines = 1
        }
        val artist = TextView(activity).apply {
            setTextColor(activity.getColor(R.color.tv_text_secondary))
            setTextSizeRes(R.dimen.tv_text_size_metadata)
            maxLines = 1
        }
        info.addView(title)
        info.addView(artist)
        container.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

        val playPause = ImageView(activity).apply {
            setImageResource(android.R.drawable.ic_media_pause)
            isClickable = true
            setOnClickListener { MusicPlaybackManager.togglePlayPause() }
        }
        val btnSize = (activity.dim(R.dimen.tv_mini_player_height) * 0.7).toInt()
        container.addView(playPause, LinearLayout.LayoutParams(btnSize, btnSize))

        if (activity is androidx.appcompat.app.AppCompatActivity) {
            activity.lifecycleScope.launch {
                MusicPlaybackManager.currentTrack.collect { item ->
                    container.visibility = if (item != null) View.VISIBLE else View.GONE
                    if (item != null) {
                        title.text = item.title
                        artist.text = item.artists.firstOrNull() ?: item.albumArtist
                        art.load(JellyfinNativeApi(activity).imageUrl(SecureSessionStore(activity).read()!!, item, MediaCardPresentation.SQUARE))
                    }
                }
            }
            activity.lifecycleScope.launch {
                MusicPlaybackManager.isPlaying.collect { isPlaying ->
                    playPause.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
                }
            }
        }

        return container
    }

    private fun miniPlayerTime(activity: Activity) = TextView(activity).apply {
        setTextColor(activity.getColor(R.color.tv_text_secondary))
        setTextSizeRes(R.dimen.tv_text_size_metadata)
        gravity = Gravity.CENTER
        text = "0:00"
        maxLines = 1
    }
}
