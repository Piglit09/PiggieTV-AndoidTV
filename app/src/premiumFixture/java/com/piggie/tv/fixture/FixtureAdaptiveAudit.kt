package com.piggie.tv.fixture

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.fragment.app.FragmentActivity
import com.piggie.tv.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Disposable, read-only Home measurements. No session, URL, or token is retained. */
object FixtureAdaptiveAudit {
    private val imageEvents = ConcurrentHashMap<String, AtomicInteger>()
    private val idPattern = Regex("[0-9a-f]{32}")

    fun recordImageEvent(category: String, event: String) {
        if (category != "hero" || event !in setOf(
                "request_start", "decode_start", "memory_cache_hit", "disk_success",
                "network_success", "cancel", "error"
            )) return
        imageEvents.computeIfAbsent(event) { AtomicInteger() }.incrementAndGet()
    }

    fun imageSnapshot(): Map<String, Int> = imageEvents.mapValues { it.value.get() }.toSortedMap()

    internal fun resetForTest() = imageEvents.clear()

    data class Presentation(val mode: String?, val animationActive: Boolean?, val generation: Int?)

    /** Private-field inspection stays in the disposable source set. Control has no coordinator. */
    fun capturePresentation(activity: Activity): Presentation {
        val fragment = (activity as? FragmentActivity)?.supportFragmentManager?.fragments
            ?.singleOrNull { it.javaClass.simpleName == "HomeFragment" && it.isVisible }
            ?: return Presentation(null, null, null)
        val coordinator = runCatching {
            Class.forName("com.piggie.tv.ui.discovery.BaseDiscoveryFragment")
                .getDeclaredField("homeHeroCoordinator").apply { isAccessible = true }
                .get(fragment)
        }.getOrNull() ?: return Presentation(null, null, null)
        fun read(prefix: String): Any? = runCatching {
            coordinator.javaClass.methods.singleOrNull { it.name.startsWith(prefix) &&
                it.parameterCount == 0 }?.invoke(coordinator)
        }.getOrNull()
        return Presentation(read("getPresentation")?.toString(),
                            read("getAnimationActive") as? Boolean,
                            read("getTransitionGeneration") as? Int)
    }

    data class Structure(
        val heroViewIdentity: Int?,
        val heroItemId: String?,
        val heroBounds: String,
        val heroHeight: Int?,
        val pageViewIdentity: Int?,
        val pageAdapterIdentity: Int?,
        val pageAdapterItemCount: Int?,
        val pageFirstVisiblePosition: Int?,
        val pageBounds: String,
        val visibleRowIdentities: List<Int>,
        val visibleRowTitles: List<String>,
        val rowBounds: List<String>,
        val rowVisibleBounds: List<String>,
        val visibleCardIdentities: List<Int>,
        val visibleCardIds: List<String>,
        val cardBounds: List<String>,
        val cardVisibleBounds: List<String>,
        val focusedViewIdentity: Int?,
        val focusedAction: String?,
    )

    fun captureStructure(root: View): Structure {
        val hero = root.findViewById<View>(R.id.hero_row)
        val page = allViews(root).filterIsInstance<RecyclerView>().firstOrNull { recycler ->
            (recycler.layoutManager as? LinearLayoutManager)?.orientation == RecyclerView.VERTICAL &&
                (hero == null || isDescendant(recycler, hero))
        } ?: allViews(root).filterIsInstance<RecyclerView>().firstOrNull { recycler ->
            (recycler.layoutManager as? LinearLayoutManager)?.orientation == RecyclerView.VERTICAL
        }
        val rows = (0 until (page?.childCount ?: 0)).map { page!!.getChildAt(it) }
            .filter { it.visibility == View.VISIBLE && it.id != R.id.hero_row }
        val cards = allViews(root).filter { view ->
            view.visibility == View.VISIBLE &&
                (view.getTag(R.id.ptv_discovery_item_id) as? String)?.matches(idPattern) == true
        }.toList()
        val focused = allViews(root).firstOrNull { it.isFocused }
        return Structure(
            heroViewIdentity = hero?.let(System::identityHashCode),
            heroItemId = hero?.let(::heroItemId),
            heroBounds = hero?.let(::bounds) ?: "absent",
            heroHeight = hero?.height,
            pageViewIdentity = page?.let(System::identityHashCode),
            pageAdapterIdentity = page?.adapter?.let(System::identityHashCode),
            pageAdapterItemCount = page?.adapter?.itemCount,
            pageFirstVisiblePosition = (page?.layoutManager as? LinearLayoutManager)
                ?.findFirstVisibleItemPosition(),
            pageBounds = page?.let(::bounds) ?: "absent",
            visibleRowIdentities = rows.map(System::identityHashCode),
            visibleRowTitles = rows.map { row ->
                allViews(row).filterIsInstance<TextView>().firstOrNull()?.text?.toString() ?: ""
            },
            rowBounds = rows.map(::bounds),
            rowVisibleBounds = rows.map(::visibleBounds),
            visibleCardIdentities = cards.map(System::identityHashCode),
            visibleCardIds = cards.mapNotNull { it.getTag(R.id.ptv_discovery_item_id) as? String },
            cardBounds = cards.map(::bounds),
            cardVisibleBounds = cards.map(::visibleBounds),
            focusedViewIdentity = focused?.let(System::identityHashCode),
            focusedAction = (focused as? TextView)?.text?.toString()
                ?: focused?.contentDescription?.toString()
                ?: focused?.javaClass?.simpleName,
        )
    }

    private fun bounds(view: View): String {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return "${location[0]},${location[1]},${location[0] + view.width},${location[1] + view.height}"
    }

    private fun heroItemId(hero: View): String? = runCatching {
        val candidate = hero.javaClass.getDeclaredField("current")
            .apply { isAccessible = true }.get(hero) ?: return@runCatching null
        val item = candidate.javaClass.getMethod("getItem").invoke(candidate)
        val id = item.javaClass.getMethod("getId").invoke(item) as? String
        id?.takeIf(idPattern::matches)
    }.getOrNull()

    private fun visibleBounds(view: View): String {
        val result = Rect()
        return if (view.getGlobalVisibleRect(result)) {
            "${result.left},${result.top},${result.right},${result.bottom}"
        } else "none"
    }

    private fun isDescendant(ancestor: ViewGroup, child: View): Boolean {
        var current = child.parent
        while (current is View) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    private fun allViews(root: View): Sequence<View> = sequence {
        if (root.visibility != View.VISIBLE) return@sequence
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            yieldAll(allViews(root.getChildAt(index)))
        }
    }
}
