package com.piggie.tv.ui.hero

import android.view.View

object HeroInitialLayoutPolicy {
    fun shouldSample(width: Int, height: Int, alreadySampled: Boolean): Boolean =
        !alreadySampled && width > 0 && height > 0
}

/** One layout callback, removed immediately after the first usable viewport sample. */
class HeroInitialVisibilitySampler(
    private val view: View,
    private val onReady: () -> Unit
) : View.OnLayoutChangeListener {
    private var attached = false
    private var sampled = false

    fun attach() {
        if (sampled || attached) return
        attached = true
        view.addOnLayoutChangeListener(this)
        sampleIfReady()
    }

    fun detach() {
        if (attached) {
            view.removeOnLayoutChangeListener(this)
            attached = false
        }
    }

    override fun onLayoutChange(
        changedView: View,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        oldLeft: Int,
        oldTop: Int,
        oldRight: Int,
        oldBottom: Int
    ) {
        sampleIfReady()
    }

    private fun sampleIfReady() {
        if (!HeroInitialLayoutPolicy.shouldSample(view.width, view.height, sampled)) return
        sampled = true
        detach()
        onReady()
    }
}
