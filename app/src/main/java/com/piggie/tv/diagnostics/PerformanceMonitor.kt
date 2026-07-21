package com.piggie.tv.diagnostics

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.piggie.tv.theme.PTVColors
import java.util.concurrent.TimeUnit

object PerformanceMonitor {
    private var isEnabled = false
    private var overlay: View? = null
    private var fps = 0
    private var frames = 0
    private var lastFpsUpdate = 0L

    private val choreographerCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isEnabled) return
            frames++
            val now = System.nanoTime()
            if (now - lastFpsUpdate >= TimeUnit.SECONDS.toNanos(1)) {
                fps = frames
                frames = 0
                lastFpsUpdate = now
                updateOverlay()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun toggle(activity: Activity) {
        isEnabled = !isEnabled
        if (isEnabled) {
            lastFpsUpdate = System.nanoTime()
            Choreographer.getInstance().postFrameCallback(choreographerCallback)
            showOverlay(activity)
        } else {
            hideOverlay(activity)
        }
    }

    private fun showOverlay(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val view = TextView(activity).apply {
            setBackgroundColor(0x88000000.toInt())
            setTextColor(PTVColors.accent)
            textSize = 12f
            setPadding(20, 20, 20, 20)
            gravity = Gravity.END or Gravity.TOP
        }
        overlay = view
        root.addView(view, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END or Gravity.TOP })
        updateOverlay()
    }

    private fun hideOverlay(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        overlay?.let { root.removeView(it) }
        overlay = null
    }

    private fun updateOverlay() {
        val view = overlay as? TextView ?: return
        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val maxMem = runtime.maxMemory() / 1024 / 1024
        
        view.text = "FPS: $fps | MEM: ${usedMem}MB / ${maxMem}MB"
    }
}
