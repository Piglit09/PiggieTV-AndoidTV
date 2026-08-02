package com.piggie.tv.ui.shows

import android.app.Activity
import com.piggie.tv.data.api.NativeRequestScope
import com.piggie.tv.data.discovery.DiscoveryPage
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.ui.discovery.BaseDiscoveryFragment
import com.piggie.tv.ui.hero.HeroRoute
import com.piggie.tv.ui.hero.HeroSource
import kotlin.concurrent.thread

class ShowsFragment : BaseDiscoveryFragment() {
    override val discoveryPage = DiscoveryPage.SHOWS
    override val heroRoute = HeroRoute.SHOWS
    private var heroRequestScope: NativeRequestScope? = null

    override fun loadSupplementalHeroCandidates() {
        val hostActivity = activity ?: return
        heroRequestScope?.cancel()
        val requestScope = NativeRequestScope().also { heroRequestScope = it }
        thread(name = "ptv-shows-hero-next-up", start = true) {
            api.withRequestScope(requestScope) {
                val episodes = runCatching { api.fetchNextUp(session, NEXT_UP_LIMIT) }
                    .getOrDefault(emptyList())
                if (requestScope.isCancelled) return@withRequestScope

                val resolvedSeries = linkedMapOf<String, MediaItem?>()
                offerReadyCandidates(hostActivity, requestScope, episodes, resolvedSeries)

                ShowsHeroCandidatePolicy.parentSeriesIds(episodes).forEach { seriesId ->
                    if (requestScope.isCancelled) return@withRequestScope
                    val series = runCatching { api.loadItem(session, seriesId) }.getOrNull()
                    if (requestScope.isCancelled) return@withRequestScope
                    resolvedSeries[seriesId] = series
                    offerReadyCandidates(hostActivity, requestScope, episodes, resolvedSeries)
                }
            }
        }
    }

    override fun onDestroyView() {
        cancelSupplementalHeroCandidates()
        super.onDestroyView()
    }

    override fun cancelSupplementalHeroCandidates() {
        heroRequestScope?.cancel()
        heroRequestScope = null
    }

    private fun offerReadyCandidates(
        hostActivity: Activity,
        requestScope: NativeRequestScope,
        episodes: List<MediaItem>,
        resolvedSeries: Map<String, MediaItem?>
    ) {
        val candidates = ShowsHeroCandidatePolicy.readyCandidates(episodes, resolvedSeries)
        if (candidates.isEmpty()) return
        hostActivity.runOnUiThread {
            if (
                destroyed ||
                requestScope.isCancelled ||
                heroRequestScope !== requestScope
            ) {
                return@runOnUiThread
            }
            offerHeroCandidates("shows.hero.next-up", HeroSource.NEXT_UP, candidates)
        }
    }

    private companion object {
        const val NEXT_UP_LIMIT = 8
    }
}
