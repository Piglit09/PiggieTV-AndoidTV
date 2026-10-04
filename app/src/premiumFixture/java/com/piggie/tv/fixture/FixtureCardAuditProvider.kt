package com.piggie.tv.fixture

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.piggie.tv.BuildConfig
import com.piggie.tv.R
import com.piggie.tv.ui.library.LibraryBrowserActivity
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Read-only, shell-only card-ID evidence available solely in the disposable build. */
class FixtureCardAuditProvider : ContentProvider() {
    @Volatile private var resumedActivity: WeakReference<Activity>? = null

    override fun onCreate(): Boolean {
        if (!BuildConfig.DEBUG || !BuildConfig.LOCAL_FIXTURE_ONLY) return false
        val application = context?.applicationContext as? Application ?: return false
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity?.get() === activity) resumedActivity = null
            }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (resumedActivity?.get() === activity) resumedActivity = null
            }
        })
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ownUid = context?.applicationInfo?.uid
        if (!BuildConfig.DEBUG || !BuildConfig.LOCAL_FIXTURE_ONLY || method != "snapshot" ||
            ownUid == null || Binder.getCallingUid() !in setOf(Process.SHELL_UID, ownUid)
        ) return status("denied")

        val activity = resumedActivity?.get() ?: return status("no_resumed_activity")
        if (Looper.myLooper() == Looper.getMainLooper()) return safeCaptureActivity(activity)
        val value = AtomicReference<Bundle>()
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                value.set(safeCaptureActivity(activity))
            } finally {
                done.countDown()
            }
        }
        return if (done.await(2, TimeUnit.SECONDS)) value.get() ?: status("capture_failed")
               else status("capture_timeout")
    }

    private fun safeCaptureActivity(activity: Activity): Bundle =
        try {
            captureActivity(activity)
        } catch (_: RuntimeException) {
            status("capture_failed")
        }

    private fun captureActivity(activity: Activity): Bundle {
        if (!activity.hasWindowFocus() || activity.isFinishing || activity.isDestroyed) {
            return status("not_focused_fixture")
        }
        val snapshot = captureVisibleTree(activity.window.decorView)
        if (snapshot.invalidTagCount != 0) return status("invalid_card_tag")
        val browser = if (activity is LibraryBrowserActivity) browserScope(activity.intent) else null
        val route = browser?.first ?: snapshot.route ?: return status("unknown_route")
        return status("ok").apply {
            putInt("schema", 1)
            putString("package", BuildConfig.APPLICATION_ID)
            putInt("pid", Process.myPid())
            putString("activity", activity.javaClass.simpleName)
            putString("route", route)
            putString("libraryId", browser?.second)
            putStringArrayList("itemIds", ArrayList(snapshot.itemIds))
            putString("focusedItemId", snapshot.focusedItemId)
        }
    }

    private fun status(value: String) = Bundle().apply { putString("status", value) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Fixture card audit is read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Fixture card audit is read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Fixture card audit is read-only")

    data class VisibleTreeSnapshot(
        val route: String?,
        val itemIds: List<String>,
        val focusedItemId: String?,
        val invalidTagCount: Int,
    )

    companion object {
        private val ID = Regex("[0-9a-f]{32}")
        private val MAIN_ROUTES = setOf("Home", "Movies", "Shows", "Music", "Search", "Settings")
        private val LIBRARY_ROUTES = setOf("Movies", "Shows", "Anime", "Cartoons")
        private val SHOWS_TABS = listOf(
            R.id.ptv_shows_tab_shows to "Shows",
            R.id.ptv_shows_tab_anime to "Anime",
            R.id.ptv_shows_tab_cartoons to "Cartoons",
        )

        internal fun browserScope(intent: Intent): Pair<String, String?>? {
            val name = intent.getStringExtra("library_name") ?: return null
            val id = intent.getStringExtra("lib_id")
            return if (name in LIBRARY_ROUTES && (id == null || ID.matches(id))) name to id else null
        }

        internal fun captureVisibleTree(root: View): VisibleTreeSnapshot {
            val ids = linkedSetOf<String>()
            var focused: String? = null
            var invalid = 0
            val selectedTabs = mutableListOf<String>()
            fun visit(view: View) {
                if (view.visibility != View.VISIBLE) return
                SHOWS_TABS.firstOrNull { it.first == view.id }?.let { (_, route) ->
                    if (view.isSelected) selectedTabs.add(route)
                }
                val tag = view.getTag(R.id.ptv_discovery_item_id)
                if (tag != null) {
                    if (tag is String && ID.matches(tag)) {
                        ids.add(tag)
                        if (view.hasFocus()) focused = tag
                    } else invalid++
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) visit(view.getChildAt(index))
                }
            }
            visit(root)
            val rail = root.findViewById<ViewGroup>(R.id.ptv_nav_rail)
            val selectedMain = (0 until (rail?.childCount ?: 0))
                .map { rail!!.getChildAt(it) }
                .filterIsInstance<Button>()
                .filter { it.visibility == View.VISIBLE && it.isSelected }
                .map { it.text.toString() }
            val main = selectedMain.singleOrNull()?.takeIf { it in MAIN_ROUTES }
            val route = if (main == "Shows") selectedTabs.singleOrNull() else main
            return VisibleTreeSnapshot(route, ids.toList(), focused, invalid)
        }
    }
}
