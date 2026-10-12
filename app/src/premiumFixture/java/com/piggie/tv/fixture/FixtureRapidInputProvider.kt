package com.piggie.tv.fixture

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import com.piggie.tv.BuildConfig
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** Fixed rapid D-pad probe on the disposable package; every key has a fresh in-app guard. */
class FixtureRapidInputProvider : ContentProvider() {
    @Volatile private var resumedActivity: WeakReference<Activity>? = null
    private val running = AtomicBoolean(false)
    private val generation = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(): Boolean {
        if (!BuildConfig.DEBUG || !BuildConfig.LOCAL_FIXTURE_ONLY) return false
        val application = context?.applicationContext as? Application ?: return false
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) { resumedActivity = WeakReference(activity) }
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
        if (!BuildConfig.DEBUG || !BuildConfig.LOCAL_FIXTURE_ONLY || method != "down-up-down-up" ||
            ownUid == null || Binder.getCallingUid() !in setOf(Process.SHELL_UID, ownUid)
        ) return answer("denied")
        if (!running.compareAndSet(false, true)) return answer("already_running")
        val thisGeneration = generation.incrementAndGet()
        val completed = CountDownLatch(1)
        val downTimes = LongArray(KEYS.size)
        val upTimes = LongArray(KEYS.size)
        val downUptime = LongArray(KEYS.size)
        val upUptime = LongArray(KEYS.size)
        val states = JSONArray()
        var status = "incomplete"
        val activity = resumedActivity?.get()

        fun guard(): Boolean = running.get() && generation.get() == thisGeneration &&
            activity != null && resumedActivity?.get() === activity &&
            activity.packageName == BuildConfig.APPLICATION_ID && activity.hasWindowFocus() &&
            !activity.isFinishing && !activity.isDestroyed && Process.myPid() > 0 &&
            FixtureCardAuditProvider.captureVisibleTree(activity.window.decorView).route == "Home" &&
            activity.window.decorView.findFocus() != null

        fun record(index: Int, phase: String) {
            val tree = FixtureAdaptiveAudit.captureStructure(activity!!.window.decorView)
            val presentation = FixtureAdaptiveAudit.capturePresentation(activity)
            states.put(JSONObject().apply {
                put("index", index); put("phase", phase)
                put("elapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos())
                put("heroHeight", tree.heroHeight ?: -1)
                put("heroBounds", tree.heroBounds)
                put("heroIdentity", tree.heroViewIdentity ?: -1)
                put("heroItemId", tree.heroItemId ?: "unknown")
                put("pageIdentity", tree.pageViewIdentity ?: -1)
                put("adapterIdentity", tree.pageAdapterIdentity ?: -1)
                put("focusedIdentity", tree.focusedViewIdentity ?: -1)
                put("focusedAction", tree.focusedAction ?: "unknown")
                put("firstVisiblePosition", tree.pageFirstVisiblePosition ?: -1)
                put("presentationMode", presentation.mode ?: "unknown")
                put("presentationAnimating", presentation.animationActive ?: false)
                put("presentationGeneration", presentation.generation ?: -1)
            })
        }

        fun finish(value: String) {
            status = value
            running.set(false)
            completed.countDown()
        }

        fun step(index: Int) {
            if (!guard()) { finish("foreground_guard_failed_before_down_$index"); return }
            val target = activity!!
            val code = KEYS[index]
            record(index, "before")
            val now = SystemClock.uptimeMillis()
            val pressed = target.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            downUptime[index] = now
            downTimes[index] = SystemClock.elapsedRealtimeNanos()
            if (!guard()) { finish("foreground_guard_failed_before_up_$index"); return }
            val upNow = SystemClock.uptimeMillis()
            val released = target.dispatchKeyEvent(KeyEvent(now, upNow, KeyEvent.ACTION_UP, code, 0))
            upUptime[index] = upNow
            upTimes[index] = SystemClock.elapsedRealtimeNanos()
            record(index, "after")
            if (!pressed && !released) { finish("key_unhandled_at_$index"); return }
            if (index == KEYS.lastIndex) finish("ok")
            else main.postDelayed({ step(index + 1) }, INTER_KEY_DELAY_MS)
        }
        main.post { step(0) }
        if (!completed.await(5, TimeUnit.SECONDS)) {
            running.set(false)
            generation.incrementAndGet()
            return answer("timeout")
        }
        return answer(status).apply {
            putInt("pid", Process.myPid())
            putLongArray("downTimesNanos", downTimes)
            putLongArray("upTimesNanos", upTimes)
            putLongArray("downUptimeMs", downUptime)
            putLongArray("upUptimeMs", upUptime)
            putString("downUptimeCsv", downUptime.joinToString(","))
            putString("upUptimeCsv", upUptime.joinToString(","))
            putString("statesJson", states.toString())
            putStringArrayList("keys", ArrayList(sequenceLabels()))
        }
    }

    private fun answer(status: String) = Bundle().apply { putString("status", status) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Fixture rapid input has no storage")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Fixture rapid input has no storage")
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Fixture rapid input has no storage")

    companion object {
        const val INTER_KEY_DELAY_MS = 100L
        private val KEYS = intArrayOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP,
                                      KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP)
        fun sequenceLabels() = listOf("DOWN", "UP", "DOWN", "UP")
    }
}
