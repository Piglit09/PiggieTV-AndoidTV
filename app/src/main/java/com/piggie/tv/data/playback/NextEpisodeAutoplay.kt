package com.piggie.tv.data.playback

import com.piggie.tv.data.models.MediaItem

const val UP_NEXT_THRESHOLD_MS = 25_000L

enum class AutoplayTrigger {
    COUNTDOWN,
    PLAY_NOW,
    ENDED
}

enum class UpNextOverlayAction {
    PLAY_NOW,
    CANCEL_AUTOPLAY,
    CLOSE
}

data class UpNextActionEffect(
    val trigger: AutoplayTrigger? = null,
    val cancelAutoplay: Boolean = false,
    val dismissOnly: Boolean = false
)

data class AutoplayGuardState(
    val enabled: Boolean,
    val canceled: Boolean,
    val hasNextItem: Boolean,
    val appForeground: Boolean,
    val playbackError: Boolean,
    val manuallyStoppedEarly: Boolean,
    val transitionAlreadyStarted: Boolean
)

object NextEpisodeAutoplayPolicy {
    fun endTrigger(overlayWasShown: Boolean, overlayDismissed: Boolean): AutoplayTrigger =
        if (overlayWasShown && !overlayDismissed) AutoplayTrigger.COUNTDOWN else AutoplayTrigger.ENDED

    fun effect(action: UpNextOverlayAction): UpNextActionEffect = when (action) {
        UpNextOverlayAction.PLAY_NOW -> UpNextActionEffect(trigger = AutoplayTrigger.PLAY_NOW)
        UpNextOverlayAction.CANCEL_AUTOPLAY -> UpNextActionEffect(cancelAutoplay = true)
        UpNextOverlayAction.CLOSE -> UpNextActionEffect(dismissOnly = true)
    }

    fun shouldShowOverlay(
        remainingMs: Long,
        state: AutoplayGuardState,
        overlayAlreadyShown: Boolean
    ): Boolean = remainingMs in 1..UP_NEXT_THRESHOLD_MS &&
        state.enabled &&
        state.hasNextItem &&
        !state.canceled &&
        !state.playbackError &&
        !overlayAlreadyShown

    fun shouldStart(trigger: AutoplayTrigger, state: AutoplayGuardState): Boolean {
        if (!state.hasNextItem || state.playbackError || state.manuallyStoppedEarly || state.transitionAlreadyStarted) {
            return false
        }
        if (trigger == AutoplayTrigger.PLAY_NOW) return true
        return state.enabled && !state.canceled && state.appForeground
    }
}

/** Selects the next episode from Jellyfin's ordered episode response. */
object NextEpisodeSelector {
    fun select(currentItemId: String, orderedEpisodes: List<MediaItem>): MediaItem? {
        val currentIndex = orderedEpisodes.indexOfFirst { it.id == currentItemId }
        if (currentIndex < 0) return null
        return orderedEpisodes
            .asSequence()
            .drop(currentIndex + 1)
            .firstOrNull { it.type.equals("Episode", ignoreCase = true) && it.id != currentItemId }
    }
}

/** Selects the previous episode from Jellyfin's ordered, series-scoped episode response. */
object PreviousEpisodeSelector {
    fun select(currentItemId: String, orderedEpisodes: List<MediaItem>): MediaItem? {
        val currentIndex = orderedEpisodes.indexOfFirst { it.id == currentItemId }
        if (currentIndex <= 0) return null
        return orderedEpisodes
            .asSequence()
            .take(currentIndex)
            .filter { it.type.equals("Episode", ignoreCase = true) && it.id != currentItemId }
            .lastOrNull()
    }
}
