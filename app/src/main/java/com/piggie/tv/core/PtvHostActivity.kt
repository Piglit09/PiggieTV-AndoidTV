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
import com.piggie.tv.auth.AuthFailurePolicy
import com.piggie.tv.auth.AuthLogout
import com.piggie.tv.auth.AuthenticationRejection
import com.piggie.tv.auth.AuthSessionCoordinator
import com.piggie.tv.auth.SessionRestoreDestination
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.api.NativeRequestScope
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.playback.MusicPlaybackManager
import com.piggie.tv.data.session.NativeSettings
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.data.session.SessionReadResult
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
import com.piggie.tv.updates.ReleaseUpdateManager
import com.piggie.tv.diagnostics.PerformanceMonitor
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import com.piggie.tv.diagnostics.PtvFocusTrace
import com.piggie.tv.memory.MemoryPressurePolicy
import com.piggie.tv.memory.MemoryPressureParticipant
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.lang.ref.WeakReference
import kotlin.concurrent.thread

class PtvHostActivity : AppCompatActivity() {
    private val store by lazy { SecureSessionStore(this) }
    private val sessionApi by lazy { JellyfinNativeApi(this) }
    private val releaseUpdateManager by lazy { ReleaseUpdateManager(this) }
    private var activeSession: NativeSession? = null
    private var completedInitialResume = false
    private var sessionRevalidationGeneration = 0L
    private var sessionRevalidationScope: NativeRequestScope? = null
    private val authenticationRejectionListener: (AuthenticationRejection) -> Unit = { rejection ->
        runOnUiThread {
            val candidate = activeSession ?: return@runOnUiThread
            if (
                isFinishing ||
                isDestroyed ||
                !AuthSessionCoordinator.isValidated(candidate) ||
                !AuthSessionCoordinator.rejectionMatches(rejection, candidate)
            ) {
                return@runOnUiThread
            }
            clearRejectedSession(candidate)
        }
    }
    val session: NativeSession
        get() = requireNotNull(activeSession) { "An authenticated session is required" }
    private lateinit var contentFrame: FrameLayout
    private lateinit var navigationRail: View
    private var navigation = emptyMap<NativeRoute, Button>()
    private var currentRoute = NativeRoute.HOME
    private val routeFragments = LinkedHashMap<NativeRoute, Fragment>(ROUTE_CACHE_SIZE, 0.75f, true)
    private var visibleFragment: Fragment? = null
    private var detailsFragment: Fragment? = null
    private var detailsItemType: String? = null
    private var focusBeforeDetails: WeakReference<View>? = null
    private var detailsTraceCookie = NO_TRACE
    private var detailsRequestedAtMs = 0L
    private val traceSequence = AtomicInteger()

    override fun onCreate(savedInstanceState: Bundle?) {
        val storedSession = (store.readResult() as? SessionReadResult.Available)?.session
        if (
            storedSession == null ||
            !storedSession.isComplete() ||
            !AuthSessionCoordinator.isValidated(storedSession)
        ) {
            activeSession = null
            // Discard FragmentManager restoration when authentication is unavailable. Restored
            // fragments synchronously request the host session from super.onCreate().
            super.onCreate(null)
            returnToLogin()
            return
        }
        activeSession = storedSession
        // FragmentActivity restores retained fragments from super.onCreate(). They can create
        // their views synchronously and read the host session, so the session must exist first.
        super.onCreate(savedInstanceState)
        completedInitialResume = savedInstanceState != null
        AuthSessionCoordinator.addAuthenticationRejectionListener(authenticationRejectionListener)

        val requestedLaunchRoute = consumeRequestedRoute(intent)
        if (savedInstanceState != null) {
            val restoredRoute = savedInstanceState.getString("current_route", NativeRoute.HOME.name)
            currentRoute = NativeRouteNavigator.restoreTarget(restoredRoute)
        } else if (requestedLaunchRoute != null) {
            currentRoute = requestedLaunchRoute
        }
        
        MusicPlaybackManager.init(this)
        
        val shell = NativePtvShell.create(this, currentRoute, ::resolveContentUpExit, ::showRoute)
        contentFrame = shell.content
        navigation = shell.navigation
        navigationRail = shell.navigationRail
        restoreCachedFragments()
        if (detailsFragment != null) {
            detailsItemType = savedInstanceState?.getString(DETAILS_ITEM_TYPE_STATE)
            NativePtvShell.applyRailPresentation(navigationRail, currentRoute, detailsItemType)
        }
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
            val selectedNavigation = NativeRouteNavigator.navigationSelection(currentRoute)
            navigation.forEach { (route, button) -> button.isSelected = route == selectedNavigation }
            visibleFragment?.let { linkPageAndNavigationFocus(currentRoute, it) }
        }

        PerformanceMonitor.setVisible(this, NativeSettings(this).diagnosticsOverlayEnabled)
        releaseUpdateManager.checkForUpdates(session, force = false, onResult = null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val requestedRoute = consumeRequestedRoute(intent)
        setIntent(intent)
        requestedRoute?.let(::showRoute)
    }

    override fun onResume() {
        super.onResume()
        // MainActivity has just validated the session before the first host resume. Revalidate on
        // later foreground returns so a token revoked while the task stays alive recovers cleanly.
        if (!completedInitialResume) {
            completedInitialResume = true
            return
        }
        revalidateActiveSession()
    }

    override fun onPause() {
        cancelSessionRevalidation()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("current_route", currentRoute.name)
        if (detailsFragment != null) outState.putString(DETAILS_ITEM_TYPE_STATE, detailsItemType)
    }

    override fun onDestroy() {
        cancelSessionRevalidation()
        AuthSessionCoordinator.removeAuthenticationRejectionListener(authenticationRejectionListener)
        super.onDestroy()
        activeSession = null
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
        val leavingProfile = currentRoute == NativeRoute.PROFILE
        val target = NativeRouteNavigator.backTarget(currentRoute) ?: return false
        showRoute(target)
        if (!leavingProfile) navigation[target]?.requestFocus()
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
            if (target == NativeRoute.SETTINGS) {
                visible?.let { linkPageAndNavigationFocus(target, it) }
            } else {
                navigation[NativeRouteNavigator.navigationSelection(target)]?.requestFocus()
            }
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
        detailsItemType = null
        PtvDiagnosticsManager.routeRequested(route)
        NativePtvShell.applyRailPresentation(navigationRail, target)
        val selectedNavigation = NativeRouteNavigator.navigationSelection(target)
        navigation.forEach { (navRoute, button) -> button.isSelected = navRoute == selectedNavigation }
        if (target != NativeRoute.SETTINGS && target != NativeRoute.PROFILE) {
            navigation[NativeRoute.SETTINGS]?.nextFocusDownId = View.NO_ID
        }

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
            if (currentRoute == target && detailsFragment == null) {
                linkPageAndNavigationFocus(target, fragment)
            }
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

        if (currentFocus == null) navigation[selectedNavigation]?.requestFocus()
    }

    private fun linkPageAndNavigationFocus(route: NativeRoute, fragment: Fragment) {
        val firstControl = when (route) {
            NativeRoute.SETTINGS -> (fragment as? SettingsFragment)?.firstControlForNavigation()
            NativeRoute.PROFILE -> fragment.view?.findViewById<View>(R.id.ptv_profile_settings_entry)
            else -> return
        } ?: return
        val settingsNavigation = navigation[NativeRoute.SETTINGS] ?: return
        if (firstControl.id == View.NO_ID) firstControl.id = View.generateViewId()
        settingsNavigation.nextFocusDownId = firstControl.id
        firstControl.nextFocusUpId = settingsNavigation.id
        firstControl.requestFocus()
    }

    fun updateSettingsFirstControl(firstControl: View) {
        if (currentRoute != NativeRoute.SETTINGS) return
        val settingsNavigation = navigation[NativeRoute.SETTINGS] ?: return
        if (firstControl.id == View.NO_ID) firstControl.id = View.generateViewId()
        settingsNavigation.nextFocusDownId = firstControl.id
        firstControl.nextFocusUpId = settingsNavigation.id
    }

    fun updateShowsSecondaryControls(controls: List<View>, selected: View?) {
        if (currentRoute != NativeRoute.SHOWS) return
        val showsNavigation = navigation[NativeRoute.SHOWS] ?: return
        controls.forEach { control -> control.nextFocusUpId = showsNavigation.id }
        selected?.let { showsNavigation.nextFocusDownId = it.id }
    }

    private fun resolveContentUpExit(focused: View, defaultTarget: View): View {
        if (navigation.values.none { it === defaultTarget }) return defaultTarget
        if (detailsFragment != null) return defaultTarget

        val routeView = visibleFragment
            ?.takeIf { it.tag == routeTag(currentRoute) && it.isAdded && !it.isHidden }
            ?.view
        if (routeView == null || !isDescendantOf(focused, routeView)) return focused

        val routeNavigation = navigation[NativeRouteNavigator.navigationSelection(currentRoute)]
        return routeNavigation
            ?.takeIf {
                it.parent === navigationRail &&
                    it.visibility == View.VISIBLE &&
                    it.isFocusable &&
                    it.width > 0 && it.height > 0
            }
            ?: focused
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
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
        detailsItemType = item.type
        NativePtvShell.applyRailPresentation(navigationRail, currentRoute, detailsItemType)

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
        detailsItemType = null
        NativePtvShell.applyRailPresentation(navigationRail, currentRoute)
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

    private fun revalidateActiveSession() {
        cancelSessionRevalidation()
        val candidate = activeSession ?: return
        if (!AuthSessionCoordinator.isValidated(candidate)) {
            returnToLogin()
            return
        }
        val scope = NativeRequestScope()
        val generation = ++sessionRevalidationGeneration
        sessionRevalidationScope = scope
        thread(name = "ptv-active-session-validation") {
            val result = runCatching {
                sessionApi.withRequestScope(scope) { sessionApi.validateSession(candidate) }
            }
            runOnUiThread {
                if (!canApplySessionRevalidation(generation, scope, candidate)) return@runOnUiThread
                sessionRevalidationScope = null
                result.onSuccess { refreshed ->
                    // Mutable display/admin metadata may refresh in memory. Persistent writes stay
                    // inside AuthSessionCoordinator so this lifecycle check cannot race logout.
                    if (AuthSessionCoordinator.isValidated(refreshed)) activeSession = refreshed
                }.onFailure { error ->
                    val decision = AuthFailurePolicy.validationFailure(error)
                    if (
                        decision.destination == SessionRestoreDestination.LOGIN &&
                        decision.clearAuthentication
                    ) {
                        clearRejectedSession(candidate)
                    }
                    // Connectivity, server, parse, and cancellation failures leave both the
                    // validated in-memory marker and durable credentials untouched.
                }
            }
        }
    }

    private fun canApplySessionRevalidation(
        generation: Long,
        scope: NativeRequestScope,
        candidate: NativeSession,
    ): Boolean =
        generation == sessionRevalidationGeneration &&
            sessionRevalidationScope === scope &&
            lifecycle.currentState == Lifecycle.State.RESUMED &&
            !isFinishing &&
            !isDestroyed &&
            activeSession === candidate &&
            AuthSessionCoordinator.isValidated(candidate)

    private fun cancelSessionRevalidation() {
        sessionRevalidationGeneration += 1L
        sessionRevalidationScope?.cancel()
        sessionRevalidationScope = null
    }

    private fun clearRejectedSession(candidate: NativeSession) {
        if (activeSession !== candidate || !AuthSessionCoordinator.isValidated(candidate)) return
        cancelSessionRevalidation()
        val cleared = AuthLogout.clearLocalAuthentication(
            store,
            sessionApi::cancelInFlightRequests,
        )
        activeSession = null
        returnToLogin(storageClearFailed = !cleared)
    }

    private fun returnToLogin(storageClearFailed: Boolean = false) {
        startActivity(AuthLogout.loginIntent(this, storageClearFailed))
        finish()
    }

    fun signOut() {
        cancelSessionRevalidation()
        val cleared = AuthLogout.clearLocalAuthentication(store)
        activeSession = null
        AuthLogout.returnToLogin(this, storageClearFailed = !cleared)
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
        private const val DETAILS_ITEM_TYPE_STATE = "ptv_details_item_type"
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
