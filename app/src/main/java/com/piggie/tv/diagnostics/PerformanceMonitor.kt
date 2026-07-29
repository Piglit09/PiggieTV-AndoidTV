package com.piggie.tv.diagnostics

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.piggie.tv.R
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes

/** Optional diagnostic overlay. Frame values come from Window.FrameMetrics. */
object PerformanceMonitor {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: TextView? = null
    private var owner: Activity? = null

    private val refresh = object : Runnable {
        override fun run() {
            overlay?.text = PtvDiagnosticsManager.overlaySummary()
            if (overlay != null) handler.postDelayed(this, 2_000L)
        }
    }

    fun setVisible(activity: Activity, visible: Boolean) {
        if (visible) show(activity) else hide()
    }

    fun toggle(activity: Activity) = setVisible(activity, overlay == null)

    fun isVisible(): Boolean = overlay != null

    fun detach(activity: Activity) {
        if (owner === activity) hide()
    }

    private fun show(activity: Activity) {
        if (owner !== activity) hide()
        if (overlay != null) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        overlay = TextView(activity).apply {
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(PTVColors.accent)
            setTextSizeRes(R.dimen.tv_text_size_metadata)
            setPadding(activity.dim(R.dimen.tv_spacing_medium), activity.dim(R.dimen.tv_spacing_medium), activity.dim(R.dimen.tv_spacing_medium), activity.dim(R.dimen.tv_spacing_medium))
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        owner = activity
        root.addView(overlay, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END or Gravity.TOP })
        handler.post(refresh)
    }

    private fun hide() {
        handler.removeCallbacks(refresh)
        val view = overlay
        (view?.parent as? ViewGroup)?.removeView(view)
        overlay = null
        owner = null
    }
}
