package com.piggie.tv.ui.home

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.piggie.tv.R
import com.piggie.tv.ui.hero.HeroController
import com.piggie.tv.ui.hero.HeroFocusAction
import com.piggie.tv.ui.hero.HeroRowLayoutContract
import com.piggie.tv.ui.hero.HeroRowView
import com.piggie.tv.ui.layout.TvLinearLayoutManager
import com.piggie.tv.util.dim
import kotlin.math.roundToInt

/** Owns only Home's attached Hero bounds and the Hero/first-shelf D-pad boundary. */
internal class HomeHeroCoordinator(
    private val page: RecyclerView,
    private val hero: HeroRowView,
    private val heroController: HeroController,
    private val state: HomeHeroInteractionState<HeroFocusAction>,
    private val reduceMotion: () -> Boolean,
    private val firstInteractivePosition: () -> Int? = { null },
    private val readyRowPositions: () -> Set<Int> = { emptySet() }
) {
    private var animator: ValueAnimator? = null
    private var viewportHeight = 0
    private var firstBrowseRowPosition = 1
    private var attached = false
    private var lastAppliedPresentation: HomeHeroPresentation? = null
    private var focusGeneration = 0
    private var pendingRowPosition: Int? = null
    private val rowWarmup = (page.layoutManager as? TvLinearLayoutManager)?.let { manager ->
        HomeCollapseShelfWarmup(
            page = page,
            manager = manager,
            revealHeightPx = ::collapseRevealHeight,
            expandedHeaderHeightPx = ::expandedTargetHeight,
            readyRowPositions = readyRowPositions,
            expanded = { state.presentation == HomeHeroPresentation.EXPANDED }
        )
    }

    val presentation: HomeHeroPresentation get() = state.presentation
    val animationActive: Boolean get() = animator?.isRunning == true
    var transitionGeneration: Int = 0
        private set

    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
        val height = page.height
        if (height > 0 && (height != viewportHeight || bottom != oldBottom)) {
            viewportHeight = height
            applyPresentation(animate = false)
        }
    }

    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, focused ->
        hero.homeActionFor(focused)?.let(state::rememberAction)
    }

    fun attach() {
        if (attached) return
        attached = true
        page.addOnLayoutChangeListener(layoutListener)
        page.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        viewportHeight = page.height
        applyPresentation(animate = false)
    }

    fun detach() {
        if (!attached) return
        attached = false
        cancelPendingFocus()
        rowWarmup?.detach()
        finishAnimation()
        page.removeOnLayoutChangeListener(layoutListener)
        if (page.viewTreeObserver.isAlive) {
            page.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        }
    }

    fun resumeForWarmup() {
        rowWarmup?.resume()
    }

    fun onReadyRowsChanged() {
        rowWarmup?.onReadyRowsChanged()
    }

    fun bindHeader(container: FrameLayout) {
        val height = targetHeight()
        HeroRowLayoutContract.bind(container, hero, height)
        HeroRowLayoutContract.resize(container, hero, height)
        hero.setHomePresentation(state.presentation)
        heroController.setPresentationPaused(state.presentation == HomeHeroPresentation.COLLAPSED)
    }

    /** Called by the Home page's dispatch path before normal focus navigation. */
    fun handleKeyEvent(event: KeyEvent, focused: View?): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || focused == null) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> downFromHero(focused)
            KeyEvent.KEYCODE_DPAD_UP -> upFromFirstBrowseRow(focused)
            else -> false
        }
    }

    fun settleForPause() {
        rowWarmup?.pause()
        if (pendingRowPosition != null) rollbackPendingFocus(restoreFocus = false)
        else cancelPendingFocus()
        finishAnimation()
        heroController.setPresentationPaused(state.presentation == HomeHeroPresentation.COLLAPSED)
    }

    private fun downFromHero(focused: View): Boolean {
        val action = hero.homeActionFor(focused) ?: return false
        state.rememberAction(action)
        val position = firstInteractivePosition()
            ?: firstAttachedFocusableRowPosition()
            ?: return true
        if (position !in 1 until (page.adapter?.itemCount ?: 0)) return true
        cancelPendingFocus()
        firstBrowseRowPosition = position
        state.heroDown(firstRowAvailable = true)
        applyPresentation(animate = true)
        anchorHeaderIfFirstRow(position)
        val target = focusTargetAt(position)
        if (target?.requestFocus() == true) {
            anchorHeaderIfFirstRow(position)
        } else {
            pendingRowPosition = position
            val generation = focusGeneration
            page.postOnAnimation { resolvePendingFocus(generation, position, 0) }
        }
        return true
    }

    private fun upFromFirstBrowseRow(focused: View): Boolean {
        val heroAction = hero.homeActionFor(focused)
        if (heroAction != null && state.presentation == HomeHeroPresentation.COLLAPSED) {
            cancelPendingFocus()
            state.rememberAction(heroAction)
            state.firstRowUp(heroAvailable = true)
            applyPresentation(animate = true)
            anchorHeader()
            return true
        }
        val position = page.findContainingViewHolder(focused)?.bindingAdapterPosition
            ?: return false
        val firstInteractive = firstInteractivePosition() ?: firstBrowseRowPosition
        if (position != firstInteractive || position < 1) return state.lowerRowUp()
        if (!hero.homeActionsAvailable()) return true
        cancelPendingFocus()
        state.firstRowUp(heroAvailable = true)
        applyPresentation(animate = true)
        anchorHeader()
        restoreHeroFocus(focusGeneration, 0)
        return true
    }

    private fun firstAttachedFocusableRowPosition(): Int? {
        val count = page.adapter?.itemCount ?: return null
        for (position in 1 until count) {
            if (focusTargetAt(position) != null) return position
        }
        return null
    }

    private fun focusTargetAt(position: Int): View? {
        val row = page.findViewHolderForAdapterPosition(position)?.itemView ?: return null
        val focusables = ArrayList<View>()
        row.addFocusables(focusables, View.FOCUS_DOWN, View.FOCUSABLES_ALL)
        return focusables.firstOrNull { it.isShown && it.isEnabled }
    }

    private fun resolvePendingFocus(generation: Int, position: Int, attempt: Int) {
        if (!attached || generation != focusGeneration || pendingRowPosition != position) return
        val target = focusTargetAt(position)
        if (target?.requestFocus() == true) {
            pendingRowPosition = null
            anchorHeaderIfFirstRow(position)
            return
        }
        if (attempt >= MAX_FOCUS_LAYOUT_ATTEMPTS) {
            rollbackPendingFocus()
            return
        }
        if (attempt == 1) {
            (page.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(position, page.height / 3)
        }
        page.postOnAnimation { resolvePendingFocus(generation, position, attempt + 1) }
    }

    private fun rollbackPendingFocus(restoreFocus: Boolean = true) {
        cancelPendingFocus()
        state.firstRowUp(heroAvailable = true)
        applyPresentation(animate = false)
        anchorHeader()
        if (restoreFocus) restoreHeroFocus(focusGeneration, 0)
    }

    private fun restoreHeroFocus(generation: Int, attempt: Int) {
        if (!attached || generation != focusGeneration ||
            state.presentation != HomeHeroPresentation.EXPANDED
        ) return
        if (hero.isAttachedToWindow && hero.requestHomeActionFocus(state.rememberedAction)) return
        if (attempt >= MAX_FOCUS_LAYOUT_ATTEMPTS) return
        anchorHeader()
        page.postOnAnimation { restoreHeroFocus(generation, attempt + 1) }
    }

    private fun cancelPendingFocus() {
        focusGeneration++
        pendingRowPosition = null
    }

    private fun anchorHeaderIfFirstRow(position: Int) {
        if (position == 1) anchorHeader()
    }

    private fun anchorHeader() {
        (page.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
    }

    private fun applyPresentation(animate: Boolean) {
        if (lastAppliedPresentation != state.presentation) {
            lastAppliedPresentation = state.presentation
            transitionGeneration++
        }
        hero.setHomePresentation(state.presentation)
        heroController.setPresentationPaused(state.presentation == HomeHeroPresentation.COLLAPSED)
        rowWarmup?.onPresentationChanged()
        val container = hero.parent as? FrameLayout ?: return
        val target = targetHeight()
        val start = container.layoutParams?.height ?: target
        animator?.cancel()
        animator = null
        if (!animate || reduceMotion() || !container.isAttachedToWindow || start == target) {
            HeroRowLayoutContract.resize(container, hero, target)
            return
        }
        animator = ValueAnimator.ofInt(start, target).apply {
            duration = TRANSITION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                if (hero.parent === container) {
                    HeroRowLayoutContract.resize(container, hero, it.animatedValue as Int)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) {
                        page.post { rowWarmup?.onPresentationChanged() }
                    }
                }
            })
            start()
        }
    }

    private fun finishAnimation() {
        animator?.cancel()
        animator = null
        (hero.parent as? FrameLayout)?.let { HeroRowLayoutContract.resize(it, hero, targetHeight()) }
    }

    private fun targetHeight(): Int {
        val clearance = page.context.dim(R.dimen.tv_floating_nav_clearance)
        val compactContent = (COMPACT_CONTENT_DP * page.resources.displayMetrics.density).roundToInt()
        return when (state.presentation) {
            HomeHeroPresentation.EXPANDED -> {
                val viewport = viewportHeight.takeIf { it > 0 }
                    ?: page.resources.displayMetrics.heightPixels
                HomeHeroHeightPolicy.expanded(viewport, clearance)
            }
            HomeHeroPresentation.COLLAPSED -> HomeHeroHeightPolicy.collapsed(
                clearance,
                compactContent
            )
        }
    }

    private fun collapseRevealHeight(): Int {
        val clearance = page.context.dim(R.dimen.tv_floating_nav_clearance)
        val compactContent = (COMPACT_CONTENT_DP * page.resources.displayMetrics.density).roundToInt()
        return (expandedTargetHeight() -
            HomeHeroHeightPolicy.collapsed(clearance, compactContent)).coerceAtLeast(0)
    }

    private fun expandedTargetHeight(): Int {
        val clearance = page.context.dim(R.dimen.tv_floating_nav_clearance)
        val viewport = viewportHeight.takeIf { it > 0 }
            ?: page.resources.displayMetrics.heightPixels
        return HomeHeroHeightPolicy.expanded(viewport, clearance)
    }

    private companion object {
        const val TRANSITION_MS = 250L
        const val COMPACT_CONTENT_DP = 112
        const val MAX_FOCUS_LAYOUT_ATTEMPTS = 3
    }
}
