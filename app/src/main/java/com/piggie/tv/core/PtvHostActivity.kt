package com.piggie.tv.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Trace
import android.util.Log
import android.view.KeyEvent
import android.graphics.Rect
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import com.piggie.tv.R
import com.piggie.tv.auth.MainActivity
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.playback.MusicPlaybackManager
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.navigation.NativePtvShell
import com.piggie.tv.navigation.NativeRoute
import com.piggie.tv.navigation.NativeRouteNavigator
import com.piggie.tv.ui.hero.HeroRefreshableRoute
import com.piggie.tv.ui.home.HomeFragment
import com.piggie.tv.ui.movies.MoviesFragment
import com.piggie.tv.ui.music.MusicFragment
import com.piggie.tv.ui.profile.ProfileFragment
import com.piggie.tv.ui.search.SearchFragment
import com.piggie.tv.ui.settings.SettingsFragment
import com.piggie.tv.ui.shows.ShowsFragment
import com.piggie.tv.ui.player.MediaDetailsFragment
import com.piggie.tv.ui.player.MediaDetailsSeedStore
import com.piggie.tv.data.session.NativeSettings
import com.piggie.tv.diagnostics.PerformanceMonitor
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import com.piggie.tv.diagnostics.PtvFocusTrace
import com.piggie.tv.memory.MemoryPressurePolicy
import com.piggie.tv.memory.MemoryPressureParticipant
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.lang.ref.WeakReference

class PtvHostActivity : AppCompatActivity() {
    private val store by lazy { SecureSessionStore(this) }
    lateinit var session: NativeSession
    private lateinit var contentFrame: FrameLayout
    private var navigation = emptyMap<NativeRoute, Button>()
    private var currentRoute = NativeRoute.HOME
    private val routeFragments = LinkedHashMap<NativeRoute, Fragment>(ROUTE_CACHE_SIZE, 0.75f, true)
    private var visibleFragment: Fragment? = null
    private var detailsFragment: Fragment? = null
    private var focusBeforeDetails: WeakReference<View>? = null
    private var detailsTraceCookie = NO_TRACE
    private var detailsRequestedAtMs = 0L
    private val traceSequence = AtomicInteger()

    override fun onCreate(savedInstanceState: Bundle?) {
        session = store.read() ?: run {
            super.onCreate(savedInstanceState)
            returnToLogin()
            return
        }
        // FragmentActivity restores retained fragments from super.onCreate(). They can create
        // their views synchronously and read the host session, so the session must exist first.
        super.onCreate(savedInstanceState)

        val requestedLaunchRoute = consumeRequestedRoute(intent)
        if (savedInstanceState != null) {
            val restoredRoute = savedInstanceState.getString("current_route", NativeRoute.HOME.name)
            currentRoute = NativeRouteNavigator.restoreTarget(restoredRoute)
        } else if (requestedLaunchRoute != null) {
            currentRoute = requestedLaunchRoute
        }
        
        MusicPlaybackManager.init(this)
        
        val shell = NativePtvShell.create(this, currentRoute, ::showRoute)
        contentFrame = shell.content
        navigation = shell.navigation
        restoreCachedFragments()
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager,
                    fragment: Fragment,
                    view: View,
                    savedInstanceState: Bundle?
                ) {
                    if (!PtvDiagnosticsManager.isEnabled()) return
                    val route = currentRoute.name.lowercase()
                    PtvDiagnosticsManager.routeFirstContent(route)
                    view.post { PtvDiagnosticsManager.routeInteractive(route, describeFocus(currentFocus)) }
                }
            },
            false
        )
        
        if (
            requestedLaunchRoute != null ||
            savedInstanceState == null ||
            visibleFragment?.tag != routeTag(currentRoute)
        ) {
            showRoute(requestedLaunchRoute ?: currentRoute)
        } else {
            // Restore selection state for navigation buttons
            navigation.forEach { (route, button) -> button.isSelected = route == currentRoute }
        }

        PerformanceMonitor.setVisible(this, NativeSettings(this).diagnosticsOverlayEnabled)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val requestedRoute = consumeRequestedRoute(intent)
        setIntent(intent)
        requestedRoute?.let(::showRoute)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("current_route", currentRoute.name)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val memoryTarget = detailsFragment?.takeIf { it.view != null }
            ?: visibleFragment?.takeIf { it.view != null }
        (memoryTarget as? MemoryPressureParticipant)
            ?.onMemoryPressure(level)
        if (MemoryPressurePolicy.actions(level).releaseInactiveRoutes) {
            releaseInactiveRouteFragments()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        releaseInactiveRouteFragments()
    }

    private fun releaseInactiveRouteFragments() {
        val inactive = routeFragments.entries
            .filter { (route, fragment) -> route != currentRoute && fragment !== visibleFragment }
        if (inactive.isEmpty()) return

        inactive.forEach { (route, _) -> routeFragments.remove(route) }
        if (isFinishing || isDestroyed) return
        // Keep removals in the FragmentManager queue. A synchronous commit here can leapfrog an
        // already queued route transaction which still caps the outgoing Fragment's lifecycle,
        // leaving that transaction to call setMaxLifecycle() on a Fragment we just made inactive.
        // Removing pending additions as well as isAdded Fragments also prevents an orphaned route
        // when a trim arrives between commit() and execution of the cold-start transaction.
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .apply { inactive.forEach { (_, fragment) -> remove(fragment) } }
            .commitAllowingStateLoss()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_2,
            KeyEvent.KEYCODE_BUTTON_C,
            KeyEvent.KEYCODE_MEDIA_CLOSE -> {
                handleBack()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
    }

    private fun handleBack(): Boolean {
        if (closeDetails()) return true
        val target = NativeRouteNavigator.backTarget(currentRoute) ?: return false
        showRoute(target)
        navigation[target]?.requestFocus()
        return true
    }

    fun showRoute(target: NativeRoute) {
        val visible = visibleFragment
        if (NativeRouteNavigator.canRefreshVisibleRoute(
                current = currentRoute,
                target = target,
                detailsOpen = detailsFragment != null,
                visibleFragmentPresent = visible != null,
                visibleTagMatches = visible?.tag == routeTag(target),
                cachedFragmentMatchesVisible =
                    visible != null && routeFragments[target] === visible
            )
        ) {
            (visibleFragment as? HeroRefreshableRoute)?.refreshHero()
            navigation[target]?.requestFocus()
            return
        }

        Trace.beginSection("PtvHostActivity#showRoute")
        try {
            showRouteInternal(target)
        } finally {
            Trace.endSection()
        }
    }

    private fun showRouteInternal(target: NativeRoute) {
        val routeRequestedAtMs = android.os.SystemClock.elapsedRealtime()
        val route = target.name.lowercase()
        val routeTrace = beginAsyncTrace("PiggieTV#route:$route")
        currentRoute = target
        PtvDiagnosticsManager.routeRequested(route)
        navigation.forEach { (navRoute, button) -> button.isSelected = navRoute == target }

        // restoreCachedFragments() makes the route cache authoritative after state restoration.
        // A tag lookup here can rediscover an active Fragment whose removal is already queued by
        // cache eviction or memory pressure. Reusing it would enqueue show/setMaxLifecycle after
        // remove and crash when the batched transactions execute.
        val fragment = routeFragments[target] ?: createRouteFragment(target)
        routeFragments[target] = fragment

        val transaction = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
        // A Details add may still be queued when a route is selected. Queue its removal behind
        // that add so the pending Fragment cannot become an orphaned resumed destination.
        detailsFragment?.let(transaction::remove)
        detailsFragment = null
        endDetailsTrace()
        val outgoingFragment = visibleFragment
        val outgoingIsRegistered = routeFragments.values.any { it === outgoingFragment }
        outgoingFragment
            ?.takeIf { it !== fragment && (it.isAdded || outgoingIsRegistered) }
            ?.let {
            if (it.tag?.startsWith(ROUTE_TAG_PREFIX) == true && !isRegisteredRouteFragment(it)) {
                transaction.remove(it)
            } else {
                // A rapid route change can arrive before the previous add executes. Both
                // transactions use this FragmentManager, so FIFO execution makes this hide and
                // lifecycle cap valid for that registered pending route as well.
                transaction.hide(it).setMaxLifecycle(it, Lifecycle.State.STARTED)
            }
        }
        if (fragment.isAdded) {
            transaction.show(fragment).setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
        } else {
            transaction.add(contentFrame.id, fragment, routeTag(target))
            transaction.setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
        }
        transaction.setPrimaryNavigationFragment(fragment)

        while (routeFragments.size > ROUTE_CACHE_SIZE) {
            val outgoingRoute = routeFragments.entries
                .firstOrNull { (_, cachedFragment) -> cachedFragment === outgoingFragment }
                ?.key
            val routeToEvict = NativeRouteNavigator.evictionCandidate(
                lruRoutes = routeFragments.keys,
                target = target,
                outgoing = outgoingRoute
            ) ?: break
            val fragmentToEvict = routeFragments.remove(routeToEvict)
            if (fragmentToEvict != null && fragmentToEvict !== fragment) {
                // Queue removal even if add() has not executed yet; both operations belong to this
                // FragmentManager and FIFO execution then prevents an untracked active Fragment.
                transaction.remove(fragmentToEvict)
            }
        }

        visibleFragment = fragment
        transaction.runOnCommit {
            val diagnostics = PtvDiagnosticsManager.isEnabled()
            if (diagnostics) PtvDiagnosticsManager.routeVisible(route, describeFocus(currentFocus))
            contentFrame.postOnAnimation {
                if (diagnostics) PtvDiagnosticsManager.routeInteractive(route, describeFocus(currentFocus))
                Log.i(
                    PERFORMANCE_TAG,
                    "route=$route interactiveMs=" +
                        (android.os.SystemClock.elapsedRealtime() - routeRequestedAtMs)
                )
                endAsyncTrace("PiggieTV#route:$route", routeTrace)
            }
        }.commit()

        if (currentFocus == null) navigation[target]?.requestFocus()
    }

    private fun createRouteFragment(target: NativeRoute): Fragment = when (target) {
        NativeRoute.HOME -> HomeFragment()
        NativeRoute.MOVIES -> MoviesFragment()
        NativeRoute.SHOWS -> ShowsFragment()
        NativeRoute.MUSIC -> MusicFragment()
        NativeRoute.SEARCH -> SearchFragment()
        NativeRoute.SETTINGS -> SettingsFragment()
        NativeRoute.PROFILE -> ProfileFragment()
    }

    private fun restoreCachedFragments() {
        detailsFragment = supportFragmentManager.findFragmentByTag(DETAILS_TAG)
        visibleFragment = supportFragmentManager.findFragmentByTag(routeTag(currentRoute))
            ?: supportFragmentManager.fragments.lastOrNull { it !== detailsFragment }
        NativeRoute.entries.forEach { route ->
            supportFragmentManager.findFragmentByTag(routeTag(route))?.let { routeFragments[route] = it }
        }
        if (
            visibleFragment?.tag == routeTag(currentRoute) &&
            routeFragments[currentRoute] == null
        ) {
            routeFragments[currentRoute] = visibleFragment!!
        }
    }

    private fun routeTag(route: NativeRoute) = "$ROUTE_TAG_PREFIX${route.name.lowercase()}"

    private fun isRegisteredRouteFragment(fragment: Fragment): Boolean =
        NativeRoute.entries.any { routeTag(it) == fragment.tag }

    fun showDetails(item: MediaItem) {
        MediaDetailsSeedStore.put(item)
        val existing = detailsFragment
        if (existing != null) return

        focusBeforeDetails = WeakReference(currentFocus)
        detailsRequestedAtMs = android.os.SystemClock.elapsedRealtime()
        detailsTraceCookie = beginAsyncTrace("PiggieTV#details:firstInteractive")
        val fragment = MediaDetailsFragment.newInstance(item.id)
        detailsFragment = fragment

        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .apply {
                visibleFragment?.takeIf { it.isAdded }?.let {
                    hide(it).setMaxLifecycle(it, Lifecycle.State.STARTED)
                }
                add(contentFrame.id, fragment, DETAILS_TAG)
                setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
                setPrimaryNavigationFragment(fragment)
            }
            .commit()
    }

    fun onDetailsInteractive(interactiveAtMs: Long) {
        if (detailsFragment == null) return
        val elapsed = interactiveAtMs - detailsRequestedAtMs
        Log.i(PERFORMANCE_TAG, "details interactiveMs=$elapsed")
        Trace.beginSection("PiggieTV#details:firstInteractive:${elapsed}ms")
        Trace.endSection()
        endDetailsTrace()
    }

    private fun closeDetails(): Boolean {
        val fragment = detailsFragment ?: return false
        if ((fragment as? MediaDetailsFragment)?.handleBackWithinDetails() == true) {
            return true
        }
        val closeRequestedAtMs = android.os.SystemClock.elapsedRealtime()
        detailsFragment = null
        endDetailsTrace()
        val route = visibleFragment
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .remove(fragment)
            .apply {
                route?.takeIf { it.isAdded }?.let {
                    show(it).setMaxLifecycle(it, Lifecycle.State.RESUMED)
                    setPrimaryNavigationFragment(it)
                }
            }
            .runOnCommit {
                focusBeforeDetails?.get()?.takeIf { it.isAttachedToWindow }?.requestFocus()
                focusBeforeDetails = null
            }
            .commitNow()
        Log.i(
            PERFORMANCE_TAG,
            "details returnMs=${android.os.SystemClock.elapsedRealtime() - closeRequestedAtMs}"
        )
        return true
    }

    private fun endDetailsTrace() {
        if (detailsTraceCookie != NO_TRACE) {
            endAsyncTrace("PiggieTV#details:firstInteractive", detailsTraceCookie)
            detailsTraceCookie = NO_TRACE
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val trackedKey = event.action == KeyEvent.ACTION_DOWN && event.keyCode in TRACKED_DPAD_KEYS
        if (trackedKey) PtvDiagnosticsManager.markUiInteraction()
        val diagnostics = trackedKey && PtvDiagnosticsManager.shouldTraceInput()
        val traceEnabled = diagnostics && isSystemTraceEnabled()
        val tracked = diagnostics || traceEnabled
        val previous = if (diagnostics) describeFocus(currentFocus) else null
        val traceName = if (tracked) "PiggieTV#input:${KeyEvent.keyCodeToString(event.keyCode)}" else ""
        val inputTrace = if (tracked) beginAsyncTrace(traceName) else NO_TRACE
        val handled = super.dispatchKeyEvent(event)
        if (tracked) {
            window.decorView.postOnAnimation {
                if (diagnostics) {
                    val resulting = describeFocus(currentFocus)
                    PtvDiagnosticsManager.recordFocus(
                        PtvFocusTrace(
                            route = currentRoute.name.lowercase(),
                            direction = KeyEvent.keyCodeToString(event.keyCode),
                            previousFocus = previous,
                            resultingFocus = resulting,
                            resultingBounds = focusBounds(currentFocus),
                            failure = when {
                                resulting == null -> "no focus owner"
                                resulting == previous && event.keyCode != KeyEvent.KEYCODE_DPAD_CENTER -> "focus did not move"
                                currentFocus?.visibility != View.VISIBLE -> "focused view is not visible"
                                else -> null
                            }
                        )
                    )
                }
                endAsyncTrace(traceName, inputTrace)
            }
        }
        return handled
    }

    @SuppressLint("NewApi") // Guarded by isSystemTraceEnabled(), which requires API 29.
    private fun beginAsyncTrace(name: String): Int {
        if (!isSystemTraceEnabled()) return NO_TRACE
        val cookie = traceSequence.incrementAndGet()
        Trace.beginAsyncSection(name, cookie)
        return cookie
    }

    private fun isSystemTraceEnabled() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()

    @SuppressLint("NewApi") // A non-sentinel cookie can only be created on API 29+.
    private fun endAsyncTrace(name: String, cookie: Int) {
        if (cookie != NO_TRACE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.endAsyncSection(name, cookie)
        }
    }

    private fun describeFocus(view: View?): String? {
        view ?: return null
        val id = view.id.takeIf { it != View.NO_ID }?.let { idValue ->
            runCatching { resources.getResourceEntryName(idValue) }.getOrNull()
        }
        return (id ?: view.javaClass.simpleName) + if (view.contentDescription != null) ":${view.contentDescription}" else ""
    }

    private fun focusBounds(view: View?): String? {
        view ?: return null
        val rect = Rect()
        return if (view.getGlobalVisibleRect(rect)) "${rect.left},${rect.top},${rect.right},${rect.bottom}" else "not-visible"
    }

    private fun returnToLogin() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        })
        finish()
    }

    companion object {
        private const val ACTION_RETURN_HOME = "com.piggie.tv.action.RETURN_HOME"

        /**
         * Brings the existing TV host to the front and selects Home without rebuilding its route
         * cache. CLEAR_TOP removes the player (and any transient screen above the host), while
         * SINGLE_TOP guarantees delivery through onNewIntent() to the existing host instance.
         */
        internal fun returnHomeIntent(context: Context): Intent =
            Intent(context, PtvHostActivity::class.java).apply {
                action = ACTION_RETURN_HOME
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

        internal fun requestedRoute(intent: Intent?): NativeRoute? =
            NativeRoute.HOME.takeIf { intent?.action == ACTION_RETURN_HOME }

        /** Consumes the handoff so a later host recreation restores its then-current route. */
        internal fun consumeRequestedRoute(intent: Intent?): NativeRoute? =
            requestedRoute(intent)?.also { intent?.action = null }

        // Vertically virtualized discovery pages retain only visible/near-visible shelves.
        // Keeping the previous route avoids reconstructing those shelves during TV round trips.
        private const val ROUTE_CACHE_SIZE = 2
        private const val ROUTE_TAG_PREFIX = "ptv-route-"
        private const val DETAILS_TAG = "ptv-details"
        private const val NO_TRACE = -1
        private const val PERFORMANCE_TAG = "PtvPerformance"
        private val TRACKED_DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER
        )
    }
}
