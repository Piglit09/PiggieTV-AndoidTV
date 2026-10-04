package com.piggie.tv.core

import android.app.Application
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import com.piggie.tv.auth.AuthSessionCoordinator
import com.piggie.tv.R
import com.piggie.tv.auth.MainActivity
import com.piggie.tv.data.api.SessionOrigin
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.EncryptedSessionToken
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.data.session.SessionTokenCipher
import com.piggie.tv.navigation.NativeRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class PtvHostActivityRouteTransactionTest {
    private lateinit var store: SecureSessionStore
    private var controller: ActivityController<PtvHostActivity>? = null

    @Before
    fun saveSession() {
        val application: Application = RuntimeEnvironment.getApplication()
        SecureSessionStore.installTokenCipherFactoryForTests { PassthroughTokenCipher }
        store = SecureSessionStore(application)
        val session = NativeSession(
            token = "test-token",
            serverId = "test-server",
            userId = "test-user",
            userName = "Test User",
            serverUrl = "https://example.invalid"
        )
        val attempt = AuthSessionCoordinator.beginAttempt()
        assertTrue(
            AuthSessionCoordinator.commitAuthenticated(attempt, session) {
                store.save(session, SessionOrigin.STORED)
            }
        )
    }

    private object PassthroughTokenCipher : SessionTokenCipher {
        override fun encrypt(
            token: ByteArray,
            additionalAuthenticatedData: ByteArray,
        ) = EncryptedSessionToken(token + ByteArray(16), ByteArray(12))

        override fun decrypt(
            encryptedToken: EncryptedSessionToken,
            additionalAuthenticatedData: ByteArray,
        ): ByteArray = encryptedToken.ciphertext.copyOfRange(
            0,
            encryptedToken.ciphertext.size - 16,
        )
    }

    @After
    fun tearDown() {
        controller?.let { activityController ->
            runCatching { activityController.pause().stop().destroy() }
        }
        controller = null
        store.clear()
        AuthSessionCoordinator.resetForTests()
        SecureSessionStore.installTokenCipherFactoryForTests(null)
    }

    @Test
    fun hostWithoutCurrentProcessValidationReturnsToLogin() {
        AuthSessionCoordinator.resetForTests()
        controller = Robolectric.buildActivity(PtvHostActivity::class.java).setup()
        val activity = requireNotNull(controller).get()

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun memoryTrimQueuesRemovalBehindPendingRouteLifecycleChange() {
        val activity = launchWithCommittedHome()
        val fragmentManager = activity.supportFragmentManager

        activity.showRoute(NativeRoute.MOVIES)
        activity.onTrimMemory(com.piggie.tv.memory.MemoryPressurePolicy.RUNNING_LOW)

        // Before the repair, onTrimMemory used commitNowAllowingStateLoss(): it removed Home
        // immediately, then this flush executed the older setMaxLifecycle(Home, STARTED) and threw.
        assertTrue(fragmentManager.executePendingTransactions())

        val movies = fragmentManager.findFragmentByTag("ptv-route-movies")
        assertTrue(movies?.isAdded == true)
        assertEquals(Lifecycle.State.RESUMED, movies?.lifecycle?.currentState)
        assertNull(fragmentManager.findFragmentByTag("ptv-route-home"))
    }

    @Test
    fun rapidReturnCreatesReplacementInsteadOfReusingFragmentQueuedForEviction() {
        val activity = launchWithCommittedHome()
        val fragmentManager = activity.supportFragmentManager
        val originalHome = fragmentManager.findFragmentByTag("ptv-route-home")

        activity.showRoute(NativeRoute.MOVIES)
        fragmentManager.executePendingTransactions()

        // Shows queues eviction of Home. Returning before that transaction executes used to find
        // the still-active Home by tag, queue show/setMaxLifecycle after remove, and crash on flush.
        activity.showRoute(NativeRoute.SHOWS)
        activity.showRoute(NativeRoute.HOME)
        assertTrue(fragmentManager.executePendingTransactions())

        val replacementHome = fragmentManager.findFragmentByTag("ptv-route-home")
        assertTrue(replacementHome?.isAdded == true)
        assertNotSame(originalHome, replacementHome)
        assertEquals(Lifecycle.State.RESUMED, replacementHome?.lifecycle?.currentState)
        assertNull(fragmentManager.findFragmentByTag("ptv-route-movies"))
    }

    @Test
    fun rapidRouteChangeHidesAndCapsPendingOutgoingRoute() {
        val activity = launchWithCommittedHome()
        val fragmentManager = activity.supportFragmentManager

        activity.showRoute(NativeRoute.MOVIES)
        activity.showRoute(NativeRoute.SHOWS)
        assertTrue(fragmentManager.executePendingTransactions())

        val movies = fragmentManager.findFragmentByTag("ptv-route-movies")
        val shows = fragmentManager.findFragmentByTag("ptv-route-shows")
        assertTrue(movies?.isAdded == true)
        assertTrue(movies?.isHidden == true)
        assertEquals(Lifecycle.State.STARTED, movies?.lifecycle?.currentState)
        assertTrue(shows?.isAdded == true)
        assertTrue(shows?.isHidden == false)
        assertEquals(Lifecycle.State.RESUMED, shows?.lifecycle?.currentState)
        assertEquals(shows, fragmentManager.primaryNavigationFragment)
    }

    @Test
    fun routeChangeRemovesDetailsWhoseAddIsStillPending() {
        val activity = launchWithCommittedHome()
        val fragmentManager = activity.supportFragmentManager
        val item = MediaItem(
            id = "pending-details",
            title = "Pending Details",
            type = "Movie",
            year = null,
            imageTag = null,
            seriesName = null,
            episodeLabel = null,
            playbackPositionTicks = 0,
            runtimeTicks = 0
        )

        activity.showDetails(item)
        activity.showRoute(NativeRoute.MOVIES)
        assertTrue(fragmentManager.executePendingTransactions())

        val movies = fragmentManager.findFragmentByTag("ptv-route-movies")
        assertNull(fragmentManager.findFragmentByTag("ptv-details"))
        assertTrue(movies?.isAdded == true)
        assertTrue(movies?.isHidden == false)
        assertEquals(Lifecycle.State.RESUMED, movies?.lifecycle?.currentState)
        assertEquals(movies, fragmentManager.primaryNavigationFragment)
    }

    @Test
    fun movieDetailsBackPreservesPremiumBrowseRailAndKeepsHostAlive() {
        val activity = launchWithCommittedHome()
        val fragmentManager = activity.supportFragmentManager
        activity.showRoute(NativeRoute.MOVIES)
        fragmentManager.executePendingTransactions()
        val rail = activity.findViewById<View>(R.id.ptv_nav_rail)
        assertNull((rail.background as GradientDrawable).colors)

        activity.showDetails(MediaItem(
            id = "back-movie",
            title = "Back Movie",
            type = "Movie",
            year = null,
            imageTag = null,
            seriesName = null,
            episodeLabel = null,
            playbackPositionTicks = 0,
            runtimeTicks = 0
        ))
        fragmentManager.executePendingTransactions()
        assertNull((rail.background as GradientDrawable).colors)

        @Suppress("DEPRECATION")
        activity.onBackPressed()
        fragmentManager.executePendingTransactions()
        assertFalse(activity.isFinishing)
        assertNull(fragmentManager.findFragmentByTag("ptv-details"))
        assertNull((rail.background as GradientDrawable).colors)
        assertEquals(
            fragmentManager.findFragmentByTag("ptv-route-movies"),
            fragmentManager.primaryNavigationFragment,
        )
    }

    @Test
    fun seriesDetailsBackRestoresShowsBrowseContextAndKeepsHostAlive() {
        val activity = launchWithCommittedHome()
        val fragmentManager = activity.supportFragmentManager
        activity.showRoute(NativeRoute.SHOWS)
        fragmentManager.executePendingTransactions()

        activity.showDetails(MediaItem(
            id = "back-series",
            title = "Back Series",
            type = "Series",
            year = null,
            imageTag = null,
            seriesName = null,
            episodeLabel = null,
            playbackPositionTicks = 0,
            runtimeTicks = 0
        ))
        fragmentManager.executePendingTransactions()
        assertTrue(fragmentManager.findFragmentByTag("ptv-details")?.isAdded == true)

        @Suppress("DEPRECATION")
        activity.onBackPressed()
        fragmentManager.executePendingTransactions()

        assertFalse(activity.isFinishing)
        assertNull(fragmentManager.findFragmentByTag("ptv-details"))
        assertEquals(
            fragmentManager.findFragmentByTag("ptv-route-shows"),
            fragmentManager.primaryNavigationFragment,
        )
        val rail = activity.findViewById<ViewGroup>(R.id.ptv_nav_rail)
        assertTrue(rail.getChildAt(2).isSelected)
    }

    @Test
    fun continueWatchingBackHandoffReusesHostAndSelectsHome() {
        val activity = launchWithCommittedHome()
        val fragmentManager = activity.supportFragmentManager
        activity.showRoute(NativeRoute.SHOWS)
        fragmentManager.executePendingTransactions()

        val handoff = PtvHostActivity.returnHomeIntent(activity)
        assertEquals(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            handoff.flags and
                (Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        assertEquals(NativeRoute.HOME, PtvHostActivity.requestedRoute(handoff))

        controller!!.newIntent(handoff)
        fragmentManager.executePendingTransactions()
        assertNull(PtvHostActivity.requestedRoute(activity.intent))

        val home = fragmentManager.findFragmentByTag("ptv-route-home")
        assertTrue(home?.isAdded == true)
        assertTrue(home?.isHidden == false)
        assertEquals(Lifecycle.State.RESUMED, home?.lifecycle?.currentState)
        assertEquals(home, fragmentManager.primaryNavigationFragment)
    }

    @Test
    fun ordinaryHostIntentDoesNotRequestAPlaybackReturnRoute() {
        assertNull(PtvHostActivity.requestedRoute(Intent()))
    }

    @Test
    fun settingsProfileEntryOpensProfileAndBackRestoresEntryAsNavigationTarget() {
        val activity = launchWithCommittedHome()
        val manager = activity.supportFragmentManager

        activity.showRoute(NativeRoute.SETTINGS)
        manager.executePendingTransactions()
        val entryId = activity.resources.getIdentifier("ptv_settings_profile_entry", "id", activity.packageName)
        val profileEntry = requireNotNull(activity.findViewById<View>(entryId))
        assertTrue(profileEntry.isFocusable)
        assertTrue(descendantText(profileEntry).contains("Test User"))
        val settingsNavigation = activity.findViewById<View>(R.id.ptv_nav_rail)
            .let { rail -> (rail as ViewGroup).getChildAt(5) }
        assertEquals(profileEntry.id, settingsNavigation.nextFocusDownId)
        assertEquals(settingsNavigation.id, profileEntry.nextFocusUpId)
        assertTrue(profileEntry.performClick())
        manager.executePendingTransactions()

        val profile = requireNotNull(manager.findFragmentByTag("ptv-route-profile"))
        assertTrue(profile.isAdded && !profile.isHidden)
        assertEquals(Lifecycle.State.RESUMED, profile.lifecycle.currentState)
        val profileSettingsId = activity.resources.getIdentifier("ptv_profile_settings_entry", "id", activity.packageName)
        val profileSettingsEntry = requireNotNull(activity.findViewById<View>(profileSettingsId))
        assertEquals(profileSettingsEntry.id, settingsNavigation.nextFocusDownId)
        assertEquals(settingsNavigation.id, profileSettingsEntry.nextFocusUpId)
        assertTrue(settingsNavigation.isSelected)

        @Suppress("DEPRECATION")
        activity.onBackPressed()
        manager.executePendingTransactions()

        val settings = requireNotNull(manager.findFragmentByTag("ptv-route-settings"))
        assertTrue(settings.isAdded && !settings.isHidden)
        assertEquals(Lifecycle.State.RESUMED, settings.lifecycle.currentState)
        // This host test deliberately leaves the Robolectric window detached. Its focus owner is
        // therefore null, so verify the restored D-pad path here and check actual focus on TV.
        assertEquals(profileEntry, settings.view?.findViewById<View>(profileEntry.id))
        assertEquals(profileEntry.id, settingsNavigation.nextFocusDownId)
        assertEquals(settingsNavigation.id, profileEntry.nextFocusUpId)
        assertTrue(settingsNavigation.isSelected)
    }

    @Test
    fun settingsRebuildKeepsProfileEntryLinkedToTheSettingsNavigation() {
        val activity = launchWithCommittedHome()
        activity.showRoute(NativeRoute.SETTINGS)
        activity.supportFragmentManager.executePendingTransactions()

        val settingsNavigation = (activity.findViewById<View>(R.id.ptv_nav_rail) as ViewGroup).getChildAt(5)
        val originalEntry = activity.findViewById<View>(R.id.ptv_settings_profile_entry)
        val autoplay = descendants(activity.window.decorView)
            .filterIsInstance<Button>()
            .first { it.text.toString().startsWith("Autoplay Next Episode:") }
        assertTrue(autoplay.performClick())

        val rebuiltEntry = activity.findViewById<View>(R.id.ptv_settings_profile_entry)
        assertNotSame(originalEntry, rebuiltEntry)
        assertEquals(rebuiltEntry.id, settingsNavigation.nextFocusDownId)
        assertEquals(settingsNavigation.id, rebuiltEntry.nextFocusUpId)
    }

    @Test
    fun settingsScrollablePagesClipControlsBelowTheFloatingNavigation() {
        val activity = launchWithCommittedHome()
        activity.showRoute(NativeRoute.SETTINGS)
        activity.supportFragmentManager.executePendingTransactions()
        val clearance = activity.resources.getDimensionPixelSize(R.dimen.tv_floating_nav_clearance)

        fun assertSafeViewport() {
            val settings = requireNotNull(activity.supportFragmentManager.findFragmentByTag("ptv-route-settings"))
            val scroll = requireNotNull((settings.view as FrameLayout).getChildAt(0) as? ScrollView)
            assertEquals(clearance, scroll.paddingTop)
            assertTrue(scroll.clipToPadding)
        }

        assertSafeViewport()
        settingsButton(activity, "Diagnostics & Beta Info").performClick()
        assertSafeViewport()
        settingsButton(activity, "Test Runner").performClick()
        assertSafeViewport()
    }

    @Test
    fun cachedDiagnosticsAndTestRunnerReconnectTheirFirstControlOnReturn() {
        val activity = launchWithCommittedHome()
        val manager = activity.supportFragmentManager
        val settingsNavigation = (activity.findViewById<View>(R.id.ptv_nav_rail) as ViewGroup).getChildAt(5)
        activity.showRoute(NativeRoute.SETTINGS)
        manager.executePendingTransactions()
        settingsButton(activity, "Diagnostics & Beta Info").performClick()

        fun returnToCachedSettings(firstControl: View) {
            activity.showRoute(NativeRoute.HOME)
            manager.executePendingTransactions()
            assertEquals(View.NO_ID, settingsNavigation.nextFocusDownId)
            activity.showRoute(NativeRoute.SETTINGS)
            manager.executePendingTransactions()
            assertEquals(firstControl.id, settingsNavigation.nextFocusDownId)
            assertEquals(settingsNavigation.id, firstControl.nextFocusUpId)
        }

        val diagnosticsFirst = settingsButton(activity, "Diagnostics collection:")
        returnToCachedSettings(diagnosticsFirst)

        settingsButton(activity, "Test Runner").performClick()
        val testRunnerFirst = settingsButton(activity, "Run Safe Tests")
        returnToCachedSettings(testRunnerFirst)

        settingsNavigation.nextFocusDownId = View.NO_ID
        activity.showRoute(NativeRoute.SETTINGS)
        assertEquals(testRunnerFirst.id, settingsNavigation.nextFocusDownId)
    }

    private fun settingsButton(activity: PtvHostActivity, prefix: String): Button =
        descendants(activity.window.decorView)
            .filterIsInstance<Button>()
            .first { it.text.toString().startsWith(prefix) }

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
        }
    }

    private fun descendantText(root: View): String = when (root) {
        is TextView -> root.text.toString()
        is ViewGroup -> (0 until root.childCount).joinToString(" ") { descendantText(root.getChildAt(it)) }
        else -> ""
    }

    private fun launchWithCommittedHome(): PtvHostActivity {
        // Avoid ActivityController.visible(): Robolectric's generic service shadow fabricates an
        // invalid Media3 callback while MusicPlaybackManager binds. Visibility is irrelevant to
        // exercising FragmentManager's queued lifecycle operations.
        val activityController = Robolectric.buildActivity(PtvHostActivity::class.java)
            .create()
            .start()
            .resume()
        controller = activityController
        return activityController.get().also { activity ->
            activity.supportFragmentManager.executePendingTransactions()
        }
    }
}
