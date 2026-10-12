package com.piggie.tv.ui.home

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.ui.hero.HeroCandidate
import com.piggie.tv.ui.hero.HeroController
import com.piggie.tv.ui.hero.HeroFocusAction
import com.piggie.tv.ui.hero.HeroRoute
import com.piggie.tv.ui.hero.HeroRowView
import com.piggie.tv.ui.hero.HeroSource
import com.piggie.tv.ui.hero.HeroState
import com.piggie.tv.ui.layout.TvLinearLayoutManager
import com.piggie.tv.util.dim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-xhdpi")
class HomeHeroCoordinatorTest {
    @Test
    fun downAndUpKeepHeaderAndRowMountedWhileRestoringDetailsFocus() {
        val rig = rig(reduceMotion = true)
        val details = rig.hero.findViewById<View>(R.id.hero_details_action)
        assertTrue(details.requestFocus())
        val header = rig.page.findViewHolderForAdapterPosition(0)?.itemView
        val firstRow = rig.page.findViewHolderForAdapterPosition(1)?.itemView

        assertTrue(rig.coordinator.handleKeyEvent(down(), details))
        assertEquals(HomeHeroPresentation.COLLAPSED, rig.coordinator.presentation)
        assertTrue(rig.firstCard.hasFocus())
        assertEquals(1, rig.page.findContainingViewHolder(rig.firstCard)?.bindingAdapterPosition)
        assertSame(header, rig.page.findViewHolderForAdapterPosition(0)?.itemView)
        assertSame(firstRow, rig.page.findViewHolderForAdapterPosition(1)?.itemView)
        assertFalse(rig.coordinator.animationActive)

        assertTrue(rig.coordinator.handleKeyEvent(up(), rig.firstCard))
        assertEquals(HomeHeroPresentation.EXPANDED, rig.coordinator.presentation)
        assertTrue(details.hasFocus())
        assertSame(rig.hero, rig.page.findViewHolderForAdapterPosition(0)?.itemView?.let {
            (it as ViewGroup).getChildAt(0)
        })
        rig.activity.finish()
    }

    @Test
    fun rapidRetargetFinishesAtLastModeWithoutRunningAnimation() {
        val rig = rig(reduceMotion = false)
        val primary = rig.hero.findViewById<View>(R.id.hero_primary_action)
        assertTrue(primary.requestFocus())

        rig.coordinator.handleKeyEvent(down(), primary)
        rig.coordinator.handleKeyEvent(up(), rig.firstCard)
        rig.coordinator.handleKeyEvent(down(), primary)
        rig.coordinator.handleKeyEvent(up(), rig.firstCard)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS)

        assertEquals(HomeHeroPresentation.EXPANDED, rig.coordinator.presentation)
        assertFalse(rig.coordinator.animationActive)
        assertTrue(primary.hasFocus())
        assertEquals(5, rig.coordinator.transitionGeneration)
        rig.activity.finish()
    }

    @Test
    fun unavailableAttachedRowIsSearchedAfterLayoutAndRapidUpCancelsStaleDown() {
        val rig = rig(reduceMotion = true, firstRowFocusable = false)
        val primary = rig.hero.findViewById<View>(R.id.hero_primary_action)
        assertTrue(primary.requestFocus())
        assertEquals(null, rig.page.findViewHolderForAdapterPosition(2))

        assertTrue(rig.coordinator.handleKeyEvent(down(), primary))
        assertTrue(rig.coordinator.handleKeyEvent(up(), primary))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(HomeHeroPresentation.EXPANDED, rig.coordinator.presentation)
        assertTrue(primary.hasFocus())
        assertFalse(rig.coordinator.animationActive)
        rig.activity.finish()
    }

    @Test
    fun loadedOffscreenRowReceivesFocusAfterBoundedLayout() {
        val rig = rig(reduceMotion = true, firstRowFocusable = false)
        val primary = rig.hero.findViewById<View>(R.id.hero_primary_action)
        assertTrue(primary.requestFocus())
        assertEquals(null, rig.page.findViewHolderForAdapterPosition(2))

        assertTrue(rig.coordinator.handleKeyEvent(down(), primary))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(HomeHeroPresentation.COLLAPSED, rig.coordinator.presentation)
        assertTrue(rig.firstCard.hasFocus())
        assertEquals(2, rig.page.findContainingViewHolder(rig.firstCard)?.bindingAdapterPosition)
        assertTrue(rig.coordinator.handleKeyEvent(up(), rig.firstCard))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(HomeHeroPresentation.EXPANDED, rig.coordinator.presentation)
        assertTrue(primary.hasFocus())
        rig.activity.finish()
    }

    @Test
    fun upFromFirstInteractiveRowRestoresHeroAfterHeaderWasVirtualized() {
        val rig = rig(reduceMotion = true, firstRowFocusable = false)
        val primary = rig.hero.findViewById<View>(R.id.hero_primary_action)
        assertTrue(primary.requestFocus())
        assertTrue(rig.coordinator.handleKeyEvent(down(), primary))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(rig.firstCard.hasFocus())
        rig.page.scrollToPosition(2)
        rig.page.measure(exact(1_920), exact(1_080))
        rig.page.layout(0, 0, 1_920, 1_080)
        assertEquals(null, rig.page.findViewHolderForAdapterPosition(0))

        assertTrue(rig.coordinator.handleKeyEvent(up(), rig.firstCard))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(HomeHeroPresentation.EXPANDED, rig.coordinator.presentation)
        assertTrue(primary.hasFocus())
        rig.activity.finish()
    }

    @Test
    fun noInteractiveRowLeavesHeroExpandedAndUpAvailableForNavigation() {
        val rig = rig(reduceMotion = true, firstRowFocusable = false, noInteractiveRow = true)
        val primary = rig.hero.findViewById<View>(R.id.hero_primary_action)
        assertTrue(primary.requestFocus())

        assertTrue(rig.coordinator.handleKeyEvent(down(), primary))
        assertEquals(HomeHeroPresentation.EXPANDED, rig.coordinator.presentation)
        assertTrue(primary.hasFocus())
        assertFalse(rig.coordinator.animationActive)
        assertFalse(rig.coordinator.handleKeyEvent(up(), primary))
        rig.activity.finish()
    }

    @Test
    fun pausingPendingRowFocusDoesNotStealDetailsFocus() {
        val rig = rig(
            reduceMotion = true,
            firstRowFocusable = false,
            noInteractiveRow = true,
            advertiseMissingRow = true
        )
        val primary = rig.hero.findViewById<View>(R.id.hero_primary_action)
        assertTrue(primary.requestFocus())
        rig.coordinator.handleKeyEvent(down(), primary)
        val detailsFocus = Button(rig.activity).apply { text = "Details control" }
        rig.activity.addContentView(detailsFocus, FrameLayout.LayoutParams(300, 100))
        assertTrue(detailsFocus.requestFocus())

        rig.coordinator.settleForPause()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(detailsFocus.hasFocus())
        assertEquals(HomeHeroPresentation.EXPANDED, rig.coordinator.presentation)
        rig.activity.finish()
    }

    @Test
    fun earlierShelfBecomingInteractiveKeepsUpWithinBrowsingRows() {
        val rig = rig(reduceMotion = true, firstRowFocusable = false)
        val primary = rig.hero.findViewById<View>(R.id.hero_primary_action)
        assertTrue(primary.requestFocus())
        rig.coordinator.handleKeyEvent(down(), primary)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(rig.firstCard.hasFocus())
        assertEquals(HomeHeroPresentation.COLLAPSED, rig.coordinator.presentation)

        // An earlier shelf finishes loading while the user is focused in the next shelf.
        rig.firstInteractivePosition.set(1)

        assertFalse(rig.coordinator.handleKeyEvent(up(), rig.firstCard))
        assertEquals(HomeHeroPresentation.COLLAPSED, rig.coordinator.presentation)
        rig.activity.finish()
    }

    private fun rig(
        reduceMotion: Boolean,
        firstRowFocusable: Boolean = true,
        noInteractiveRow: Boolean = false,
        advertiseMissingRow: Boolean = false
    ): Rig {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val page = RecyclerView(activity).apply {
            layoutManager = TvLinearLayoutManager(activity, RecyclerView.VERTICAL, false)
            itemAnimator = null
        }
        val hero = HeroRowView(
            context = activity,
            route = HeroRoute.HOME,
            session = NativeSession("token", "server", "user", "Codex", "https://example.test"),
            api = JellyfinNativeApi(activity),
            reduceMotion = { reduceMotion },
            onPrimary = {},
            onDetails = {},
            onControlsFocusChanged = {},
            onImageResult = { _, _, _ -> }
        )
        val featured = MediaItem(
            id = "featured", title = "Featured", type = "Movie", year = "2026", imageTag = null,
            seriesName = null, episodeLabel = null, playbackPositionTicks = 0,
            runtimeTicks = 100, overview = "Featured story"
        )
        val candidate = HeroCandidate(featured, HeroSource.RECOMMENDED)
        hero.render(HeroState(HeroRoute.HOME, listOf(candidate), candidate, 0))
        val controller = HeroController(HeroRoute.HOME, { reduceMotion }, {})
        val state = HomeHeroInteractionState(HeroFocusAction.PRIMARY)
        val firstInteractivePosition = AtomicReference<Int?>(
            when {
                advertiseMissingRow -> 1
                noInteractiveRow -> null
                firstRowFocusable -> 1
                else -> 2
            }
        )
        val coordinator = HomeHeroCoordinator(
            page,
            hero,
            controller,
            state,
            { reduceMotion },
            { firstInteractivePosition.get() }
        )
        val firstCard = Button(activity).apply { text = "First card" }
        page.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = if (firstRowFocusable || noInteractiveRow) 2 else 3
            override fun getItemViewType(position: Int) = position
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = when (viewType) {
                    0 -> FrameLayout(activity)
                    1 -> if (firstRowFocusable) firstCard else FrameLayout(activity).apply {
                        minimumHeight = 1_100
                        layoutParams = RecyclerView.LayoutParams(-1, 1_100)
                    }
                    else -> firstCard
                }
                return object : RecyclerView.ViewHolder(view) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                if (position == 0) coordinator.bindHeader(holder.itemView as FrameLayout)
            }
        }
        activity.setContentView(page)
        coordinator.attach()
        page.measure(exact(1_920), exact(1_080))
        page.layout(0, 0, 1_920, 1_080)
        return Rig(activity, page, hero, firstCard, coordinator, firstInteractivePosition)
    }

    private fun exact(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)
    private fun down() = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
    private fun up() = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)

    private data class Rig(
        val activity: Activity,
        val page: RecyclerView,
        val hero: HeroRowView,
        val firstCard: Button,
        val coordinator: HomeHeroCoordinator,
        val firstInteractivePosition: AtomicReference<Int?>
    )
}
