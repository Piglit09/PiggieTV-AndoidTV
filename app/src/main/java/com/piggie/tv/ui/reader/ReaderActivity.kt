package com.piggie.tv.ui.reader

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.piggie.tv.R
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.reader.ReaderDocument
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.ui.widgets.PTVZoomImageView
import com.piggie.tv.util.PTVLog
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import com.piggie.tv.diagnostics.PtvReaderTrace
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject

class ReaderActivity : AppCompatActivity() {
    private val viewModel: ReaderViewModel by viewModels()
    private val store by lazy { SecureSessionStore(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val edgeTurnGate = ReaderEdgeTurnGate()

    private lateinit var session: NativeSession
    private lateinit var item: MediaItem
    private lateinit var root: FrameLayout
    private lateinit var pageView: PTVZoomImageView
    private lateinit var loadingGroup: LinearLayout
    private lateinit var overlay: LinearLayout
    private lateinit var overlayIndicator: TextView
    private lateinit var transientIndicator: TextView
    private lateinit var fitButton: Button
    private lateinit var directionButton: Button
    private lateinit var firstControl: Button

    private var document: ReaderDocument? = null
    private var currentPage = 0
    private var pendingPage: Int? = null
    private var isRtl = false
    private var fitMode = ReaderFitMode.FIT_PAGE
    private var stateHandled = false

    private val loadingWatchdog = Runnable {
        if (loadingGroup.isVisible) showError(
            "The reader is still loading. Retry the document or open Diagnostics for the latest trace."
        )
    }
    private val hideIndicator = Runnable { transientIndicator.isVisible = false }
    private val autoHideControls = Runnable {
        if (overlay.isVisible && !overlay.hasFocus()) hideControls()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = store.read() ?: run { finish(); return }
        item = parseIntentItem() ?: run { finish(); return }
        PtvDiagnosticsManager.routeRequested("reader")
        PtvDiagnosticsManager.recordReader(PtvReaderTrace(itemId = item.id, event = "open_requested"))
        setupView()
        observeState()
        beginDocumentLoad()
    }

    private fun setupView() {
        root = FrameLayout(this).apply { setBackgroundColor(PTVColors.background) }

        pageView = PTVZoomImageView(this).apply {
            contentDescription = "Reader page"
            setBackgroundColor(0xFF050208.toInt())
        }
        root.addView(pageView, FrameLayout.LayoutParams(-1, -1))

        loadingGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dim(R.dimen.tv_spacing_large), dim(R.dimen.tv_spacing_large), dim(R.dimen.tv_spacing_large), dim(R.dimen.tv_spacing_large))
            addView(ProgressBar(this@ReaderActivity))
            addView(TextView(this@ReaderActivity).apply {
                text = "Loading reader…"
                setTextSizeRes(R.dimen.tv_text_size_body)
                setTextColor(PTVColors.textPrimary)
                gravity = Gravity.CENTER
            })
        }
        root.addView(loadingGroup, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        transientIndicator = TextView(this).apply {
            setBackgroundResource(R.drawable.tv_panel_background)
            setTextColor(PTVColors.textPrimary)
            setTextSizeRes(R.dimen.tv_text_size_metadata)
            setPadding(dim(R.dimen.tv_spacing_medium), dim(R.dimen.tv_spacing_small), dim(R.dimen.tv_spacing_medium), dim(R.dimen.tv_spacing_small))
            isVisible = false
        }
        root.addView(transientIndicator, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dim(R.dimen.tv_reader_safe_margin)
            bottomMargin = dim(R.dimen.tv_reader_safe_margin)
        })

        overlay = createControlsOverlay()
        root.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        updateContentInsets()
    }

    private fun createControlsOverlay(): LinearLayout {
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE61A0A33.toInt())
            setPadding(dim(R.dimen.tv_spacing_medium), dim(R.dimen.tv_spacing_small), dim(R.dimen.tv_spacing_medium), dim(R.dimen.tv_spacing_small))
            isVisible = false
        }

        val navigationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        firstControl = controlButton("Previous") { requestRelativePage(ReaderPageAction.PREVIOUS) }
        overlayIndicator = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextSizeRes(R.dimen.tv_text_size_body)
            setTextColor(PTVColors.textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val next = controlButton("Next") { requestRelativePage(ReaderPageAction.NEXT) }
        fitButton = controlButton("Fit Page") { cycleFitMode() }
        navigationRow.addView(firstControl, controlParams(104))
        navigationRow.addView(overlayIndicator, LinearLayout.LayoutParams(0, dim(R.dimen.tv_reader_control_height), 1f))
        navigationRow.addView(next, controlParams(88))
        navigationRow.addView(fitButton, controlParams(106))
        controls.addView(navigationRow)

        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        toolsRow.addView(controlButton("Zoom −") { pageView.zoomOut(); updateFitLabel() }, controlParams(92))
        toolsRow.addView(controlButton("Zoom +") { pageView.zoomIn(); updateFitLabel() }, controlParams(92))
        toolsRow.addView(controlButton("Reset") { resetToFitPage() }, controlParams(82))
        directionButton = controlButton("LTR") { setReadingDirection(!isRtl) }
        toolsRow.addView(directionButton, controlParams(82))
        toolsRow.addView(controlButton("Settings") { showSettings() }, controlParams(100))
        toolsRow.addView(controlButton("Exit") { finish() }, controlParams(72))
        controls.addView(toolsRow)
        return controls
    }

    private fun controlButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSizeRes(R.dimen.tv_nav_text_size)
        setTextColor(PTVColors.textPrimary)
        setBackgroundResource(R.drawable.tv_button_secondary)
        minimumHeight = 0
        minHeight = 0
        minimumWidth = 0
        minWidth = 0
        setPadding(dim(R.dimen.tv_spacing_small), 0, dim(R.dimen.tv_spacing_small), 0)
        setOnClickListener {
            action()
            scheduleControlAutoHide()
        }
    }

    private fun controlParams(widthDp: Int) = LinearLayout.LayoutParams(
        (widthDp * resources.displayMetrics.density).toInt(),
        dim(R.dimen.tv_reader_control_height)
    ).apply { marginEnd = dim(R.dimen.tv_spacing_small) }

    private fun observeState() {
        lifecycleScope.launchWhenStarted {
            viewModel.state.collectLatest { state ->
                when (state) {
                    ReaderState.Idle -> Unit
                    ReaderState.Loading -> showLoading()
                    is ReaderState.Success -> if (!stateHandled) {
                        stateHandled = true
                        handler.removeCallbacks(loadingWatchdog)
                        loadingGroup.isVisible = false
                        document = state.document
                        isRtl = state.isRtl
                        fitMode = state.fitMode
                        currentPage = state.initialPage
                        pageView.setFitMode(fitMode)
                        updateFitLabel()
                        updateDirectionLabel()
                        pageView.post { requestPage(state.initialPage) }
                        PtvDiagnosticsManager.recordReader(
                            PtvReaderTrace(
                                itemId = item.id,
                                event = "document_opened",
                                format = state.document.format.name,
                                pageCount = state.document.pageCount,
                                page = state.initialPage,
                                fitMode = state.fitMode.name,
                                direction = if (state.isRtl) "RTL" else "LTR"
                            )
                        )
                    }
                    is ReaderState.Unsupported -> {
                        handler.removeCallbacks(loadingWatchdog)
                        showError("${state.format.name} is not supported by the native TV reader.")
                        PtvDiagnosticsManager.recordReader(PtvReaderTrace(itemId = item.id, event = "unsupported", format = state.format.name))
                    }
                    is ReaderState.Error -> {
                        handler.removeCallbacks(loadingWatchdog)
                        showError(state.message)
                        PtvDiagnosticsManager.recordReader(PtvReaderTrace(itemId = item.id, event = "reader_error", error = state.message))
                    }
                }
            }
        }
    }

    private fun beginDocumentLoad() {
        stateHandled = false
        showLoading()
        viewModel.loadDocument(session, item)
    }

    private fun showLoading() {
        loadingGroup.isVisible = true
        handler.removeCallbacks(loadingWatchdog)
        handler.postDelayed(loadingWatchdog, LOADING_TIMEOUT_MS)
    }

    private fun requestPage(index: Int) {
        val doc = document ?: return
        if (index !in 0 until doc.pageCount) {
            edgeTurnGate.reset()
            showTemporaryIndicator()
            return
        }
        pendingPage = index
        if (pageView.drawable == null) loadingGroup.isVisible = true
        val width = pageView.width.coerceAtLeast(1)
        val height = pageView.height.coerceAtLeast(1)
        PtvDiagnosticsManager.recordReader(
            PtvReaderTrace(
                itemId = doc.itemId,
                event = "render_requested",
                format = doc.format.name,
                pageCount = doc.pageCount,
                page = index,
                fitMode = fitMode.name,
                direction = if (isRtl) "RTL" else "LTR",
                renderTarget = "${width}x$height"
            )
        )
        viewModel.requestPage(index, doc.pageCount, width, height) { result ->
            // requestPage cancels its predecessor. Comparing against an ID that
            // is assigned after this call races with an immediate cache hit on
            // Dispatchers.Main.immediate and incorrectly discards that page.
            if (result.pageIndex != pendingPage) return@requestPage
            if (result.bitmap == null) {
                pendingPage = null
                showError(result.error ?: "Page ${index + 1} could not be rendered.")
                PtvDiagnosticsManager.recordReader(
                    PtvReaderTrace(itemId = doc.itemId, event = "render_error", page = index, error = result.error)
                )
                return@requestPage
            }
            pageView.setImageBitmap(result.bitmap)
            pageView.setFitMode(fitMode)
            currentPage = index
            pendingPage = null
            loadingGroup.isVisible = false
            viewModel.saveProgress(session, doc.itemId, currentPage)
            updateIndicators()
            showTemporaryIndicator()
            // A page turn initiated from the controls must not steal focus from
            // that control row; otherwise the very next D-pad press pages again.
            if (!overlay.isVisible) pageView.requestFocus()
            PTVLog.i(
                "Reader page=${index + 1}/${doc.pageCount} fit=$fitMode target=${width}x$height " +
                    "bitmap=${result.bitmap.width}x${result.bitmap.height} cache=${result.cacheHit} renderMs=${result.renderDurationMs}"
            )
            PtvDiagnosticsManager.routeVisible("reader", "reader_page")
            PtvDiagnosticsManager.routeInteractive("reader", "reader_page")
            PtvDiagnosticsManager.recordReader(
                PtvReaderTrace(
                    itemId = doc.itemId,
                    event = "page_displayed",
                    format = doc.format.name,
                    pageCount = doc.pageCount,
                    page = index,
                    fitMode = fitMode.name,
                    direction = if (isRtl) "RTL" else "LTR",
                    renderTarget = "${width}x$height",
                    renderMs = result.renderDurationMs,
                    cacheHit = result.cacheHit,
                    bitmapBytes = result.bitmap.allocationByteCount
                )
            )
        }
    }

    private fun requestRelativePage(action: ReaderPageAction) {
        val base = pendingPage ?: currentPage
        val target = if (action == ReaderPageAction.NEXT) base + 1 else base - 1
        requestPage(target)
    }

    private fun updateIndicators() {
        val count = document?.pageCount ?: return
        val percent = (((currentPage + 1).toDouble() / count) * 100).toInt().coerceIn(0, 100)
        val label = "Page ${currentPage + 1} of $count  •  $percent%"
        overlayIndicator.text = label
        transientIndicator.text = label
    }

    private fun showTemporaryIndicator() {
        updateIndicators()
        transientIndicator.isVisible = true
        handler.removeCallbacks(hideIndicator)
        handler.postDelayed(hideIndicator, INDICATOR_TIMEOUT_MS)
    }

    private fun cycleFitMode() {
        fitMode = when (fitMode) {
            ReaderFitMode.FIT_PAGE -> ReaderFitMode.FIT_WIDTH
            ReaderFitMode.FIT_WIDTH -> ReaderFitMode.ACTUAL_SIZE
            ReaderFitMode.ACTUAL_SIZE -> ReaderFitMode.FIT_PAGE
        }
        pageView.setFitMode(fitMode)
        document?.let { viewModel.setFitMode(session, it.itemId, fitMode) }
        edgeTurnGate.reset()
        updateFitLabel()
        PtvDiagnosticsManager.recordReader(PtvReaderTrace(itemId = item.id, event = "fit_mode_changed", page = currentPage, fitMode = fitMode.name))
    }

    private fun resetToFitPage() {
        fitMode = ReaderFitMode.FIT_PAGE
        pageView.setFitMode(fitMode)
        document?.let { viewModel.setFitMode(session, it.itemId, fitMode) }
        edgeTurnGate.reset()
        updateFitLabel()
        PtvDiagnosticsManager.recordReader(PtvReaderTrace(itemId = item.id, event = "fit_reset", page = currentPage, fitMode = fitMode.name))
    }

    private fun updateFitLabel() {
        fitButton.text = when {
            pageView.hasUserTransform() -> "Custom Zoom"
            fitMode == ReaderFitMode.FIT_PAGE -> "Fit Page"
            fitMode == ReaderFitMode.FIT_WIDTH -> "Fit Width"
            else -> "Actual Size"
        }
    }

    private fun setReadingDirection(rtl: Boolean) {
        isRtl = rtl
        document?.let { viewModel.toggleRtl(session, it.itemId, rtl) }
        updateDirectionLabel()
        edgeTurnGate.reset()
        PtvDiagnosticsManager.recordReader(
            PtvReaderTrace(itemId = item.id, event = "direction_changed", page = currentPage, direction = if (rtl) "RTL" else "LTR")
        )
    }

    private fun updateDirectionLabel() {
        directionButton.text = if (isRtl) "RTL" else "LTR"
    }

    private fun showControls() {
        overlay.isVisible = true
        handler.removeCallbacks(hideIndicator)
        transientIndicator.isVisible = false
        overlay.post {
            updateContentInsets()
            firstControl.requestFocus()
        }
        scheduleControlAutoHide()
    }

    private fun hideControls() {
        handler.removeCallbacks(autoHideControls)
        overlay.isVisible = false
        updateContentInsets()
        pageView.requestFocus()
    }

    private fun scheduleControlAutoHide() {
        handler.removeCallbacks(autoHideControls)
        handler.postDelayed(autoHideControls, CONTROL_AUTO_HIDE_MS)
    }

    private fun updateContentInsets() {
        val safe = dim(R.dimen.tv_reader_safe_margin)
        val bottom = if (overlay.isVisible) overlay.height + safe else safe
        pageView.setContentInsets(safe, safe, safe, bottom)
    }

    private fun showSettings() {
        ReaderSettingsDialog(
            this,
            isRtl,
            onRtlChanged =(::setReadingDirection),
            onMarkRead = { document?.let { requestPage(it.pageCount - 1) } },
            onRestart = { requestPage(0) }
        ).show()
    }

    private fun showError(message: String) {
        loadingGroup.isVisible = false
        overlay.isVisible = false
        val existing = root.findViewWithTag<View>(ERROR_TAG)
        if (existing != null) root.removeView(existing)
        val panel = LinearLayout(this).apply {
            tag = ERROR_TAG
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xE60F041C.toInt())
            setPadding(dim(R.dimen.tv_screen_margin_horizontal), dim(R.dimen.tv_spacing_large), dim(R.dimen.tv_screen_margin_horizontal), dim(R.dimen.tv_spacing_large))
            addView(TextView(this@ReaderActivity).apply {
                text = "Reader unavailable"
                setTextSizeRes(R.dimen.tv_text_size_page_title)
                setTextColor(PTVColors.textPrimary)
            })
            addView(TextView(this@ReaderActivity).apply {
                text = message
                gravity = Gravity.CENTER
                setTextSizeRes(R.dimen.tv_text_size_body)
                setTextColor(PTVColors.textSecondary)
                setPadding(0, dim(R.dimen.tv_spacing_medium), 0, dim(R.dimen.tv_spacing_medium))
            })
            val retry = controlButton("Retry") {
                root.findViewWithTag<View>(ERROR_TAG)?.let(root::removeView)
                beginDocumentLoad()
            }
            addView(retry, LinearLayout.LayoutParams(dim(R.dimen.tv_hero_button_width), dim(R.dimen.tv_hero_button_height)))
            addView(controlButton("Exit") { finish() }, LinearLayout.LayoutParams(dim(R.dimen.tv_hero_button_width), dim(R.dimen.tv_hero_button_height)).apply {
                topMargin = dim(R.dimen.tv_spacing_small)
            })
            retry.post { retry.requestFocus() }
        }
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        PtvDiagnosticsManager.recordReader(
            PtvReaderTrace(
                itemId = item.id,
                event = "navigation_input",
                page = currentPage,
                fitMode = fitMode.name,
                direction = if (isRtl) "RTL" else "LTR",
                input = KeyEvent.keyCodeToString(keyCode)
            )
        )
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showSettings()
            return true
        }
        if (overlay.isVisible && isDescendantOf(currentFocus, overlay)) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                hideControls()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (overlay.isVisible) hideControls() else showControls()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (pageView.isZoomedOrPannable()) {
                    pageView.panForKey(keyCode)
                    edgeTurnGate.reset()
                    updateFitLabel()
                } else {
                    showControls()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                handleHorizontalKey(keyCode)
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                when {
                    pageView.hasUserTransform() || fitMode != ReaderFitMode.FIT_PAGE -> resetToFitPage()
                    overlay.isVisible -> hideControls()
                    else -> finish()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun handleHorizontalKey(keyCode: Int) {
        val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            ReaderHorizontalDirection.RIGHT
        } else {
            ReaderHorizontalDirection.LEFT
        }
        if (pageView.isZoomedOrPannable()) {
            if (pageView.panForKey(keyCode)) {
                edgeTurnGate.reset()
                updateFitLabel()
                return
            }
            if (!edgeTurnGate.onEdgePress(direction, SystemClock.elapsedRealtime())) return
        }
        requestRelativePage(ReaderNavigationPolicy.pageAction(direction, isRtl))
    }

    private fun isDescendantOf(view: View?, ancestor: ViewGroup): Boolean {
        var current = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun parseIntentItem(): MediaItem? {
        val id = intent.getStringExtra(EXTRA_ITEM_ID) ?: return null
        val json = intent.getStringExtra(EXTRA_ITEM_JSON) ?: return null
        return runCatching { parseMediaItem(JSONObject(json)) }
            .onFailure { PTVLog.e("Reader intent parse failed item=${PTVLog.mask(id)}", it) }
            .getOrNull()
    }

    private fun parseMediaItem(obj: JSONObject): MediaItem = MediaItem(
        id = obj.getString("id"),
        title = obj.optString("title", "Untitled"),
        type = obj.optString("type"),
        year = obj.optString("year").ifBlank { null },
        imageTag = obj.optString("imageTag").ifBlank { null },
        seriesName = obj.optString("seriesName").ifBlank { null },
        episodeLabel = obj.optString("episodeLabel").ifBlank { null },
        playbackPositionTicks = obj.optLong("playbackPositionTicks"),
        runtimeTicks = obj.optLong("runtimeTicks"),
        pageCount = obj.optInt("pageCount", 0),
        container = obj.optString("container").ifBlank { null }
    )

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::item.isInitialized) {
            PtvDiagnosticsManager.recordReader(PtvReaderTrace(itemId = item.id, event = "reader_closed", page = currentPage))
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ITEM_ID = "extra_item_id"
        private const val EXTRA_ITEM_JSON = "extra_item_json"
        private const val ERROR_TAG = "reader_error"
        private const val LOADING_TIMEOUT_MS = 12_000L
        private const val INDICATOR_TIMEOUT_MS = 2_500L
        private const val CONTROL_AUTO_HIDE_MS = 6_000L

        fun start(context: Context, item: MediaItem) {
            context.startActivity(Intent(context, ReaderActivity::class.java).apply {
                putExtra(EXTRA_ITEM_ID, item.id)
                putExtra(EXTRA_ITEM_JSON, JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("type", item.type)
                    put("pageCount", item.pageCount)
                    put("container", item.container)
                }.toString())
            })
        }
    }
}
