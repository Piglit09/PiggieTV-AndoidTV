package com.piggie.tv.ui.hero

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.ui.home.HomeHeroPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-xhdpi")
class HomeHeroPresentationViewTest {
    @Test
    fun compactKeepsIdentityMetadataAndBothActionsWhileHidingDescription() {
        val activity = activity()
        val hero = hero(activity)
        hero.render(featured())

        hero.setHomePresentation(HomeHeroPresentation.COLLAPSED)

        assertEquals(View.VISIBLE, hero.findViewById<TextView>(R.id.hero_title).visibility)
        assertTrue(hero.findViewById<TextView>(R.id.hero_metadata).text.contains("2026"))
        assertEquals(View.GONE, hero.findViewById<View>(R.id.hero_overview).visibility)
        assertTrue(hero.homeActionsAvailable())
        assertEquals(View.VISIBLE, hero.findViewById<View>(R.id.hero_primary_action).visibility)
        assertEquals(View.VISIBLE, hero.findViewById<View>(R.id.hero_details_action).visibility)
        activity.finish()
    }

    @Test
    fun compactArrangesActionsToRightOfIdentityAndExpandedRestoresDescription() {
        val activity = activity()
        val hero = hero(activity)
        hero.render(featured())
        hero.setHomePresentation(HomeHeroPresentation.COLLAPSED)
        layout(hero, height = 344)

        val title = hero.findViewById<View>(R.id.hero_title)
        val primary = hero.findViewById<View>(R.id.hero_primary_action)
        val actionContainer = primary.parent as View
        val titleBounds = Rect().also(title::getHitRect)
        val actionBounds = Rect().also(actionContainer::getHitRect)
        assertTrue(
            "compact action focus bounds should sit beside identity: actions=$actionBounds title=$titleBounds",
            actionBounds.left > titleBounds.right
        )

        hero.setHomePresentation(HomeHeroPresentation.EXPANDED)
        layout(hero, height = 900)
        assertEquals(View.VISIBLE, hero.findViewById<View>(R.id.hero_overview).visibility)
        assertTrue(
            "expanded actions should follow the description",
            actionContainer.top + primary.top > title.top
        )
        activity.finish()
    }

    @Test
    fun transitionsReuseArtworkAndActionViewsWithoutStartingArtworkLoads() {
        val activity = activity()
        val hero = hero(activity)
        activity.setContentView(hero)
        hero.render(featured())
        val artwork = hero.findViewById<ImageView>(R.id.hero_backdrop)
        val primary = hero.findViewById<View>(R.id.hero_primary_action)
        val details = hero.findViewById<View>(R.id.hero_details_action)
        val requestGeneration = backdropRequestGeneration(hero)

        hero.setHomePresentation(HomeHeroPresentation.COLLAPSED)
        hero.setHomePresentation(HomeHeroPresentation.EXPANDED)
        hero.setHomePresentation(HomeHeroPresentation.COLLAPSED)

        assertSame(artwork, hero.findViewById<View>(R.id.hero_backdrop))
        assertSame(primary, hero.findViewById<View>(R.id.hero_primary_action))
        assertSame(details, hero.findViewById<View>(R.id.hero_details_action))
        assertEquals("presentation must not request the same backdrop again", requestGeneration, backdropRequestGeneration(hero))
        activity.finish()
    }

    @Test
    fun actionLookupAndFocusRemainAvailableInBothPresentations() {
        val activity = activity()
        val hero = hero(activity)
        activity.setContentView(hero)
        hero.render(featured())
        val primary = hero.findViewById<View>(R.id.hero_primary_action)
        val details = hero.findViewById<View>(R.id.hero_details_action)

        assertEquals(HeroFocusAction.PRIMARY, hero.homeActionFor(primary))
        assertEquals(HeroFocusAction.DETAILS, hero.homeActionFor(details))
        assertEquals(null, hero.homeActionFor(hero))
        assertTrue(hero.requestHomeActionFocus(HeroFocusAction.DETAILS))
        assertTrue(details.hasFocus())

        hero.setHomePresentation(HomeHeroPresentation.COLLAPSED)
        assertTrue(hero.requestHomeActionFocus(HeroFocusAction.PRIMARY))
        assertTrue(primary.hasFocus())
        assertEquals(HeroFocusAction.PRIMARY, hero.homeActionFor(hero.findFocus()))
        activity.finish()
    }

    @Test
    fun emptyHeroHasNoActionFocusTarget() {
        val activity = activity()
        val hero = hero(activity)
        activity.setContentView(hero)
        hero.setHomePresentation(HomeHeroPresentation.COLLAPSED)

        assertFalse(hero.homeActionsAvailable())
        assertFalse(hero.requestHomeActionFocus(HeroFocusAction.PRIMARY))
        activity.finish()
    }

    @Test
    fun expandedDetailsFocusCanDrawBeyondActionContainer() {
        assertDetailsFocusOverflowAllowed(HomeHeroPresentation.EXPANDED, 900)
    }

    @Test
    fun compactDetailsFocusCanDrawBeyondActionContainer() {
        assertDetailsFocusOverflowAllowed(HomeHeroPresentation.COLLAPSED, 344)
    }

    private fun assertDetailsFocusOverflowAllowed(presentation: HomeHeroPresentation, height: Int) {
        val activity = activity()
        val hero = hero(activity)
        activity.setContentView(hero)
        hero.render(featured())
        hero.setHomePresentation(presentation)
        layout(hero, height)
        val details = hero.findViewById<View>(R.id.hero_details_action)
        val actions = details.parent as ViewGroup
        val content = actions.parent as ViewGroup
        assertTrue(details.requestFocus())
        assertTrue(details.scaleX > 1f)
        assertTrue(
            "focused Details scale must cross action row's right bound",
            details.right + details.width * (details.scaleX - 1f) / 2f > actions.width + 2f
        )
        assertFalse("action row must not cut the rounded focus edge", actions.clipChildren)
        assertFalse("action row padding must not cut the focus edge", actions.clipToPadding)
        assertFalse("content must not clip the action row to its bounds", content.clipChildren)
        assertFalse("content padding must not cut edge actions", content.clipToPadding)
        activity.finish()
    }

    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun hero(activity: Activity) = HeroRowView(
        context = activity,
        route = HeroRoute.HOME,
        session = NativeSession("token", "server", "user", "Codex", "https://example.test"),
        api = JellyfinNativeApi(activity),
        reduceMotion = { true },
        onPrimary = {},
        onDetails = {},
        onControlsFocusChanged = {},
        onImageResult = { _, _, _ -> }
    )

    private fun featured(): HeroState {
        val item = MediaItem(
            id = "featured", title = "Featured Film", type = "Movie", year = "2026",
            imageTag = null, seriesName = null, episodeLabel = null,
            playbackPositionTicks = 0L, runtimeTicks = 100L,
            overview = "A short description of the featured film."
        )
        val candidate = HeroCandidate(item, HeroSource.RECOMMENDED)
        return HeroState(HeroRoute.HOME, pool = listOf(candidate), current = candidate, selectedIndex = 0)
    }

    private fun layout(hero: HeroRowView, height: Int) {
        hero.measure(
            View.MeasureSpec.makeMeasureSpec(1880, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        hero.layout(0, 0, 1880, height)
    }

    private fun backdropRequestGeneration(hero: HeroRowView): Long =
        HeroRowView::class.java.getDeclaredField("backdropRequestGeneration").apply {
            isAccessible = true
        }.getLong(hero)
}
