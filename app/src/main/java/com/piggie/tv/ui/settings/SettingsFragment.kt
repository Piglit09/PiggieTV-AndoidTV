package com.piggie.tv.ui.settings

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import com.piggie.tv.R
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.NativeSettings
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes
import com.piggie.tv.util.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : Fragment() {
    private val api by lazy { JellyfinNativeApi(requireContext()) }
    private val store by lazy { SecureSessionStore(requireContext()) }
    private val settings by lazy { NativeSettings(requireContext()) }
    private lateinit var session: NativeSession
    private lateinit var root: FrameLayout

    @OptIn(ExperimentalCoilApi::class)
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        session = (activity as? PtvHostActivity)?.session ?: store.read()!!
        root = FrameLayout(requireContext())
        showSettings()
        return root
    }

    private fun showSettings() {
        val context = requireContext()
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dim(R.dimen.tv_spacing_large), context.dim(R.dimen.tv_spacing_medium), context.dim(R.dimen.tv_spacing_large), context.dim(R.dimen.tv_spacing_large))
        }
        scroll.addView(page)
        
        page.addView(label("Settings", context.sp(R.dimen.tv_text_size_page_title), R.color.tv_text_primary, true))
        page.addView(label(session.userName, context.sp(R.dimen.tv_text_size_body), R.color.tv_text_secondary, margin = 8))
        
        settingsButton(page, "Quality: ${settings.playbackQuality}") {
            cycleQuality()
            showSettings()
        }
        settingsButton(page, "Subtitles: ${settings.subtitlePreference}") {
            cycleSubtitles()
            showSettings()
        }
        settingsButton(page, "Audio: ${settings.audioLanguage}") {
            cycleAudio()
            showSettings()
        }
        settingsButton(page, "Clear Image Cache") {
            Coil.imageLoader(requireContext()).memoryCache?.clear()
            Coil.imageLoader(requireContext()).diskCache?.clear()
            Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show()
        }
        settingsButton(page, "Diagnostics") {
            showDiagnostics()
        }
        settingsButton(page, "Sign Out") {
            store.clear()
            activity?.finish()
        }
        
        root.removeAllViews()
        root.addView(scroll)
    }

    private fun cycleQuality() {
        val options = listOf("Auto", "Prefer Direct Play", "4K (120 Mbps)", "1080p (20 Mbps)", "720p (4 Mbps)")
        val current = settings.playbackQuality
        val next = options[(options.indexOf(current) + 1) % options.size]
        settings.playbackQuality = next
    }

    private fun cycleSubtitles() {
        val options = listOf("Default", "Always On", "Off", "Forced Only")
        val current = settings.subtitlePreference
        val next = options[(options.indexOf(current) + 1) % options.size]
        settings.subtitlePreference = next
    }

    private fun cycleAudio() {
        val options = listOf("Default", "English", "Japanese", "Spanish")
        val current = settings.audioLanguage
        val next = options[(options.indexOf(current) + 1) % options.size]
        settings.audioLanguage = next
    }

    private fun showDiagnostics() {
        val info = api.diagnostics(session, store.origin())
        val context = requireContext()
        val metrics = resources.displayMetrics
        val sw = resources.configuration.smallestScreenWidthDp

        val scroll = ScrollView(context).apply { isFillViewport = true }
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dim(R.dimen.tv_spacing_large), context.dim(R.dimen.tv_spacing_medium), context.dim(R.dimen.tv_spacing_large), context.dim(R.dimen.tv_spacing_large))
        }
        scroll.addView(page)

        page.addView(label("Diagnostics", context.sp(R.dimen.tv_text_size_page_title), R.color.tv_text_primary, true))
        
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val lastSuccess = info.lastSuccessAt?.let { dateFormat.format(Date(it)) } ?: "Never"

        val lines = listOf(
            "Server: " + info.serverUrl,
            "Display: ${metrics.widthPixels}x${metrics.heightPixels} (density=${metrics.density}, dpi=${metrics.densityDpi}, sw=${sw}dp)",
            "Session: " + info.sessionOrigin.name.lowercase().replace('_', ' '),
            "App Version: " + info.appVersion,
            "Android Version: " + info.androidVersion,
            "Device: " + info.device,
            "Last API Event: " + (info.lastApiError ?: "None"),
            "Last Successful Request: " + lastSuccess
        )
        lines.forEach { page.addView(label(it, 14f, R.color.tv_text_secondary, margin = 6)) }
        
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, context.dim(R.dimen.tv_spacing_large), 0, 0)
        }
        
        val back = Button(context).apply {
            text = "Back to Settings"
            isAllCaps = false
            textSize = 15f
            setTextColor(requireContext().getColor(R.color.tv_text_primary))
            setBackgroundResource(R.drawable.tv_button_secondary)
            setOnClickListener { showSettings() }
        }
        buttonRow.addView(back, LinearLayout.LayoutParams(context.dim(R.dimen.tv_hero_button_width) + context.dim(R.dimen.tv_spacing_large), context.dim(R.dimen.tv_nav_button_height) + context.dim(R.dimen.tv_spacing_small)))
        
        page.addView(buttonRow)
        
        root.removeAllViews()
        root.addView(scroll)
        back.requestFocus()
    }

    private fun settingsButton(page: LinearLayout, title: String, action: () -> Unit) {
        val context = requireContext()
        page.addView(Button(context).apply {
            text = title
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isAllCaps = false
            setTextSizeRes(R.dimen.tv_text_size_body)
            setTextColor(requireContext().getColor(R.color.tv_text_primary))
            setBackgroundResource(R.drawable.tv_button_secondary)
            setPadding(context.dim(R.dimen.tv_spacing_medium), 0, context.dim(R.dimen.tv_spacing_medium), 0)
            setOnClickListener { action() }
            setOnFocusChangeListener { view, focused ->
                view.animate().scaleX(if (focused) 1.02f else 1f).setDuration(120).start()
            }
        }, LinearLayout.LayoutParams(-1, context.dim(R.dimen.tv_nav_button_height) + context.dim(R.dimen.tv_spacing_medium)).apply { topMargin = context.dim(R.dimen.tv_spacing_small) })
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false, margin: Int = 0): TextView =
        TextView(requireContext()).apply {
            text = value
            textSize = size
            setTextColor(requireContext().getColor(color))
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            if (margin > 0) layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = margin }
        }
}
