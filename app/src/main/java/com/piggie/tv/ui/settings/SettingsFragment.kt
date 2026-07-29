package com.piggie.tv.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.content.Intent
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
import com.piggie.tv.data.discovery.DiscoveryManager
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.playback.ConnectionSpeed
import com.piggie.tv.data.session.NativeSettings
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.diagnostics.PerformanceMonitor
import com.piggie.tv.diagnostics.PtvDeviceSnapshot
import com.piggie.tv.diagnostics.PtvDiagnosticExporter
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import com.piggie.tv.diagnostics.PtvTestResult
import com.piggie.tv.diagnostics.PtvTestRunner
import com.piggie.tv.theme.PTVShapes
import com.piggie.tv.ui.widgets.PtvSelectionDialog
import com.piggie.tv.util.CrashReporter
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes
import com.piggie.tv.util.sp

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

    private fun showSettings(focusConnectionSpeed: Boolean = false) {
        val context = requireContext()
        val scroll = ScrollView(context).apply { isFillViewport = true }
        scroll.isSmoothScrollingEnabled = false
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dim(R.dimen.tv_spacing_large), context.dim(R.dimen.tv_spacing_medium), context.dim(R.dimen.tv_spacing_large), context.dim(R.dimen.tv_spacing_large))
        }
        scroll.addView(page)
        
        page.addView(label("Settings", context.sp(R.dimen.tv_text_size_page_title), R.color.tv_text_primary, true))
        page.addView(label("v0.8.6-beta.1  •  ${session.userName}", context.sp(R.dimen.tv_text_size_body), R.color.tv_text_secondary, margin = 8))
        
        val connectionSpeedButton = settingsButton(
            page,
            "Connection Speed: ${ConnectionSpeed.fromStored(settings.connectionSpeed).displayLabel}"
        ) {
            showConnectionSpeedSelection()
        }
        settingsButton(page, "Autoplay Next Episode: ${if (settings.autoplayNextEpisode) "On" else "Off"}") {
            settings.autoplayNextEpisode = !settings.autoplayNextEpisode
            showSettings()
        }
        settingsButton(page, "Subtitles: ${settings.subtitlePreference}") {
            cycleSubtitles()
            showSettings()
        }
        settingsButton(page, "Clear Image Cache") {
            Coil.imageLoader(requireContext()).memoryCache?.clear()
            Coil.imageLoader(requireContext()).diskCache?.clear()
            Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show()
        }
        settingsButton(page, "Diagnostics \u0026 Beta Info") {
            showDiagnostics()
        }
        settingsButton(page, "Sign Out") {
            store.clear()
            activity?.finish()
        }
        
        root.removeAllViews()
        root.addView(scroll)
        if (focusConnectionSpeed) {
            connectionSpeedButton.post { connectionSpeedButton.requestFocus() }
        }
    }

    private fun showConnectionSpeedSelection() {
        val options = ConnectionSpeed.entries
        val current = ConnectionSpeed.fromStored(settings.connectionSpeed)
        PtvSelectionDialog(
            requireContext(),
            "Connection Speed",
            options.map { it.displayLabel },
            selectedIndex = options.indexOf(current),
            defaultIndex = options.indexOf(ConnectionSpeed.AUTO)
        ) { index ->
            settings.connectionSpeed = options[index].wireValue
            PtvDiagnosticsManager.event(
                "connection",
                "speed_selected",
                mapOf("speed" to options[index].wireValue, "surface" to "settings")
            )
            showSettings(focusConnectionSpeed = true)
        }.show()
    }

    private fun cycleSubtitles() {
        val options = listOf("Default", "Always On", "Off", "Forced Only")
        val current = settings.subtitlePreference
        val next = options[(options.indexOf(current) + 1) % options.size]
        settings.subtitlePreference = next
    }

    private fun showDiagnostics() {
        val context = requireContext()
        PtvDiagnosticsManager.initialize(context)
        val device = PtvDeviceSnapshot.capture(context)

        val scroll = ScrollView(context).apply { isFillViewport = true }
        scroll.isSmoothScrollingEnabled = false
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dim(R.dimen.tv_spacing_large), context.dim(R.dimen.tv_spacing_medium), context.dim(R.dimen.tv_spacing_large), context.dim(R.dimen.tv_spacing_large))
        }
        scroll.addView(page)

        page.addView(label("Diagnostics \u0026 Beta", context.sp(R.dimen.tv_text_size_page_title), R.color.tv_text_primary, true))
        
        val lines = listOf(
            "Version: ${device.appVersion} (${device.versionCode})",
            "Device: ${device.manufacturer} ${device.model} • Android ${device.androidVersion} / API ${device.apiLevel}",
            "App surface: ${device.widthPixels}×${device.heightPixels}px • ${device.logicalWidthDp}×${device.logicalHeightDp}dp • ${device.layoutProfile}",
            "Window: ${device.availableWindowWidth}×${device.availableWindowHeight}px • insets ${device.insetLeft},${device.insetTop},${device.insetRight},${device.insetBottom}",
            "Density: ${device.density} • ${device.densityDpi}dpi • scaled ${device.scaledDensity} • font ${device.fontScale}",
            "Display mode: ${device.displayMode} @ ${"%.2f".format(device.refreshRateHz)}Hz",
            "Memory: ${device.availableMemoryBytes / 1024 / 1024}MB available • Storage: ${device.availableStorageBytes / 1024 / 1024}MB available",
            "Diagnostics: ${if (PtvDiagnosticsManager.isEnabled()) "Enabled" else "Disabled"} • Overlay: ${if (PerformanceMonitor.isVisible()) "Visible" else "Hidden"}"
        )
        lines.forEach { page.addView(label(it, context.sp(R.dimen.tv_text_size_body), R.color.tv_text_secondary, margin = context.dim(R.dimen.tv_spacing_small))) }

        page.addView(
            label(
                "Discovery Shelves",
                context.sp(R.dimen.tv_text_size_section_title),
                R.color.tv_text_primary,
                true,
                margin = context.dim(R.dimen.tv_spacing_medium)
            )
        )
        val shelfDiagnostics = DiscoveryManager.recentDiagnostics()
        if (shelfDiagnostics.isEmpty()) {
            page.addView(label("No shelf samples recorded yet.", context.sp(R.dimen.tv_text_size_metadata), R.color.tv_text_secondary))
        } else {
            shelfDiagnostics.forEach { shelf ->
                val cache = if (shelf.cacheHit) "hit" else "miss"
                page.addView(
                    label(
                        "${shelf.page}/${shelf.shelfTitle}: ${shelf.status} • " +
                            "${shelf.resultCount}→${shelf.renderCount} • ${shelf.elapsedMs}ms • " +
                            "HTTP ${shelf.httpStatus ?: "—"} • cache $cache • visible ${shelf.visibleCards ?: 0}",
                        context.sp(R.dimen.tv_text_size_metadata),
                        R.color.tv_text_secondary,
                        margin = context.dim(R.dimen.tv_spacing_small)
                    )
                )
            }
        }

        val toggleDiagnostics = settingsButton(page, "Diagnostics collection: ${if (PtvDiagnosticsManager.isEnabled()) "On" else "Off"}") {
            PtvDiagnosticsManager.setEnabled(context, !PtvDiagnosticsManager.isEnabled())
            activity?.let(PtvDiagnosticsManager::ensureActivityInstrumentation)
            showDiagnostics()
        }
        settingsButton(page, "Debug overlay: ${if (PerformanceMonitor.isVisible()) "On" else "Off"}") {
            val visible = !PerformanceMonitor.isVisible()
            settings.diagnosticsOverlayEnabled = visible
            (activity as? PtvHostActivity)?.let { PerformanceMonitor.setVisible(it, visible) }
            showDiagnostics()
        }
        settingsButton(page, "Test Runner") { showTestRunner() }
        settingsButton(page, "Copy Text Report") {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("PTV Diagnostic", PtvDiagnosticExporter.exportText(context)))
            Toast.makeText(context, "Sanitized report copied", Toast.LENGTH_SHORT).show()
        }
        settingsButton(page, "Save JSON + Text") {
            val files = PtvDiagnosticExporter.save(context)
            Toast.makeText(context, "Saved ${files.json.name} and ${files.text.name}", Toast.LENGTH_LONG).show()
        }
        settingsButton(page, "Share Sanitized Report") {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "PiggieTV diagnostics")
                putExtra(Intent.EXTRA_TEXT, PtvDiagnosticExporter.exportText(context))
            }
            runCatching { startActivity(Intent.createChooser(intent, "Share diagnostics")) }
                .onFailure { Toast.makeText(context, "No share target is installed", Toast.LENGTH_SHORT).show() }
        }
        settingsButton(page, "Clear Crash Reports") {
            CrashReporter.clearReports(context)
            Toast.makeText(context, "Local crash reports cleared", Toast.LENGTH_SHORT).show()
        }
        settingsButton(page, "Back") { showSettings() }
        
        root.removeAllViews()
        root.addView(scroll)
        toggleDiagnostics.requestFocus()
    }

    private fun showTestRunner(results: List<PtvTestResult>? = null) {
        val context = requireContext()
        val scroll = ScrollView(context).apply { isFillViewport = true }
        scroll.isSmoothScrollingEnabled = false
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dim(R.dimen.tv_spacing_large), context.dim(R.dimen.tv_spacing_medium), context.dim(R.dimen.tv_spacing_large), context.dim(R.dimen.tv_spacing_large))
        }
        scroll.addView(page)
        page.addView(label("Diagnostics Test Runner", context.sp(R.dimen.tv_text_size_page_title), R.color.tv_text_primary, true))
        page.addView(label("Safe tests never start media. Playback and document lifecycle checks remain skipped until a sample is explicitly selected.", context.sp(R.dimen.tv_text_size_body), R.color.tv_text_secondary, margin = context.dim(R.dimen.tv_spacing_small)))
        results?.forEach { result ->
            val color = if (result.status == com.piggie.tv.diagnostics.PtvTestStatus.FAILED) R.color.tv_focus_ring else R.color.tv_text_secondary
            page.addView(label("${result.status}: ${result.suite} / ${result.test} • ${result.durationMs}ms • ${result.detail}", context.sp(R.dimen.tv_text_size_metadata), color, margin = context.dim(R.dimen.tv_spacing_small)))
        }
        val run = settingsButton(page, if (results == null) "Run Safe Tests" else "Run Again") {
            Toast.makeText(context, "Running safe diagnostics…", Toast.LENGTH_SHORT).show()
            PtvTestRunner.runSafe(context, session, api) { showTestRunner(it) }
        }
        settingsButton(page, "Back to Diagnostics") { showDiagnostics() }
        root.removeAllViews()
        root.addView(scroll)
        run.requestFocus()
    }

    private fun settingsButton(page: LinearLayout, title: String, action: () -> Unit): Button {
        val context = requireContext()
        val button = Button(context).apply {
            text = title
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isAllCaps = false
            setTextSizeRes(R.dimen.tv_text_size_body)
            setTextColor(requireContext().getColor(R.color.tv_text_primary))
            setBackgroundResource(R.drawable.tv_button_secondary)
            setPadding(context.dim(R.dimen.tv_spacing_medium), 0, context.dim(R.dimen.tv_spacing_medium), 0)
            minimumHeight = 0
            minHeight = 0
            setOnClickListener { action() }
            setOnFocusChangeListener { view, focused ->
                PTVShapes.applyFocusEffect(view, focused)
            }
        }
        page.addView(button, LinearLayout.LayoutParams(-1, context.dim(R.dimen.tv_nav_button_height) + context.dim(R.dimen.tv_spacing_medium)).apply { topMargin = context.dim(R.dimen.tv_spacing_small) })
        return button
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
