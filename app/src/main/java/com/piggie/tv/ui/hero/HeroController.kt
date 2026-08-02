package com.piggie.tv.ui.hero

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import com.piggie.tv.diagnostics.PtvRedactor

class HeroController(
    private val route: HeroRoute,
    private val reduceMotion: () -> Boolean,
    private val onStateChanged: (HeroState) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis
) : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private data class CandidateBatch(
        val source: HeroSource,
        val candidates: List<HeroCandidate>
    )

    private val candidateBatches = linkedMapOf<String, CandidateBatch>()
    private var state = HeroState(route = route)
    private var lifecycleResumed = false
    private var controlsFocused = false
    private var pendingFocus: HeroCandidate? = null
    private var lastVisible = false
    private var lastVisibilityScrollSample = Int.MIN_VALUE

    private val focusRunnable = Runnable {
        val candidate = pendingFocus ?: return@Runnable
        pendingFocus = null
        val index = state.pool.indexOfFirst { it.item.id == candidate.item.id }
        if (index >= 0 && !controlsFocused) {
            select(index, HeroChangeReason.STABLE_FOCUS)
        }
    }

    private val rotationRunnable = Runnable {
        if (!canRotate()) return@Runnable
        val next = HeroPoolPolicy.nextIndex(state.selectedIndex, state.pool.size)
        if (next >= 0) select(next, HeroChangeReason.ROTATION)
    }

    fun submit(
        source: HeroSource,
        items: List<HeroCandidate>,
        key: String = source.name
    ) {
        candidateBatches[key] = CandidateBatch(
            source,
            items
            .asSequence()
            .filter { it.source == source }
            .distinctBy { it.item.id }
            .take(HeroPoolPolicy.MAX_POOL_SIZE)
            .toList()
        )
        rebuildPool()
    }

    fun submitItems(
        source: HeroSource,
        items: List<com.piggie.tv.data.models.MediaItem>,
        key: String = source.name
    ) {
        submit(source, items.map { HeroCandidate(it, source) }, key)
    }

    fun onItemFocused(candidate: HeroCandidate?) {
        handler.removeCallbacks(focusRunnable)
        pendingFocus = candidate?.takeIf { HeroPoolPolicy.accepts(route, it.source, it.item) }
        if (pendingFocus != null) {
            handler.postDelayed(focusRunnable, HeroRotationPolicy.FOCUS_DEBOUNCE_MS)
        }
    }

    fun setControlsFocused(focused: Boolean) {
        if (controlsFocused == focused) return
        controlsFocused = focused
        if (focused) {
            handler.removeCallbacks(rotationRunnable)
            handler.removeCallbacks(focusRunnable)
            pendingFocus = null
        } else {
            scheduleRotation()
        }
    }

    fun updateVisibility(visiblePercent: Int, scrollPositionPx: Int) {
        val bounded = visiblePercent.coerceIn(0, 100)
        state = state.copy(visiblePercent = bounded, scrollPositionPx = scrollPositionPx)
        val visible = bounded > 0
        val shouldRecord = visible != lastVisible ||
            lastVisibilityScrollSample == Int.MIN_VALUE ||
            kotlin.math.abs(scrollPositionPx - lastVisibilityScrollSample) >= VISIBILITY_SCROLL_SAMPLE_PX
        if (shouldRecord) {
            lastVisible = visible
            lastVisibilityScrollSample = scrollPositionPx
            PtvDiagnosticsManager.event(
                "hero",
                "visibility",
                mapOf(
                    "route" to route.name.lowercase(),
                    "visiblePercent" to bounded.toString(),
                    "scrollPositionPx" to scrollPositionPx.toString(),
                    "itemIndex" to state.selectedIndex.toString(),
                    "itemId" to masked(state.current?.item?.id),
                    "lastChangeTimestampMs" to state.lastChangeTimestampMs.toString()
                )
            )
        }
        scheduleRotation()
    }

    fun refresh() {
        if (state.pool.size < 2) return
        val next = HeroPoolPolicy.nextIndex(state.selectedIndex, state.pool.size)
        if (next >= 0) select(next, HeroChangeReason.REFRESH)
    }

    fun recordImageResult(itemId: String, loadMs: Long, failure: String?) {
        PtvDiagnosticsManager.event(
            "hero",
            "image",
            mapOf(
                "route" to route.name.lowercase(),
                "itemId" to masked(itemId),
                "loadMs" to loadMs.toString(),
                "failure" to (failure ?: "none")
            )
        )
    }

    override fun onResume(owner: LifecycleOwner) {
        lifecycleResumed = true
        scheduleRotation()
    }

    override fun onPause(owner: LifecycleOwner) {
        lifecycleResumed = false
        handler.removeCallbacks(rotationRunnable)
        handler.removeCallbacks(focusRunnable)
        pendingFocus = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        release()
    }

    fun release() {
        handler.removeCallbacks(rotationRunnable)
        handler.removeCallbacks(focusRunnable)
        pendingFocus = null
        lifecycleResumed = false
        controlsFocused = false
        candidateBatches.clear()
        state = HeroState(route = route)
        lastVisible = false
        lastVisibilityScrollSample = Int.MIN_VALUE
    }

    private fun rebuildPool() {
        val old = state.current
        val previousPool = state.pool
        val candidatesBySource = candidateBatches.values
            .groupBy(CandidateBatch::source)
            .mapValues { (_, batches) -> batches.flatMap(CandidateBatch::candidates) }
        val pool = HeroPoolPolicy.build(route, candidatesBySource)
        if (pool.isEmpty()) {
            val previous = old?.item?.id
            state = state.copy(
                pool = emptyList(),
                current = null,
                selectedIndex = -1,
                previousItemId = previous,
                changeReason = HeroChangeReason.UNAVAILABLE,
                lastChangeTimestampMs = nowMs()
            )
            onStateChanged(state)
            recordSelection()
            handler.removeCallbacks(rotationRunnable)
            return
        }

        val selectedIndex = HeroSelectionPolicy.indexAfterPoolUpdate(
            route = route,
            previousPool = previousPool,
            updatedPool = pool,
            currentItemId = old?.item?.id
        )
        val selected = pool[selectedIndex]
        val changed = old?.item?.id != selected.item.id
        state = state.copy(
            pool = pool,
            current = selected,
            selectedIndex = selectedIndex,
            previousItemId = if (changed) old?.item?.id else state.previousItemId,
            changeReason = if (changed) {
                if (old == null) HeroChangeReason.INITIAL else HeroChangeReason.POOL_UPDATE
            } else {
                state.changeReason
            },
            lastChangeTimestampMs = if (changed) nowMs() else state.lastChangeTimestampMs
        )
        onStateChanged(state)
        if (changed) recordSelection()
        scheduleRotation()
    }

    private fun select(index: Int, reason: HeroChangeReason) {
        if (index !in state.pool.indices) return
        val selected = state.pool[index]
        if (selected.item.id == state.current?.item?.id) {
            scheduleRotation()
            return
        }
        val previous = state.current?.item?.id
        state = state.copy(
            current = selected,
            selectedIndex = index,
            previousItemId = previous,
            changeReason = reason,
            lastChangeTimestampMs = nowMs()
        )
        onStateChanged(state)
        recordSelection()
        scheduleRotation()
    }

    private fun scheduleRotation() {
        handler.removeCallbacks(rotationRunnable)
        if (canRotate()) {
            handler.postDelayed(rotationRunnable, HeroRotationPolicy.ROTATION_INTERVAL_MS)
        }
    }

    private fun canRotate(): Boolean = HeroRotationPolicy.shouldSchedule(
        poolSize = state.pool.size,
        visiblePercent = state.visiblePercent,
        lifecycleResumed = lifecycleResumed,
        controlsFocused = controlsFocused,
        reduceMotion = reduceMotion()
    )

    private fun recordSelection() {
        PtvDiagnosticsManager.event(
            "hero",
            "selection",
            mapOf(
                "route" to route.name.lowercase(),
                "poolSize" to state.pool.size.toString(),
                "itemIndex" to state.selectedIndex.toString(),
                "itemId" to masked(state.current?.item?.id),
                "previousItemId" to masked(state.previousItemId),
                "reason" to state.changeReason.wireName,
                "selectionBasis" to
                    (
                        state.current?.diagnosticSelectionBasis
                            ?: state.current?.source?.name?.lowercase()
                            ?: "none"
                        ),
                "lastChangeTimestampMs" to state.lastChangeTimestampMs.toString()
            )
        )
    }

    private fun masked(value: String?): String = PtvRedactor.identifier(value) ?: "none"

    private companion object {
        const val VISIBILITY_SCROLL_SAMPLE_PX = 64
    }
}
