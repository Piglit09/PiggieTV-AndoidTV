package com.piggie.tv.core

import android.app.Application
import android.content.Intent
import androidx.lifecycle.Lifecycle
import com.piggie.tv.data.api.SessionOrigin
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.navigation.NativeRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class PtvHostActivityRouteTransactionTest {
    private lateinit var store: SecureSessionStore
    private var controller: ActivityController<PtvHostActivity>? = null

    @Before
    fun saveSession() {
        val application: Application = RuntimeEnvironment.getApplication()
        store = SecureSessionStore(application)
        store.save(
            NativeSession(
                token = "test-token",
                serverId = "test-server",
                userId = "test-user",
                userName = "Test User",
                serverUrl = "https://example.invalid"
            ),
            SessionOrigin.STORED
        )
    }

    @After
    fun tearDown() {
        controller?.let { activityController ->
            runCatching { activityController.pause().stop().destroy() }
        }
        controller = null
        store.clear()
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
