package com.piggie.tv.ui.discovery

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.piggie.tv.R
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.discovery.DiscoveryManager
import com.piggie.tv.data.discovery.DiscoveryPage
import com.piggie.tv.data.discovery.DiscoveryPageRequest
import com.piggie.tv.data.discovery.DiscoveryShelf
import com.piggie.tv.data.discovery.DiscoveryShelfType
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.NativeSettings
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.memory.MemoryPressureParticipant
import com.piggie.tv.memory.MemoryPressurePolicy
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.theme.PTVShapes
import com.piggie.tv.ui.hero.HeroCandidate
import com.piggie.tv.ui.hero.HeroController
import com.piggie.tv.ui.hero.HeroInitialVisibilitySampler
import com.piggie.tv.ui.hero.HeroRoute
import com.piggie.tv.ui.hero.HeroRefreshableRoute
import com.piggie.tv.ui.hero.HeroRowView
import com.piggie.tv.ui.hero.HeroRowLayoutContract
import com.piggie.tv.ui.hero.HeroSource
import com.piggie.tv.ui.hero.HeroState
import com.piggie.tv.ui.hero.HeroVisibilityPolicy
import com.piggie.tv.ui.layout.TvCardSpacingDecoration
import com.piggie.tv.ui.layout.TvHorizontalRecyclerView
import com.piggie.tv.ui.layout.TvLinearLayoutManager
import com.piggie.tv.ui.layout.TvShelfScrollCoordinator
import com.piggie.tv.ui.player.MediaDetailsActivity
import com.piggie.tv.ui.player.PlaybackLaunchOrigin
import com.piggie.tv.ui.player.VideoPlayerActivity
import com.piggie.tv.ui.rendering.applyRenderingTuning
import com.piggie.tv.ui.widgets.MediaCardFactory
import com.piggie.tv.ui.widgets.MediaCardHolder
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes
import kotlin.concurrent.thread

abstract class BaseDiscoveryFragment : Fragment(), HeroRefreshableRoute, MemoryPressureParticipant {
    protected abstract val discoveryPage: DiscoveryPage
    protected abstract val heroRoute: HeroRoute

    protected val api by lazy { JellyfinNativeApi(requireContext()) }
    private val store by lazy { SecureSessionStore(requireContext()) }
    private val settings by lazy { NativeSettings(requireContext()) }
    protected lateinit var session: NativeSession
    protected var destroyed = false

    private lateinit var root: FrameLayout
    private lateinit var page: RecyclerView
    private lateinit var pageAdapter: DiscoveryPageAdapter
    private lateinit var heroController: HeroController
    private lateinit var heroRow: HeroRowView
    private var heroState: HeroState? = null
    private var discoveryRequest: DiscoveryPageRequest? = null
    private var shelfCoordinator: TvShelfScrollCoordinator? = null
    private var initialHeroVisibilitySampler: HeroInitialVisibilitySampler? = null
    private var pageLoadStartedAt = 0L
    private var routeVisible = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        destroyed = false
        session = (activity as? PtvHostActivity)?.session ?: requireNotNull(store.read())
        root = FrameLayout(requireContext()).apply {
            setBackgroundColor(PTVColors.background)
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        heroController = HeroController(
            route = heroRoute,
            reduceMotion = { settings.reduceMotion },
            onStateChanged = { state ->
                heroState = state
                if (::heroRow.isInitialized) heroRow.render(state)
            }
        )
        heroRow = HeroRowView(
            context = requireContext(),
            route = heroRoute,
            session = session,
            api = api,
            reduceMotion = { settings.reduceMotion },
            onPrimary = ::playHeroItem,
            onDetails = { MediaDetailsActivity.start(requireContext(), it) },
            onControlsFocusChanged = heroController::setControlsFocused,
            onImageResult = heroController::recordImageResult
        )
        heroRow.render(heroState)

        val manager = TvLinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)
        page = RecyclerView(requireContext()).apply {
            layoutManager = manager
            itemAnimator = null
            setItemViewCacheSize(1)
            clipToPadding = false
            isFocusable = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(
                requireContext().dim(R.dimen.tv_shelf_margin_horizontal),
                0,
                requireContext().dim(R.dimen.tv_shelf_margin_horizontal),
                requireContext().dim(R.dimen.tv_spacing_large)
            )
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    updateHeroVisibility()
                }
            })
        }
        root.addView(page, ViewGroup.LayoutParams(-1, -1))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // A restored hidden Fragment can create its view while capped at STARTED. Build the
        // retained slots now, but do not start network work until the route truly resumes.
        routeVisible = false
        viewLifecycleOwner.lifecycle.addObserver(heroController)
        loadData()
        initialHeroVisibilitySampler = HeroInitialVisibilitySampler(
            page,
            ::updateHeroVisibility
        ).also(HeroInitialVisibilitySampler::attach)
    }

    override fun onDestroyView() {
        destroyed = true
        routeVisible = false
        discoveryRequest?.cancel()
        discoveryRequest = null
        initialHeroVisibilitySampler?.detach()
        initialHeroVisibilitySampler = null
        shelfCoordinator?.detach()
        shelfCoordinator = null
        heroController.release()
        heroState = null
        if (::page.isInitialized) page.adapter = null
        super.onDestroyView()
    }

    override fun onPause() {
        routeVisible = false
        discoveryRequest?.cancel()
        discoveryRequest = null
        cancelSupplementalHeroCandidates()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        routeVisible = true
        if (::pageAdapter.isInitialized && discoveryRequest == null && !destroyed) {
            startDiscoveryRequest()
            loadSupplementalHeroCandidates()
        }
    }

    override fun refreshHero() {
        heroController.refresh()
    }

    override fun onMemoryPressure(level: Int) {
        val actions = MemoryPressurePolicy.actions(level)
        if (actions.discoveryCachePercent > 0) return
        if (!actions.releaseCurrentScreenContent) {
            // Supplemental hero fetches are optional, but the visible shelves and current hero
            // must remain usable because RUNNING_CRITICAL does not trigger a later onResume().
            cancelSupplementalHeroCandidates()
            return
        }
        discoveryRequest?.cancel()
        discoveryRequest = null
        cancelSupplementalHeroCandidates()
        if (::heroController.isInitialized) heroController.release()
        heroState = null
        if (::heroRow.isInitialized) heroRow.releaseForMemoryPressure()
    }

    protected open fun loadSupplementalHeroCandidates() = Unit

    protected open fun cancelSupplementalHeroCandidates() = Unit

    protected fun offerHeroCandidates(
        key: String,
        source: HeroSource,
        candidates: List<HeroCandidate>
    ) {
        if (!destroyed) heroController.submit(source, candidates, key)
    }

    private fun loadData() {
        pageLoadStartedAt = android.os.SystemClock.elapsedRealtime()
        val manifest = DiscoveryManager.manifest(discoveryPage)
        pageAdapter = DiscoveryPageAdapter(
            definitions = manifest.shelves,
            createShelfContent = ::createShelfView,
            onRetry = { shelfId -> discoveryRequest?.retryShelf(shelfId) },
            bindHeader = { container ->
                val heroHeight = requireContext().dim(R.dimen.tv_hero_height)
                HeroRowLayoutContract.bind(container, heroRow, heroHeight)
                container.post { heroRow.traceMeasurement(container) }
            }
        )
        page.adapter = pageAdapter
        shelfCoordinator = TvShelfScrollCoordinator(
            page,
            pageAdapter,
            discoveryPage.name.lowercase()
        ).also { it.attach() }
    }

    private fun startDiscoveryRequest() {
        val manifest = DiscoveryManager.manifest(discoveryPage)
        discoveryRequest = DiscoveryManager.loadPage(
            api = api,
            nativeSession = session,
            manifest = manifest,
            discoverySession = DiscoveryManager.getSession(api, session)
        ) { shelf ->
            activity?.runOnUiThread {
                if (destroyed || !routeVisible) return@runOnUiThread
                if (shelf.diagnostic.generationId != DiscoveryManager.currentGenerationId(discoveryPage)) {
                    return@runOnUiThread
                }
                val adapterStarted = android.os.SystemClock.elapsedRealtime()
                pageAdapter.updateShelf(shelf)
                DiscoveryManager.recordAdapterState(
                    shelf,
                    android.os.SystemClock.elapsedRealtime() - adapterStarted
                )
                val source = heroSource(shelf)
                heroController.submit(
                    source,
                    shelf.items.map { HeroCandidate(it, source) },
                    shelf.definition.id
                )
            }
        }
    }

    private fun createShelfView(shelf: DiscoveryShelf): View {
        val context = requireContext()
        val title = TextView(context).apply {
            text = shelf.definition.title
            setTextSizeRes(R.dimen.tv_text_size_section_title)
            setTextColor(PTVColors.textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(
                context.dim(R.dimen.tv_screen_margin_horizontal),
                context.dim(R.dimen.tv_spacing_medium),
                0,
                0
            )
        }
        val items = shelf.items + listOfNotNull(shelf.viewMoreItem)
        val manager = TvLinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        val recycler = TvHorizontalRecyclerView(context).apply {
            layoutManager = manager
            adapter = MediaCardAdapter(
                items,
                shelf.definition.presentation,
                shelf.browseRequest,
                shelf,
                heroSource(shelf)
            )
            applyRenderingTuning(manager, visibleItemEstimate(shelf.definition.presentation))
            clipChildren = false
            clipToPadding = false
            setPadding(
                context.dim(R.dimen.tv_screen_margin_horizontal),
                context.dim(R.dimen.tv_shelf_card_inset),
                context.dim(R.dimen.tv_screen_margin_horizontal),
                context.dim(R.dimen.tv_shelf_card_inset)
            )
            addItemDecoration(
                TvCardSpacingDecoration(context.dim(R.dimen.tv_card_spacing))
            )
        }
        val height = when (shelf.definition.presentation) {
            MediaCardPresentation.POSTER ->
                context.dim(R.dimen.tv_poster_height) + context.dim(R.dimen.tv_shelf_extra_height)
            MediaCardPresentation.LANDSCAPE ->
                context.dim(R.dimen.tv_landscape_height) + context.dim(R.dimen.tv_shelf_extra_height)
            MediaCardPresentation.SQUARE ->
                context.dim(R.dimen.tv_square_height) + context.dim(R.dimen.tv_shelf_extra_height)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                0,
                context.dim(R.dimen.tv_shelf_glass_padding_vertical),
                0,
                context.dim(R.dimen.tv_shelf_glass_padding_vertical)
            )
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(recycler, LinearLayout.LayoutParams(-1, height))
            recycler.post { DiscoveryManager.recordRendered(shelf, recycler.childCount) }
        }
    }

    private fun heroSource(shelf: DiscoveryShelf): HeroSource = when (shelf.definition.type) {
        DiscoveryShelfType.CONTINUE_WATCHING -> HeroSource.CONTINUE
        DiscoveryShelfType.NEXT_UP -> HeroSource.NEXT_UP
        DiscoveryShelfType.RECOMMENDED,
        DiscoveryShelfType.YOU_MAY_ALSO_LIKE -> HeroSource.RECOMMENDED
        DiscoveryShelfType.POPULAR,
        DiscoveryShelfType.TRENDING -> HeroSource.POPULAR
        DiscoveryShelfType.RECENTLY_ADDED,
        DiscoveryShelfType.LATEST_RELEASES,
        DiscoveryShelfType.LATEST_EPISODES -> HeroSource.RECENT
        else -> HeroSource.LIBRARY
    }

    private fun updateHeroVisibility() {
        if (!::pageAdapter.isInitialized) return
        val hero = page.layoutManager?.findViewByPosition(0)
        val height = hero?.height
            ?: requireContext().dim(R.dimen.tv_hero_height)
        val percent = if (hero == null) {
            0
        } else {
            HeroVisibilityPolicy.visiblePercent(
                viewportHeight = page.height,
                heroTop = hero.top,
                heroBottom = hero.bottom,
                heroHeight = height
            )
        }
        heroController.updateVisibility(percent, page.computeVerticalScrollOffset())
    }

    private fun playHeroItem(item: MediaItem) {
        val origin = if (
            heroState?.current?.item?.id == item.id &&
            heroState?.current?.source == HeroSource.CONTINUE
        ) {
            PlaybackLaunchOrigin.CONTINUE_WATCHING
        } else {
            PlaybackLaunchOrigin.DEFAULT
        }
        if (item.type != "Series") {
            VideoPlayerActivity.start(
                requireContext(),
                item.id,
                item.playbackPositionTicks,
                origin = origin
            )
            return
        }
        thread(name = "ptv-hero-play-series", start = true) {
            val episode = runCatching { api.loadNextUpForSeries(session, item.id) }.getOrNull()
            activity?.runOnUiThread {
                if (destroyed) return@runOnUiThread
                if (episode != null) {
                    VideoPlayerActivity.start(
                        requireContext(),
                        episode.id,
                        episode.playbackPositionTicks,
                        origin = origin
                    )
                } else {
                    MediaDetailsActivity.start(requireContext(), item)
                }
            }
        }
    }

    private fun visibleItemEstimate(presentation: MediaCardPresentation): Int = when (presentation) {
        MediaCardPresentation.POSTER -> 8
        MediaCardPresentation.LANDSCAPE -> 5
        MediaCardPresentation.SQUARE -> 7
    }

    private inner class MediaCardAdapter(
        private val items: List<MediaItem>,
        private val presentation: MediaCardPresentation,
        private val browseRequest: com.piggie.tv.data.discovery.DiscoveryBrowseRequest?,
        private val shelf: DiscoveryShelf,
        private val heroSource: HeroSource
    ) : RecyclerView.Adapter<MediaCardHolder>() {
        private val firstPosterRecorded = java.util.concurrent.atomic.AtomicBoolean()

        init {
            setHasStableIds(true)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaCardHolder =
            MediaCardHolder(MediaCardFactory.createView(parent, presentation))

        override fun onBindViewHolder(holder: MediaCardHolder, position: Int) {
            val item = items[position]
            val firstPosterCallback = if (position == 0) {
                {
                    if (firstPosterRecorded.compareAndSet(false, true)) {
                        DiscoveryManager.recordFirstPoster(
                            shelf,
                            android.os.SystemClock.elapsedRealtime() - pageLoadStartedAt
                        )
                    }
                }
            } else {
                null
            }
            MediaCardFactory.bindView(
                holder,
                item,
                presentation,
                session,
                api,
                onImageReady = firstPosterCallback
            )
            holder.itemView.setOnClickListener {
                when (
                    DiscoveryMediaActionPolicy.resolve(
                        itemType = item.type,
                        shelfType = shelf.definition.type
                    )
                ) {
                    DiscoveryMediaAction.VIEW_MORE -> {
                        browseRequest?.let { request ->
                            com.piggie.tv.ui.library.LibraryBrowserActivity.start(it.context, request)
                        }
                    }
                    DiscoveryMediaAction.RESUME_PLAYBACK -> {
                        VideoPlayerActivity.start(
                            it.context,
                            item.id,
                            item.playbackPositionTicks,
                            origin = PlaybackLaunchOrigin.CONTINUE_WATCHING
                        )
                    }
                    DiscoveryMediaAction.DETAILS -> MediaDetailsActivity.start(it.context, item)
                }
            }
            holder.itemView.setOnFocusChangeListener { view, focused ->
                PTVShapes.applyFocusEffect(view, focused)
                heroController.onItemFocused(
                    if (focused && item.type != "ViewMore") {
                        HeroCandidate(item, heroSource)
                    } else {
                        null
                    }
                )
            }
        }

        override fun getItemCount(): Int = items.size

        override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

        override fun onViewRecycled(holder: MediaCardHolder) {
            MediaCardFactory.recycleView(holder)
            holder.itemView.setOnClickListener(null)
            holder.itemView.onFocusChangeListener = null
            super.onViewRecycled(holder)
        }
    }
}
