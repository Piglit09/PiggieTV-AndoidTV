package com.piggie.tv.ui.home

import kotlin.math.roundToInt

/** Home presentation is an interaction state, independent of RecyclerView scroll position. */
enum class HomeHeroPresentation { EXPANDED, COLLAPSED }

/** The one session-local authority for Home's presentation and meaningful Hero action. */
internal class HomeHeroInteractionState<Action>(initialAction: Action) {
    var presentation: HomeHeroPresentation = HomeHeroPresentation.EXPANDED
        private set
    var rememberedAction: Action = initialAction
        private set

    fun rememberAction(action: Action) {
        rememberedAction = action
    }

    fun heroDown(firstRowAvailable: Boolean): Boolean {
        if (!firstRowAvailable) return false
        presentation = HomeHeroPresentation.COLLAPSED
        return true
    }

    fun firstRowUp(heroAvailable: Boolean): Boolean {
        if (!heroAvailable) return false
        presentation = HomeHeroPresentation.EXPANDED
        return true
    }

    fun lowerRowUp(): Boolean = false
}

/** Uses the useful viewport below the floating navigation as the expanded height basis. */
internal object HomeHeroHeightPolicy {
    fun expanded(viewportPx: Int, navigationClearancePx: Int): Int {
        val clearance = navigationClearancePx.coerceAtLeast(0)
        return clearance + ((viewportPx - clearance).coerceAtLeast(0) * 0.65f).roundToInt()
    }

    fun collapsed(navigationClearancePx: Int, compactContentPx: Int): Int =
        navigationClearancePx.coerceAtLeast(0) + compactContentPx.coerceAtLeast(0)
}
