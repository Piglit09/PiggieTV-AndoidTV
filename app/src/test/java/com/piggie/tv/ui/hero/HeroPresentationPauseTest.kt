package com.piggie.tv.ui.hero

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.piggie.tv.data.models.MediaItem
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class HeroPresentationPauseTest {
    @Test
    fun collapsingCancelsPendingFocusedRowSelection() {
        val states = mutableListOf<HeroState>()
        val controller = controller(states)
        val candidates = listOf(candidate("first"), candidate("second"))
        controller.submit(HeroSource.RECOMMENDED, candidates)
        controller.onItemFocused(candidates[1])

        controller.setPresentationPaused(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

        assertEquals("first", states.last().current?.item?.id)
        controller.release()
    }

    @Test
    fun focusEventsDuringCollapseAreIgnoredAndExpandedSelectionStillWorks() {
        val states = mutableListOf<HeroState>()
        val controller = controller(states)
        val candidates = listOf(candidate("first"), candidate("second"))
        controller.submit(HeroSource.RECOMMENDED, candidates)
        controller.setPresentationPaused(true)
        controller.onItemFocused(candidates[1])
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        assertEquals("first", states.last().current?.item?.id)

        controller.setPresentationPaused(false)
        controller.onItemFocused(candidates[1])
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

        assertEquals("second", states.last().current?.item?.id)
        controller.release()
    }

    @Test
    fun collapsedPresentationStopsRotationAndExpansionResumesIt() {
        val states = mutableListOf<HeroState>()
        val controller = controller(states)
        controller.submit(HeroSource.RECOMMENDED, listOf(candidate("first"), candidate("second")))
        controller.updateVisibility(100, 0)
        controller.onResume(owner())

        controller.setPresentationPaused(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25_100))
        assertEquals("first", states.last().current?.item?.id)

        controller.setPresentationPaused(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25_100))
        assertEquals("second", states.last().current?.item?.id)
        controller.release()
    }

    @Test
    fun poolAuthorityStillUpdatesWhilePresentationIsCollapsed() {
        val states = mutableListOf<HeroState>()
        val controller = controller(states)
        controller.submit(HeroSource.RECOMMENDED, listOf(candidate("first")))
        controller.setPresentationPaused(true)

        controller.submit(HeroSource.CONTINUE, listOf(candidate("resume", HeroSource.CONTINUE, 1L)))

        assertEquals("resume", states.last().current?.item?.id)
        controller.release()
    }

    private fun controller(states: MutableList<HeroState>) = HeroController(
        route = HeroRoute.HOME,
        reduceMotion = { false },
        onStateChanged = { states.add(it) }
    )

    private fun owner() = object : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }

    private fun candidate(id: String, source: HeroSource = HeroSource.RECOMMENDED, position: Long = 0L) =
        HeroCandidate(
            item = MediaItem(
                id = id, title = id, type = "Movie", year = null, imageTag = null,
                seriesName = null, episodeLabel = null, playbackPositionTicks = position,
                runtimeTicks = 100L
            ),
            source = source
        )
}
