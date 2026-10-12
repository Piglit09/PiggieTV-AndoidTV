package com.piggie.tv.ui.home

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.piggie.tv.ui.layout.TvLinearLayoutManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-xhdpi")
class HomeCollapseShelfWarmupTest {
    @Test
    fun firstDrawAndIdlePrepareOnlyCollapseRevealWithoutMovingFocusOrRecreatingCards() {
        val rig = rig()
        assertEquals(0, rig.createdCards())
        assertEquals(null, rig.page.findViewHolderForAdapterPosition(2))
        assertEquals(0, rig.manager.extraLayoutSpaceAfterPx)
        assertTrue(rig.page.isAttachedToWindow)
        assertEquals(0, rig.manager.findFirstVisibleItemPosition())
        assertEquals(1, rig.manager.findLastVisibleItemPosition())
        assertTrue(rig.header.requestFocus())
        val firstCard = rig.firstCard
        val header = rig.page.findViewHolderForAdapterPosition(0)?.itemView

        rig.warmup.resume()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("initial view is not warmed before its first draw", 0, rig.createdCards())
        assertEquals(0, rig.manager.extraLayoutSpaceAfterPx)

        rig.drawFirstFrame()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("draw should schedule idle preparation", rig.idle.hasPending())
        rig.idle.runPending()
        rig.layout()
        assertEquals(400, rig.manager.extraLayoutSpaceAfterPx)
        assertEquals(3, rig.createdCards())
        assertNotNull(rig.page.findViewHolderForAdapterPosition(2))
        assertEquals("do not prepare beyond the compact reveal", null,
            rig.page.findViewHolderForAdapterPosition(3))
        assertSame(header, rig.page.findViewHolderForAdapterPosition(0)?.itemView)
        assertSame(firstCard, rig.page.findViewHolderForAdapterPosition(1)?.itemView)
        assertTrue(rig.header.hasFocus())
        assertEquals(0, rig.page.computeVerticalScrollOffset())

        rig.header.layoutParams = rig.header.layoutParams.apply { height = 344 }
        rig.expanded = false
        rig.warmup.onPresentationChanged()
        assertEquals("compact mode has no additional layout space", 0,
            rig.manager.extraLayoutSpaceAfterPx)
        rig.layout()
        assertEquals("collapse reuses prepared cards", 3, rig.createdCards())
        assertTrue(rig.header.hasFocus())
        assertEquals(0, rig.page.computeVerticalScrollOffset())
        rig.warmup.detach()
        rig.activity.finish()
    }

    @Test
    fun pauseCancelsPendingWarmupAndNoReadyRevealRowDoesNoWork() {
        val rig = rig()
        rig.warmup.resume()
        rig.drawFirstFrame()
        rig.warmup.pause()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        rig.idle.runPending()
        rig.layout()
        assertEquals(0, rig.manager.extraLayoutSpaceAfterPx)
        assertEquals(0, rig.createdCards())

        rig.readyRows.clear()
        rig.warmup.resume()
        rig.drawFirstFrame()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        rig.idle.runPending()
        rig.layout()
        assertEquals(0, rig.manager.extraLayoutSpaceAfterPx)
        assertEquals(0, rig.createdCards())

        rig.warmup.detach()
        rig.readyRows.add(2)
        rig.warmup.onReadyRowsChanged()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        rig.idle.runPending()
        rig.layout()
        assertFalse(rig.warmup.active)
        assertEquals(0, rig.createdCards())
        rig.activity.finish()
    }

    @Test
    fun pausedRetainedHomeRearmsReadyRowButScrollingAwayClearsExtraSpace() {
        val rig = rig()
        rig.warmup.resume()
        rig.drawFirstFrame()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        rig.warmup.pause()
        rig.idle.runPending()
        assertEquals(0, rig.manager.extraLayoutSpaceAfterPx)

        rig.warmup.resume()
        assertTrue("resume should rearm idle preparation", rig.idle.hasPending())
        rig.idle.runPending()
        rig.layout()
        assertEquals(400, rig.manager.extraLayoutSpaceAfterPx)
        rig.page.scrollBy(0, 120)
        assertEquals(0, rig.manager.extraLayoutSpaceAfterPx)
        rig.warmup.detach()
        rig.activity.finish()
    }

    @Test
    fun expandedStateDoesNotPrepareRowsUntilHeaderHasExpandedBounds() {
        val rig = rig()
        rig.header.layoutParams = rig.header.layoutParams.apply { height = 344 }
        rig.layout()
        rig.warmup.resume()
        rig.drawFirstFrame()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        rig.idle.runPending()
        assertEquals(0, rig.manager.extraLayoutSpaceAfterPx)

        rig.header.layoutParams = rig.header.layoutParams.apply { height = 744 }
        rig.layout()
        rig.warmup.onPresentationChanged()
        rig.idle.runPending()
        rig.layout()
        assertEquals(400, rig.manager.extraLayoutSpaceAfterPx)
        rig.warmup.detach()
        rig.activity.finish()
    }

    @Test
    fun warmedRowLosingReadyStateRemovesExtraSpaceBeforeAnotherRowCanAttach() {
        val rig = rig()
        rig.warmup.resume()
        rig.drawFirstFrame()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        rig.idle.runPending()
        rig.layout()
        assertEquals(400, rig.manager.extraLayoutSpaceAfterPx)
        assertEquals(3, rig.createdCards())

        rig.readyRows.remove(2)
        rig.warmup.onReadyRowsChanged()
        assertEquals("an unavailable shelf cannot retain reveal allowance", 0,
            rig.manager.extraLayoutSpaceAfterPx)
        rig.layout()
        assertEquals(null, rig.page.findViewHolderForAdapterPosition(3))
        assertEquals(3, rig.createdCards())
        rig.warmup.detach()
        rig.activity.finish()
    }

    @Test
    @Config(sdk = [25])
    fun android7ResumeBeforeAttachmentObservesAttachedTree() =
        verifyResumeBeforePageAttachmentObservesFirstDrawOnAttachedTree()

    @Test
    @Config(sdk = [30])
    fun android11ResumeBeforeAttachmentObservesAttachedTree() =
        verifyResumeBeforePageAttachmentObservesFirstDrawOnAttachedTree()

    private fun verifyResumeBeforePageAttachmentObservesFirstDrawOnAttachedTree() {
        val rig = rig(attachPageAtStart = false)
        assertFalse(rig.page.isAttachedToWindow)
        rig.warmup.resume()
        assertEquals("no draw observer may be stored on the detached tree", null,
            rig.warmup.drawObserverForTest())

        rig.attachPage()
        assertSame(rig.page.viewTreeObserver, rig.warmup.drawObserverForTest())
        assertEquals(0, rig.manager.extraLayoutSpaceAfterPx)
        rig.drawFirstFrame()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(rig.idle.hasPending())
        rig.idle.runPending()
        rig.layout()
        assertEquals(400, rig.manager.extraLayoutSpaceAfterPx)
        assertEquals(3, rig.createdCards())
        rig.warmup.detach()
        rig.activity.finish()
    }

    @Test
    @Config(sdk = [25])
    fun android7PauseBeforeAttachmentCancelsAndRearms() =
        verifyPauseBeforeAttachmentCancelsDrawAndRetainedResumeRearmsIt()

    @Test
    @Config(sdk = [30])
    fun android11PauseBeforeAttachmentCancelsAndRearms() =
        verifyPauseBeforeAttachmentCancelsDrawAndRetainedResumeRearmsIt()

    private fun verifyPauseBeforeAttachmentCancelsDrawAndRetainedResumeRearmsIt() {
        val rig = rig(attachPageAtStart = false)
        rig.warmup.resume()
        rig.warmup.pause()
        rig.attachPage()
        rig.drawFirstFrame()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        rig.idle.runPending()
        rig.layout()
        assertEquals(0, rig.manager.extraLayoutSpaceAfterPx)

        rig.warmup.resume()
        assertSame(rig.page.viewTreeObserver, rig.warmup.drawObserverForTest())
        rig.drawFirstFrame()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        rig.idle.runPending()
        rig.layout()
        assertEquals(400, rig.manager.extraLayoutSpaceAfterPx)
        rig.warmup.detach()
        rig.activity.finish()
    }

    private fun rig(attachPageAtStart: Boolean = true): Rig {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val manager = TvLinearLayoutManager(activity, RecyclerView.VERTICAL, false)
        val page = RecyclerView(activity).apply {
            layoutManager = manager
            itemAnimator = null
            isFocusable = false
        }
        val header = Button(activity).apply {
            text = "Hero action"
            layoutParams = RecyclerView.LayoutParams(-1, 744)
        }
        val firstCard = Button(activity).apply {
            text = "Continue Watching"
            layoutParams = RecyclerView.LayoutParams(-1, 400)
        }
        val readyRows = mutableSetOf(2)
        val idle = ManualIdleQueue()
        val presentation = booleanArrayOf(true)
        var createdCards = 0
        val nextRow = FrameLayout(activity).apply {
            layoutParams = RecyclerView.LayoutParams(-1, 388)
            val horizontal = RecyclerView(activity).apply {
                layoutManager = TvLinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
                adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun getItemCount() = 3
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                        createdCards++
                        return object : RecyclerView.ViewHolder(View(activity).apply {
                            layoutParams = RecyclerView.LayoutParams(368, 264)
                        }) {}
                    }
                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
                }
            }
            addView(horizontal, FrameLayout.LayoutParams(-1, -1))
        }
        val fartherRow = View(activity).apply { layoutParams = RecyclerView.LayoutParams(-1, 388) }
        page.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 4
            override fun getItemViewType(position: Int) = position
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                object : RecyclerView.ViewHolder(
                    when (viewType) { 0 -> header; 1 -> firstCard; 2 -> nextRow; else -> fartherRow }
                ) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
        if (attachPageAtStart) activity.setContentView(page)
        val warmup = HomeCollapseShelfWarmup(
            page = page,
            manager = manager,
            revealHeightPx = { 400 },
            expandedHeaderHeightPx = { 744 },
            readyRowPositions = { readyRows },
            expanded = { presentation[0] },
            idleQueue = idle
        )
        val rig = Rig(activity, page, manager, header, firstCard, readyRows, warmup, idle,
            presentation) { createdCards }
        rig.layout()
        return rig
    }

    private class Rig(
        val activity: Activity,
        val page: RecyclerView,
        val manager: TvLinearLayoutManager,
        val header: View,
        val firstCard: Button,
        val readyRows: MutableSet<Int>,
        val warmup: HomeCollapseShelfWarmup,
        val idle: ManualIdleQueue,
        val presentation: BooleanArray,
        val createdCards: () -> Int
    ) {
        var expanded: Boolean
            get() = presentation[0]
            set(value) { presentation[0] = value }
        fun layout() {
            page.measure(View.MeasureSpec.makeMeasureSpec(1_920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1_080, View.MeasureSpec.EXACTLY))
            page.layout(0, 0, 1_920, 1_080)
        }

        fun attachPage() {
            activity.setContentView(page)
            layout()
        }

        fun drawFirstFrame() {
            page.draw(Canvas(Bitmap.createBitmap(1_920, 1_080, Bitmap.Config.ARGB_8888)))
            page.viewTreeObserver.dispatchOnDraw()
        }
    }

    private fun HomeCollapseShelfWarmup.drawObserverForTest(): ViewTreeObserver? =
        HomeCollapseShelfWarmup::class.java.getDeclaredField("drawObserver")
            .apply { isAccessible = true }.get(this) as? ViewTreeObserver

    private class ManualIdleQueue : HomeIdleQueue {
        private var pending: Runnable? = null
        fun hasPending() = pending != null

        override fun post(task: Runnable): () -> Unit {
            pending = task
            return { if (pending === task) pending = null }
        }

        fun runPending() {
            val task = pending
            pending = null
            task?.run()
        }
    }
}
