package com.piggie.tv.ui.layout

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.RecyclerView
import com.piggie.tv.auth.AuthSessionCoordinator
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.api.SessionOrigin
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.EncryptedSessionToken
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.data.session.SessionTokenCipher
import com.piggie.tv.navigation.NativeRoute
import com.piggie.tv.ui.hero.HeroCandidate
import com.piggie.tv.ui.hero.HeroRoute
import com.piggie.tv.ui.hero.HeroRowView
import com.piggie.tv.ui.hero.HeroSource
import com.piggie.tv.ui.hero.HeroState
import com.piggie.tv.ui.music.MusicFragment
import com.piggie.tv.ui.player.MediaDetailsFragment
import com.piggie.tv.ui.player.MediaDetailsSeedStore
import com.piggie.tv.ui.search.SearchFragment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-xhdpi")
class FloatingNavigationContentLayoutTest {
    private lateinit var store: SecureSessionStore
    private var controller: ActivityController<PtvHostActivity>? = null

    @Before
    fun saveSession() {
        val application: Application = RuntimeEnvironment.getApplication()
        SecureSessionStore.installTokenCipherFactoryForTests { PassthroughTokenCipher }
        store = SecureSessionStore(application)
        val session = NativeSession(
            token = "floating-nav-test-token",
            serverId = "floating-nav-test-server",
            userId = "floating-nav-test-user",
            userName = "Navigation Test",
            serverUrl = "https://example.invalid"
        )
        val attempt = AuthSessionCoordinator.beginAttempt()
        assertTrue(AuthSessionCoordinator.commitAuthenticated(attempt, session) {
            store.save(session, SessionOrigin.STORED)
        })
    }

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        MediaDetailsSeedStore.clear()
        store.clear()
        AuthSessionCoordinator.resetForTests()
        SecureSessionStore.installTokenCipherFactoryForTests(null)
    }

    @Test
    fun hostedDetailsRetainsItsFormerCopyPositionWhileBackdropBeginsAtScreenTop() {
        val activity = launchHost()
        activity.showDetails(MediaItem(
            id = "floating-details",
            title = "Floating Details",
            type = "Movie",
            year = null,
            imageTag = null,
            seriesName = null,
            episodeLabel = null,
            playbackPositionTicks = 0L,
            runtimeTicks = 0L
        ))
        activity.supportFragmentManager.executePendingTransactions()
        val root = activity.supportFragmentManager.fragments
            .filterIsInstance<MediaDetailsFragment>().single().requireView() as FrameLayout
        val scroll = (0 until root.childCount).map(root::getChildAt)
            .filterIsInstance<ScrollView>().single()
        val copy = scroll.getChildAt(0) as LinearLayout

        // The old hosted content began 60dp below the rail and had another 10dp top inset.
        // Reserve the rail on the scrolling viewport, so scrolled copy cannot paint behind it.
        assertEquals(120, scroll.paddingTop)
        assertTrue(scroll.clipToPadding)
        assertEquals(20, copy.paddingTop)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, root.getChildAt(0).layoutParams.height)
    }

    @Test
    fun searchControlsStartBelowTheFloatingNavigation() {
        val activity = launchHost()
        activity.showRoute(NativeRoute.SEARCH)
        activity.supportFragmentManager.executePendingTransactions()
        val search = activity.supportFragmentManager.fragments
            .filterIsInstance<SearchFragment>().single().requireView() as RecyclerView

        assertEquals(120, search.paddingTop)
        assertTrue(search.clipToPadding)
    }

    @Test
    fun searchHeaderUpTargetsSelectedSearchNavigationWithoutChangingRoute() {
        val activity = launchHost()
        activity.showRoute(NativeRoute.SEARCH)
        activity.supportFragmentManager.executePendingTransactions()
        val searchPage = activity.supportFragmentManager.fragments
            .filterIsInstance<SearchFragment>().single().requireView() as RecyclerView
        val decor = activity.window.decorView
        val width = View.MeasureSpec.makeMeasureSpec(1_920, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(1_080, View.MeasureSpec.EXACTLY)
        decor.measure(width, height)
        decor.layout(0, 0, 1_920, 1_080)

        val header = requireNotNull(searchPage.findViewHolderForAdapterPosition(0)).itemView
        val input = descendants(header).filterIsInstance<EditText>().single()
        val submitButton = descendants(header).filterIsInstance<Button>()
            .single { it.text.toString() == "Search" }
        val navigation = activity.findViewById<ViewGroup>(com.piggie.tv.R.id.ptv_nav_rail)
        val searchButton = (0 until navigation.childCount)
            .map(navigation::getChildAt).filterIsInstance<Button>()
            .single { it.text.toString() == "Search" }
        val homeButton = (0 until navigation.childCount)
            .map(navigation::getChildAt).filterIsInstance<Button>()
            .single { it.text.toString() == "Home" }

        assertTrue(searchButton.isSelected)
        assertFalse(homeButton.isSelected)
        assertEquals(searchButton, input.focusSearch(View.FOCUS_UP))
        assertEquals(searchButton, submitButton.focusSearch(View.FOCUS_UP))
        assertTrue(searchButton.isSelected)
        assertFalse(homeButton.isSelected)
    }

    @Test
    fun homeContentUpEntersHomeNavigation() =
        assertDiscoveryContentUpTarget(NativeRoute.HOME, HeroRoute.HOME, "Movie")

    @Test
    fun moviesContentUpEntersMoviesNavigation() {
        val activity = launchHost()
        val browse = renderMoviesBrowseAction(activity)
        val navigation = activity.findViewById<ViewGroup>(com.piggie.tv.R.id.ptv_nav_rail)
        val movies = (0 until navigation.childCount).map(navigation::getChildAt)
            .filterIsInstance<Button>().single { it.text.toString() == "Movies" }

        assertTrue(movies.isSelected)
        assertEquals(movies, browse.focusSearch(View.FOCUS_UP))
    }

    @Test
    fun showsSecondaryTabsUpEnterTheFrozenShowsNavigation() {
        val activity = launchHost()
        activity.showRoute(NativeRoute.SHOWS)
        activity.supportFragmentManager.executePendingTransactions()
        val showsNavigation = activity.findViewById<ViewGroup>(com.piggie.tv.R.id.ptv_nav_rail)
            .let { rail -> (0 until rail.childCount).map(rail::getChildAt)
                .filterIsInstance<Button>().single { it.text.toString() == "Shows" } }
        val tabs = listOf(
            com.piggie.tv.R.id.ptv_shows_tab_shows,
            com.piggie.tv.R.id.ptv_shows_tab_anime,
            com.piggie.tv.R.id.ptv_shows_tab_cartoons
        ).map { requireNotNull(activity.findViewById<Button>(it)) }

        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(1_920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1_080, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1_920, 1_080)

        assertEquals(listOf("Shows", "Anime", "Cartoons"), tabs.map { it.text.toString() })
        assertTrue(showsNavigation.isSelected)
        tabs.forEach { assertEquals(showsNavigation, it.focusSearch(View.FOCUS_UP)) }
        assertEquals(tabs.first(), showsNavigation.focusSearch(View.FOCUS_DOWN))
    }

    @Test
    fun switchingShowsFamilyTabsChangesDiscoveryIdentityWithoutAddingMainNavigationItems() {
        val activity = launchHost()
        activity.showRoute(NativeRoute.SHOWS)
        activity.supportFragmentManager.executePendingTransactions()
        val navigation = activity.findViewById<ViewGroup>(com.piggie.tv.R.id.ptv_nav_rail)
        val navButtons = (0 until navigation.childCount)
            .map(navigation::getChildAt).filterIsInstance<Button>()
        val showsNavigation = navButtons.single { it.text.toString() == "Shows" }
        val anime = requireNotNull(activity.findViewById<Button>(com.piggie.tv.R.id.ptv_shows_tab_anime))
        val cartoons = requireNotNull(activity.findViewById<Button>(com.piggie.tv.R.id.ptv_shows_tab_cartoons))
        val shows = requireNotNull(activity.findViewById<Button>(com.piggie.tv.R.id.ptv_shows_tab_shows))

        assertEquals(6, navButtons.size)
        anime.performClick()
        assertTrue(anime.isSelected)
        assertTrue(showsNavigation.isSelected)
        assertTrue(com.piggie.tv.data.discovery.DiscoveryManager.currentGenerationId(
            com.piggie.tv.data.discovery.DiscoveryPage.ANIME) > 0L)
        cartoons.performClick()
        assertTrue(cartoons.isSelected)
        assertTrue(com.piggie.tv.data.discovery.DiscoveryManager.currentGenerationId(
            com.piggie.tv.data.discovery.DiscoveryPage.CARTOONS) > 0L)
        shows.performClick()
        assertTrue(shows.isSelected)
        assertTrue(showsNavigation.isSelected)
    }

    @Test
    fun musicContentUpEntersMusicNavigation() =
        assertDiscoveryContentUpTarget(NativeRoute.MUSIC, HeroRoute.MUSIC, "Audio")

    @Test
    fun settingsContentUpEntersSettingsNavigation() {
        val activity = launchHost()
        activity.showRoute(NativeRoute.SETTINGS)
        activity.supportFragmentManager.executePendingTransactions()
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1_920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1_080, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, 1_920, 1_080)

        val entry = activity.findViewById<View>(com.piggie.tv.R.id.ptv_settings_profile_entry)
        val navigation = activity.findViewById<ViewGroup>(com.piggie.tv.R.id.ptv_nav_rail)
        val settings = (0 until navigation.childCount)
            .map(navigation::getChildAt).filterIsInstance<Button>()
            .single { it.text.toString() == "Settings" }
        assertTrue(settings.isSelected)
        assertEquals(settings, entry.focusSearch(View.FOCUS_UP))
    }

    @Test
    fun contentUpDuringAnUncommittedRouteChangeKeepsFocusInOldContent() {
        val activity = launchHost()
        val oldContent = renderMoviesBrowseAction(activity)
        activity.showRoute(NativeRoute.SHOWS)
        val navigation = activity.findViewById<ViewGroup>(com.piggie.tv.R.id.ptv_nav_rail)
        val showsButton = (0 until navigation.childCount)
            .map(navigation::getChildAt).filterIsInstance<Button>()
            .single { it.text.toString() == "Shows" }

        assertTrue(showsButton.isSelected)
        assertEquals(oldContent, oldContent.focusSearch(View.FOCUS_UP))
    }

    @Test
    fun contentUpStaysInContentWhenItsRouteNavigationTargetIsUnavailable() {
        val activity = launchHost()
        val details = renderMoviesBrowseAction(activity)
        val navigation = activity.findViewById<ViewGroup>(com.piggie.tv.R.id.ptv_nav_rail)
        val moviesButton = (0 until navigation.childCount)
            .map(navigation::getChildAt).filterIsInstance<Button>()
            .single { it.text.toString() == "Movies" }
        navigation.removeView(moviesButton)

        assertEquals(details, details.focusSearch(View.FOCUS_UP))
    }

    @Test
    fun movingFocusWithinNavigationDoesNotSelectAnotherRouteUntilActivation() {
        val activity = launchHost()
        activity.showRoute(NativeRoute.SEARCH)
        activity.supportFragmentManager.executePendingTransactions()
        val navigation = activity.findViewById<ViewGroup>(com.piggie.tv.R.id.ptv_nav_rail)
        val buttons = (0 until navigation.childCount)
            .map(navigation::getChildAt).filterIsInstance<Button>()
            .associateBy { it.text.toString() }
        val search = requireNotNull(buttons["Search"])
        val music = requireNotNull(buttons["Music"])
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1_920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1_080, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, 1_920, 1_080)

        assertEquals(music, search.focusSearch(View.FOCUS_LEFT))
        assertTrue(search.isSelected)
        assertFalse(music.isSelected)
        assertTrue(music.performClick())
        assertTrue(music.isSelected)
        assertFalse(search.isSelected)
    }

    private fun assertDiscoveryContentUpTarget(
        route: NativeRoute,
        heroRoute: HeroRoute,
        itemType: String
    ) {
        val activity = launchHost()
        val navigation = activity.findViewById<ViewGroup>(com.piggie.tv.R.id.ptv_nav_rail)
        val navButtons = (0 until navigation.childCount)
            .map(navigation::getChildAt).filterIsInstance<Button>()
            .associateBy { it.text.toString() }

        val details = renderDiscoveryDetails(activity, route, heroRoute, itemType)
        val expected = requireNotNull(navButtons[route.label])
        assertTrue("${route.label} must stay selected", expected.isSelected)
        assertEquals("${route.label} content Up target", expected, details.focusSearch(View.FOCUS_UP))
    }

    private fun renderDiscoveryDetails(
        activity: PtvHostActivity,
        route: NativeRoute,
        heroRoute: HeroRoute,
        itemType: String
    ): Button {
        activity.showRoute(route)
        activity.supportFragmentManager.executePendingTransactions()
        val decor = activity.window.decorView
        val width = View.MeasureSpec.makeMeasureSpec(1_920, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(1_080, View.MeasureSpec.EXACTLY)
        decor.measure(width, height)
        decor.layout(0, 0, 1_920, 1_080)

        val fragment = requireNotNull(activity.supportFragmentManager
            .findFragmentByTag("ptv-route-${route.name.lowercase()}"))
        val page = (fragment.requireView() as FrameLayout).getChildAt(0) as RecyclerView
        val header = requireNotNull(page.findViewHolderForAdapterPosition(0)).itemView
        val hero = descendants(header).filterIsInstance<HeroRowView>().single()
        val item = MediaItem(
            id = "focus-${route.name}", title = "Focus Test", type = itemType,
            year = null, imageTag = null, seriesName = null, episodeLabel = null,
            playbackPositionTicks = 0L, runtimeTicks = 0L
        )
        val candidate = HeroCandidate(item, HeroSource.RECENT)
        hero.render(HeroState(
            route = heroRoute, pool = listOf(candidate), current = candidate, selectedIndex = 0
        ))
        decor.measure(width, height)
        decor.layout(0, 0, 1_920, 1_080)

        val details = descendants(hero).filterIsInstance<Button>()
            .single { it.text.toString() == "Details" }
        return details
    }

    private fun renderMoviesBrowseAction(activity: PtvHostActivity): Button {
        activity.showRoute(NativeRoute.MOVIES)
        activity.supportFragmentManager.executePendingTransactions()
        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(1_920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1_080, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1_920, 1_080)
        val fragment = requireNotNull(activity.supportFragmentManager
            .findFragmentByTag("ptv-route-movies"))
        val page = (fragment.requireView() as FrameLayout).getChildAt(0) as RecyclerView
        val header = requireNotNull(page.findViewHolderForAdapterPosition(0)).itemView
        return requireNotNull(header.findViewById(com.piggie.tv.R.id.ptv_library_browse_all))
    }

    @Test
    fun musicHeroRowReservesNavigationHeightSoTheFirstShelfStaysAtItsFormerScreenPosition() {
        val activity = launchHost()
        activity.showRoute(NativeRoute.MUSIC)
        activity.supportFragmentManager.executePendingTransactions()
        val music = activity.supportFragmentManager.fragments
            .filterIsInstance<MusicFragment>().single().requireView() as FrameLayout
        val page = music.getChildAt(0) as RecyclerView
        page.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1_920, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1_080, android.view.View.MeasureSpec.EXACTLY)
        )
        page.layout(0, 0, 1_920, 1_080)
        val wrapper = requireNotNull(page.findViewHolderForAdapterPosition(0)).itemView as FrameLayout

        assertEquals(550, wrapper.minimumHeight)
        assertEquals(550, wrapper.getChildAt(0).layoutParams.height)
    }

    private fun launchHost(): PtvHostActivity {
        controller = Robolectric.buildActivity(PtvHostActivity::class.java).create().start().resume()
        return requireNotNull(controller).get().also {
            it.supportFragmentManager.executePendingTransactions()
        }
    }

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
        }
    }

    private object PassthroughTokenCipher : SessionTokenCipher {
        override fun encrypt(token: ByteArray, additionalAuthenticatedData: ByteArray) =
            EncryptedSessionToken(token + ByteArray(16), ByteArray(12))

        override fun decrypt(encryptedToken: EncryptedSessionToken, additionalAuthenticatedData: ByteArray): ByteArray =
            encryptedToken.ciphertext.copyOfRange(0, encryptedToken.ciphertext.size - 16)
    }
}
