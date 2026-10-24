package com.piggie.tv.ui.home

import android.os.Looper
import android.os.MessageQueue
import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
import com.piggie.tv.ui.layout.TvLinearLayoutManager

/** Prepares only a ready shelf in the area that Home's compact Hero will expose. */
internal class HomeCollapseShelfWarmup(
    private val page: RecyclerView,
    private val manager: TvLinearLayoutManager,
    private val revealHeightPx: () -> Int,
    private val expandedHeaderHeightPx: () -> Int,
    private val readyRowPositions: () -> Set<Int>,
    private val expanded: () -> Boolean,
    private val idleQueue: HomeIdleQueue = MainHomeIdleQueue
) {
    private var resumed = false
    private var detached = false
    private var firstDrawn = false
    private var drawObserver: ViewTreeObserver? = null
    private var awaitingAttachment = false
    private var cancelIdle: (() -> Unit)? = null
    private var preparedRowPosition: Int? = null
    val active: Boolean get() = resumed && !detached

    private val afterDraw = Runnable {
        removeDrawListener()
        removeAttachListener()
        if (active) scheduleIdle()
    }

    private val firstDrawListener = ViewTreeObserver.OnDrawListener {
        if (active && !firstDrawn) {
            firstDrawn = true
            page.post(afterDraw)
        }
    }

    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            if (!active) return
            if (firstDrawn) {
                removeAttachListener()
                scheduleIdle()
            } else {
                addDrawListener()
                if (page.isLaidOut) page.invalidate()
            }
        }

        override fun onViewDetachedFromWindow(view: View) {
            removeDrawListener()
            page.removeCallbacks(afterDraw)
        }
    }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (manager.extraLayoutSpaceAfterPx > 0 && !headerAtTop()) clearExtraSpace()
        }
    }

    fun resume() {
        if (detached || resumed) return
        resumed = true
        page.addOnScrollListener(scrollListener)
        if (!firstDrawn || !page.isAttachedToWindow) addAttachListener()
        if (firstDrawn) scheduleIdle() else {
            // A detached ViewTreeObserver can be replaced at attachment. On Android 7,
            // its draw listeners are not merged into the attached observer.
            if (page.isAttachedToWindow) addDrawListener()
            // A retained route may have drawn while STARTED, before Home resumed.
            if (page.isAttachedToWindow && page.isLaidOut) page.invalidate()
        }
    }

    fun onReadyRowsChanged() {
        if (preparedRowPosition != null && preparedRowPosition !in readyRowPositions()) {
            clearExtraSpace()
        }
        if (active && firstDrawn) scheduleIdle()
    }

    fun onPresentationChanged() {
        if (!expanded()) clearExtraSpace()
        else if (active && firstDrawn) scheduleIdle()
    }

    fun pause() {
        resumed = false
        cancelPending()
        page.removeOnScrollListener(scrollListener)
        clearExtraSpace()
    }

    fun detach() {
        pause()
        detached = true
    }

    private fun addDrawListener() {
        if (!page.isAttachedToWindow) return
        if (drawObserver === page.viewTreeObserver) return
        removeDrawListener()
        page.viewTreeObserver.takeIf { it.isAlive }?.let {
            drawObserver = it
            it.addOnDrawListener(firstDrawListener)
        }
    }

    private fun addAttachListener() {
        if (awaitingAttachment) return
        awaitingAttachment = true
        page.addOnAttachStateChangeListener(attachListener)
    }

    private fun removeAttachListener() {
        if (!awaitingAttachment) return
        awaitingAttachment = false
        page.removeOnAttachStateChangeListener(attachListener)
    }

    private fun removeDrawListener() {
        drawObserver?.takeIf { it.isAlive }?.removeOnDrawListener(firstDrawListener)
        drawObserver = null
    }

    private fun cancelPending() {
        page.removeCallbacks(afterDraw)
        removeDrawListener()
        removeAttachListener()
        cancelIdle?.invoke()
        cancelIdle = null
    }

    private fun scheduleIdle() {
        if (cancelIdle != null || manager.extraLayoutSpaceAfterPx > 0 || !canPrepare()) return
        cancelIdle = idleQueue.post(Runnable {
            cancelIdle = null
            if (canPrepare()) {
                val reveal = revealHeightPx().coerceIn(0, page.height)
                if (reveal > 0) {
                    preparedRowPosition = manager.findLastVisibleItemPosition() + 1
                    manager.extraLayoutSpaceAfterPx = reveal
                    page.requestLayout()
                }
            }
        })
    }

    private fun canPrepare(): Boolean {
        if (!active || !expanded() || !page.isAttachedToWindow || !headerAtTop()) return false
        val header = manager.findViewByPosition(0) ?: return false
        if (header.height < expandedHeaderHeightPx()) return false
        val next = manager.findLastVisibleItemPosition() + 1
        return next > 1 && next in readyRowPositions()
    }

    private fun headerAtTop(): Boolean =
        manager.findFirstVisibleItemPosition() == 0 && page.computeVerticalScrollOffset() == 0

    private fun clearExtraSpace() {
        preparedRowPosition = null
        if (manager.extraLayoutSpaceAfterPx == 0) return
        manager.extraLayoutSpaceAfterPx = 0
        if (page.isComputingLayout) page.post { page.requestLayout() }
        else page.requestLayout()
    }
}

internal fun interface HomeIdleQueue {
    /** Returns a cancellation action for pending work. */
    fun post(task: Runnable): () -> Unit
}

private val MainHomeIdleQueue = HomeIdleQueue { task ->
    val queue = Looper.myQueue()
    val handler = MessageQueue.IdleHandler {
        task.run()
        false
    }
    queue.addIdleHandler(handler)
    val cancel: () -> Unit = { queue.removeIdleHandler(handler) }
    cancel
}
