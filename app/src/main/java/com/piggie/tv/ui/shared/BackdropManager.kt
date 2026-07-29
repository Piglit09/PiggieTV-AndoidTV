package com.piggie.tv.ui.shared

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.ImageView
import coil.dispose
import coil.load
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.ui.rendering.RendererFeatures
import com.piggie.tv.ui.rendering.TvRenderingRuntime
import java.util.WeakHashMap

enum class BackdropPurpose {
    HERO,
    BROWSING
}

object BackdropPolicy {
    fun shouldUpdate(purpose: BackdropPurpose, features: RendererFeatures): Boolean =
        purpose == BackdropPurpose.HERO || features.dynamicBrowsingBackdrops
}

object BackdropManager {
    private const val FOCUS_DEBOUNCE_MS = 180L
    private const val MAX_BACKDROP_WIDTH = 1280

    private data class PendingBackdrop(val url: String, val runnable: Runnable)

    private val pending = WeakHashMap<ImageView, PendingBackdrop>()
    private val displayedUrls = WeakHashMap<ImageView, String>()

    fun update(
        view: ImageView,
        overlay: View?,
        session: NativeSession,
        item: MediaItem?,
        api: JellyfinNativeApi,
        purpose: BackdropPurpose = BackdropPurpose.BROWSING
    ) {
        if (!BackdropPolicy.shouldUpdate(purpose, TvRenderingRuntime.features())) return
        pending.remove(view)?.let { view.removeCallbacks(it.runnable) }
        if (item == null) {
            view.dispose()
            displayedUrls.remove(view)
            view.setImageDrawable(ColorDrawable(PTVColors.background))
            overlay?.visibility = View.GONE
            return
        }

        val url = api.backdropUrl(session, item, MAX_BACKDROP_WIDTH)
        overlay?.visibility = View.VISIBLE
        if (displayedUrls[view] == url) return

        val request = Runnable {
            if (pending[view]?.url != url || !view.isAttachedToWindow) return@Runnable
            pending.remove(view)
            view.load(url) {
                crossfade(500)
                placeholder(view.drawable)
                error(ColorDrawable(PTVColors.background))
                listener(
                    onSuccess = { _, _ ->
                        displayedUrls[view] = url
                        // Ensure view stays full-screen and animated
                    }
                )
            }
        }
        pending[view] = PendingBackdrop(url, request)
        view.postDelayed(request, FOCUS_DEBOUNCE_MS)
    }

    fun updateHero(
        view: ImageView,
        overlay: View?,
        session: NativeSession,
        item: MediaItem?,
        api: JellyfinNativeApi
    ) = update(view, overlay, session, item, api, BackdropPurpose.HERO)

    fun updateBrowsing(
        view: ImageView,
        overlay: View?,
        session: NativeSession,
        item: MediaItem?,
        api: JellyfinNativeApi
    ) = update(view, overlay, session, item, api, BackdropPurpose.BROWSING)

    fun clear(view: ImageView) {
        pending.remove(view)?.let { view.removeCallbacks(it.runnable) }
        displayedUrls.remove(view)
        view.dispose()
    }
}
