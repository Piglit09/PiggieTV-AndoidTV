package com.piggie.tv.ui.music

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
import coil.dispose
import com.piggie.tv.R
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.discovery.DiscoveryDataSource
import com.piggie.tv.data.discovery.DiscoveryFilter
import com.piggie.tv.data.discovery.DiscoveryFilterType
import com.piggie.tv.data.discovery.DiscoveryShelfType
import com.piggie.tv.data.discovery.ShelfDefinition
import com.piggie.tv.data.discovery.ShelfStatus
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.MediaShelf
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.music.MusicAffinityAssembler
import com.piggie.tv.data.music.MusicAffinityScorer
import com.piggie.tv.data.playback.MusicPlaybackManager
import com.piggie.tv.data.session.NativeSettings
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import com.piggie.tv.diagnostics.PtvRedactor
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.theme.PTVShapes
import com.piggie.tv.ui.discovery.DiscoveryShelfSlotView
import com.piggie.tv.ui.hero.HeroCandidate
import com.piggie.tv.ui.hero.HeroController
import com.piggie.tv.ui.hero.HeroInitialVisibilitySampler
import com.piggie.tv.ui.hero.HeroRoute
import com.piggie.tv.ui.hero.HeroRefreshableRoute
import com.piggie.tv.ui.hero.HeroRowView
import com.piggie.tv.ui.hero.HeroSource
import com.piggie.tv.ui.hero.HeroState
import com.piggie.tv.ui.hero.HeroVisibilityPolicy
import com.piggie.tv.ui.layout.TvCardSpacingDecoration
import com.piggie.tv.ui.layout.TvHorizontalRecyclerView
import com.piggie.tv.ui.layout.TvLinearLayoutManager
import com.piggie.tv.ui.layout.TvShelfScrollCoordinator
import com.piggie.tv.ui.rendering.applyRenderingTuning
import com.piggie.tv.ui.widgets.MediaCardFactory
import com.piggie.tv.ui.widgets.MediaCardHolder
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.concurrent.thread

class MusicFragment : Fragment(), HeroRefreshableRoute {
    private val api by lazy { JellyfinNativeApi(requireContext()) }
    private val store by lazy { SecureSessionStore(requireContext()) }
    private val settings by lazy { NativeSettings(requireContext()) }
    private lateinit var session: NativeSession
    private lateinit var root: FrameLayout
    private lateinit var page: RecyclerView
    private lateinit var pageAdapter: MusicPageAdapter
    private lateinit var heroController: HeroController
    private lateinit var heroRow: HeroRowView
    private var heroState: HeroState? = null
    private var shelfCoordinator: TvShelfScrollCoordinator? = null
    private var initialHeroVisibilitySampler: HeroInitialVisibilitySampler? = null
    @Volatile private var destroyed = false
    @Volatile private var loadGeneration = 0
    private var artworkFallbacks = emptyMap<String, List<MediaItem>>()
    private var loadExecutor: ExecutorService? = null
    private var loadFuture: Future<*>? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        destroyed = false
        loadExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ptv-music-home")
        }
        session = (activity as? PtvHostActivity)?.session ?: requireNotNull(store.read())
        root = FrameLayout(requireContext()).apply {
            setBackgroundColor(PTVColors.background)
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        heroController = HeroController(
            route = HeroRoute.MUSIC,
            reduceMotion = { settings.reduceMotion },
            onStateChanged = { state ->
                heroState = state
                if (::heroRow.isInitialized) heroRow.render(state)
            }
        )
        heroRow = HeroRowView(
            context = requireContext(),
            route = HeroRoute.MUSIC,
            session = session,
            api = api,
            reduceMotion = { settings.reduceMotion },
            onPrimary = ::playHeroItem,
            onDetails = { MusicDetailsActivity.start(requireContext(), it.id) },
            onControlsFocusChanged = heroController::setControlsFocused,
            onImageResult = heroController::recordImageResult
        )
        heroRow.render(heroState)

        val manager = TvLinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)
        pageAdapter = MusicPageAdapter()
        page = RecyclerView(requireContext()).apply {
            layoutManager = manager
            adapter = pageAdapter
            itemAnimator = null
            setItemViewCacheSize(1)
            clipToPadding = false
            isFocusable = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, 0, requireContext().dim(R.dimen.tv_spacing_large))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    updateHeroVisibility()
                }
            })
        }
        root.addView(page, ViewGroup.LayoutParams(-1, -1))
        shelfCoordinator = TvShelfScrollCoordinator(
            page = page,
            headerOffset = pageAdapter.headerOffset,
            shelfIdAt = pageAdapter::shelfIdAt,
            route = "music"
        ).also(TvShelfScrollCoordinator::attach)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycle.addObserver(heroController)
        loadMusic()
        initialHeroVisibilitySampler = HeroInitialVisibilitySampler(
            page,
            ::updateHeroVisibility
        ).also(HeroInitialVisibilitySampler::attach)
    }

    override fun onDestroyView() {
        destroyed = true
        loadGeneration++
        loadFuture?.cancel(true)
        loadFuture = null
        api.cancelInFlight()
        loadExecutor?.shutdownNow()
        loadExecutor = null
        initialHeroVisibilitySampler?.detach()
        initialHeroVisibilitySampler = null
        shelfCoordinator?.detach()
        shelfCoordinator = null
        heroController.release()
        if (::page.isInitialized) page.adapter = null
        super.onDestroyView()
    }

    override fun refreshHero() {
        heroController.refresh()
    }

    private fun loadMusic() {
        loadFuture?.cancel(true)
        api.cancelInFlight()
        val generation = ++loadGeneration
        artworkFallbacks = emptyMap()
        pageAdapter.reset()

        val executor = loadExecutor ?: return
        loadFuture = executor.submit loadTask@{
            fun active(): Boolean =
                !Thread.currentThread().isInterrupted &&
                    !destroyed &&
                    generation == loadGeneration

            val loadedShelves = linkedMapOf<String, MediaShelf>()
            var primaryFailure: Throwable? = null
            runCatching {
                api.loadMusicHomeIncrementally(session) { shelf ->
                    if (!active()) return@loadMusicHomeIncrementally
                    loadedShelves[normalizeShelfTitle(shelf.title)] = shelf
                    activity?.runOnUiThread {
                        if (isCurrent(generation)) pageAdapter.update(shelf)
                    }
                }
            }.onFailure { primaryFailure = it }
            if (!active()) return@loadTask

            val catalogArtists = runCatching {
                api.loadArtists(session, limit = ARTIST_LIMIT)
            }.getOrDefault(emptyList())
            if (!active()) return@loadTask
            if (catalogArtists.isNotEmpty()) {
                val shelf = MediaShelf(
                    title = ARTISTS_TITLE,
                    items = catalogArtists.take(ARTIST_SHELF_LIMIT),
                    presentation = MediaCardPresentation.SQUARE
                )
                loadedShelves[normalizeShelfTitle(shelf.title)] = shelf
                activity?.runOnUiThread {
                    if (isCurrent(generation)) pageAdapter.update(shelf)
                }
            }

            val recentTracks = runCatching {
                api.loadRecentMusicSignals(session, SIGNAL_LIMIT)
            }.getOrElse {
                loadedShelves[normalizeShelfTitle(RECENTLY_PLAYED_TITLE)]
                    ?.items
                    .orEmpty()
            }
            if (!active()) return@loadTask
            val frequentTracks = runCatching {
                api.loadFrequentMusicSignals(session, SIGNAL_LIMIT)
            }.getOrDefault(emptyList())
            if (!active()) return@loadTask
            val favoriteArtists = runCatching {
                api.loadFavoriteArtists(session, FAVORITE_ARTIST_LIMIT)
            }.getOrDefault(catalogArtists.filter(MediaItem::isFavorite))
            if (!active()) return@loadTask
            val recentAlbums = loadedShelves[
                normalizeShelfTitle(RECENTLY_ADDED_ALBUMS_TITLE)
            ]?.items.orEmpty().ifEmpty {
                runCatching {
                    api.loadAlbums(
                        session = session,
                        limit = ALBUM_RESOLUTION_LIMIT,
                        sortBy = "DateCreated",
                        sortOrder = "Descending"
                    )
                }.getOrDefault(emptyList())
            }
            if (!active()) return@loadTask
            if (
                recentAlbums.isNotEmpty() &&
                normalizeShelfTitle(RECENTLY_ADDED_ALBUMS_TITLE) !in loadedShelves
            ) {
                val shelf = MediaShelf(
                    title = RECENTLY_ADDED_ALBUMS_TITLE,
                    items = recentAlbums.take(ARTIST_SHELF_LIMIT),
                    presentation = MediaCardPresentation.SQUARE
                )
                loadedShelves[normalizeShelfTitle(shelf.title)] = shelf
                activity?.runOnUiThread {
                    if (isCurrent(generation)) pageAdapter.update(shelf)
                }
            }

            val knownIds = (catalogArtists + favoriteArtists + recentAlbums)
                .mapTo(hashSetOf(), MediaItem::id)
            val unresolvedIds = buildList {
                addAll(
                    (recentTracks + frequentTracks)
                        .mapNotNull(MediaItem::artistId)
                        .distinct()
                        .take(ARTIST_RESOLUTION_LIMIT)
                )
                addAll(
                    (recentTracks + frequentTracks)
                        .mapNotNull(MediaItem::albumId)
                        .distinct()
                        .take(ALBUM_RESOLUTION_LIMIT)
                )
            }.filterNot(knownIds::contains)
            val resolvedItems = buildList {
                for (id in unresolvedIds) {
                    if (!active()) return@loadTask
                    runCatching { api.loadItem(session, id) }.getOrNull()?.let(::add)
                }
            }
            if (!active()) return@loadTask
            val affinity = MusicAffinityAssembler.assemble(
                recentTracks = recentTracks,
                frequentTracks = frequentTracks,
                favoriteArtists = favoriteArtists,
                recentAlbums = recentAlbums,
                catalogArtists = catalogArtists,
                resolvedItems = resolvedItems
            )

            activity?.runOnUiThread {
                if (!isCurrent(generation)) return@runOnUiThread
                artworkFallbacks = buildArtworkFallbacks(
                    affinity = affinity,
                    loadedShelves = loadedShelves.values.toList(),
                    resolvedItems = resolvedItems,
                    catalogArtists = catalogArtists,
                    signalTracks = recentTracks + frequentTracks
                )
                pageAdapter.refreshContent()
                val candidates = affinity.entries.take(HERO_POOL_LIMIT).map {
                    HeroCandidate(
                        item = it.item,
                        source = HeroSource.RECENT,
                        artworkItem = it.item,
                        fallbackArtworkItems = it.fallbackArtworkItems,
                        diagnosticSelectionBasis =
                            MusicAffinityScorer.reasonFor(it.affinity)?.diagnosticLabel
                    )
                }
                heroController.submit(
                    source = HeroSource.RECENT,
                    items = candidates,
                    key = "music.affinity"
                )
                if (primaryFailure != null && loadedShelves.isEmpty()) {
                    pageAdapter.failRemaining(primaryFailure?.javaClass?.simpleName)
                } else {
                    pageAdapter.completeRemaining()
                }
                recordAffinity(affinity)
            }
        }
    }

    private fun recordAffinity(result: com.piggie.tv.data.music.MusicHeroAffinityResult) {
        val selection = result.selection
        PtvDiagnosticsManager.event(
            "music",
            "hero_affinity",
            mapOf(
                "recentTracks" to result.recentTrackCount.toString(),
                "frequentTracks" to result.frequentTrackCount.toString(),
                "resolvedArtists" to result.resolvedArtistCount.toString(),
                "resolvedAlbums" to result.resolvedAlbumCount.toString(),
                "poolSize" to result.entries.size.toString(),
                "selectedItem" to
                    (PtvRedactor.identifier(selection?.candidate?.id) ?: "none"),
                "reason" to
                    (selection?.reason?.diagnosticLabel ?: "no_affinity_signal"),
                "selectedKind" to
                    (selection?.candidate?.kind?.name?.lowercase() ?: "none"),
                "selectedPlayCount" to
                    (selection?.candidate?.playCount ?: 0).toString(),
                "selectedRecentArtistPlays" to
                    (selection?.candidate?.recentArtistPlays ?: 0).toString(),
                "selectedRecentAlbumPlays" to
                    (selection?.candidate?.recentAlbumPlays ?: 0).toString(),
                "selectedCompletedTracks" to
                    (selection?.candidate?.completedTracks ?: 0).toString(),
                "selectedFavorite" to
                    (selection?.candidate?.isFavorite ?: false).toString(),
                "selectedHasLastPlayedDate" to
                    (selection?.candidate?.lastPlayedAtMs != null).toString(),
                "artworkFallbackCount" to
                    result.entries.firstOrNull()?.fallbackArtworkItems?.size.toString()
            )
        )
    }

    private fun buildArtworkFallbacks(
        affinity: com.piggie.tv.data.music.MusicHeroAffinityResult,
        loadedShelves: List<MediaShelf>,
        resolvedItems: List<MediaItem>,
        catalogArtists: List<MediaItem>,
        signalTracks: List<MediaItem>
    ): Map<String, List<MediaItem>> {
        val shelfItems = loadedShelves.flatMap(MediaShelf::items)
        val allItems = (shelfItems + signalTracks + resolvedItems + catalogArtists)
            .distinctBy(MediaItem::id)
        val albums = allItems.filter { it.type == "MusicAlbum" }
        val artists = allItems.filter { it.type == "MusicArtist" }
        val tracks = allItems.filter { it.type == "Audio" }
        val albumById = albums.associateBy(MediaItem::id)
        val artistById = artists.associateBy(MediaItem::id)
        val affinityFallbacks = affinity.entries.associate {
            it.item.id to it.fallbackArtworkItems
        }

        return allItems.associate { item ->
            val fallbacks = when (item.type) {
                "MusicArtist" -> affinityFallbacks[item.id]
                    .orEmpty()
                    .ifEmpty {
                        albums.filter { album ->
                            album.albumArtist?.equals(
                                item.title,
                                ignoreCase = true
                            ) == true ||
                                album.artists.any {
                                    it.equals(item.title, ignoreCase = true)
                                }
                        }
                    }
                "MusicAlbum" -> buildList {
                    addAll(
                        tracks.filter { it.albumId == item.id }
                            .filter { !it.imageTag.isNullOrBlank() }
                            .take(2)
                    )
                    addAll(
                        artists.filter { artist ->
                            item.artistId == artist.id ||
                                item.albumArtist?.equals(
                                    artist.title,
                                    ignoreCase = true
                                ) == true ||
                                item.artists.any {
                                    it.equals(artist.title, ignoreCase = true)
                                }
                        }.take(2)
                    )
                }
                "Audio" -> buildList {
                    item.albumId?.let(albumById::get)?.let(::add)
                    item.artistId?.let(artistById::get)?.let(::add)
                }
                else -> affinityFallbacks[item.id].orEmpty()
            }
            item.id to fallbacks.distinctBy(MediaItem::id).take(3)
        }
    }

    private fun updateHeroVisibility() {
        val hero = page.layoutManager?.findViewByPosition(0)
        val percent = if (hero == null) {
            0
        } else {
            HeroVisibilityPolicy.visiblePercent(
                viewportHeight = page.height,
                heroTop = hero.top,
                heroBottom = hero.bottom,
                heroHeight = hero.height
            )
        }
        heroController.updateVisibility(percent, page.computeVerticalScrollOffset())
    }

    private fun playHeroItem(item: MediaItem) {
        if (item.type == "Audio") {
            MusicPlaybackManager.play(requireContext(), session, listOf(item))
            return
        }
        val context = requireContext()
        thread(start = true, name = "ptv-music-hero-play") {
            val tracks = runCatching {
                when (item.type) {
                    "MusicAlbum" -> api.loadAlbumSongs(session, item.id)
                    "MusicArtist" -> api.loadArtistSongs(session, item.id)
                    "Playlist" -> api.loadPlaylistSongs(session, item.id)
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
            activity?.runOnUiThread {
                if (!destroyed && tracks.isNotEmpty()) {
                    MusicPlaybackManager.play(context, session, tracks)
                } else if (!destroyed) {
                    MusicDetailsActivity.start(context, item.id)
                }
            }
        }
    }

    private fun createShelfView(definition: MusicShelfDefinition, shelf: MediaShelf): View {
        val context = requireContext()
        val title = TextView(context).apply {
            text = definition.title
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
        val manager = TvLinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        val recycler = TvHorizontalRecyclerView(context).apply {
            layoutManager = manager
            adapter = MusicCardAdapter(shelf.items, shelf.presentation)
            applyRenderingTuning(manager, visibleItemEstimate(shelf.presentation))
            clipChildren = false
            clipToPadding = false
            setPadding(
                context.dim(R.dimen.tv_screen_margin_horizontal),
                context.dim(R.dimen.tv_spacing_small),
                context.dim(R.dimen.tv_screen_margin_horizontal),
                context.dim(R.dimen.tv_spacing_small)
            )
            addItemDecoration(
                TvCardSpacingDecoration(context.dim(R.dimen.tv_card_spacing))
            )
        }
        val height = when (shelf.presentation) {
            MediaCardPresentation.POSTER ->
                context.dim(R.dimen.tv_poster_height)
            MediaCardPresentation.LANDSCAPE ->
                context.dim(R.dimen.tv_landscape_height)
            MediaCardPresentation.SQUARE ->
                context.dim(R.dimen.tv_square_height)
        } + context.dim(R.dimen.tv_shelf_extra_height)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(recycler, LinearLayout.LayoutParams(-1, height))
        }
    }

    private fun isCurrent(generation: Int): Boolean =
        !destroyed && generation == loadGeneration && isAdded

    private fun normalizeShelfTitle(value: String): String =
        value.trim().lowercase(java.util.Locale.ROOT)

    private fun visibleItemEstimate(presentation: MediaCardPresentation): Int = when (presentation) {
        MediaCardPresentation.POSTER -> 8
        MediaCardPresentation.LANDSCAPE -> 5
        MediaCardPresentation.SQUARE -> 7
    }

    private inner class MusicPageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val states = MUSIC_SHELVES.associate { definition ->
            definition.id to MusicShelfUi(definition)
        }.toMutableMap()
        val headerOffset = 1

        init {
            setHasStableIds(true)
        }

        fun shelfIdAt(adapterPosition: Int): String? =
            MUSIC_SHELVES.getOrNull(adapterPosition - headerOffset)?.id

        fun reset() {
            MUSIC_SHELVES.forEach { states[it.id] = MusicShelfUi(it) }
            notifyDataSetChanged()
        }

        fun update(shelf: MediaShelf) {
            val position = MUSIC_SHELVES.indexOfFirst {
                normalizeShelfTitle(it.title) == normalizeShelfTitle(shelf.title)
            }
            if (position < 0) return
            val definition = MUSIC_SHELVES[position]
            states[definition.id] = MusicShelfUi(
                definition = definition,
                state = MusicShelfLoadState.CONTENT,
                shelf = shelf
            )
            notifyItemChanged(position + headerOffset)
        }

        fun completeRemaining() {
            MUSIC_SHELVES.forEachIndexed { index, definition ->
                if (states[definition.id]?.state == MusicShelfLoadState.LOADING) {
                    states[definition.id] = MusicShelfUi(
                        definition,
                        MusicShelfLoadState.EMPTY,
                        message = "No ${definition.title.lowercase()} are available."
                    )
                    notifyItemChanged(index + headerOffset)
                }
            }
        }

        fun failRemaining(message: String?) {
            MUSIC_SHELVES.forEachIndexed { index, definition ->
                if (states[definition.id]?.state == MusicShelfLoadState.LOADING) {
                    states[definition.id] = MusicShelfUi(
                        definition,
                        MusicShelfLoadState.ERROR,
                        message = message ?: "Music library request failed."
                    )
                    notifyItemChanged(index + headerOffset)
                }
            }
        }

        fun refreshContent() {
            MUSIC_SHELVES.forEachIndexed { index, definition ->
                if (states[definition.id]?.state == MusicShelfLoadState.CONTENT) {
                    notifyItemChanged(index + headerOffset)
                }
            }
        }

        override fun getItemCount(): Int = MUSIC_SHELVES.size + headerOffset

        override fun getItemId(position: Int): Long =
            if (position == 0) Long.MIN_VALUE
            else MUSIC_SHELVES[position - headerOffset].id.hashCode().toLong()

        override fun getItemViewType(position: Int): Int =
            if (position == 0) HERO_VIEW_TYPE else position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == HERO_VIEW_TYPE) {
                HeroHolder(FrameLayout(parent.context))
            } else {
                val definition = MUSIC_SHELVES[viewType - headerOffset]
                ShelfHolder(
                    DiscoveryShelfSlotView(
                        parent.context,
                        definition.asDiscoveryDefinition()
                    )
                )
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is HeroHolder -> {
                    holder.container.removeAllViews()
                    (heroRow.parent as? ViewGroup)?.removeView(heroRow)
                    holder.container.minimumHeight =
                        requireContext().dim(R.dimen.tv_hero_height)
                    holder.container.addView(
                        heroRow,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            requireContext().dim(R.dimen.tv_hero_height)
                        )
                    )
                }
                is ShelfHolder -> {
                    val definition = MUSIC_SHELVES[position - headerOffset]
                    val state = requireNotNull(states[definition.id])
                    when (state.state) {
                        MusicShelfLoadState.LOADING ->
                            holder.slot.showLoading(definition.title)
                        MusicShelfLoadState.CONTENT ->
                            holder.slot.showContent(
                                createShelfView(
                                    definition,
                                    requireNotNull(state.shelf)
                                )
                            )
                        MusicShelfLoadState.EMPTY ->
                            holder.slot.showEmpty(
                                definition.title,
                                state.message
                            ) { loadMusic() }
                        MusicShelfLoadState.ERROR ->
                            holder.slot.showFailure(
                                definition.title,
                                ShelfStatus.HTTP_ERROR,
                                state.message
                            ) { loadMusic() }
                    }
                }
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            when (holder) {
                is HeroHolder -> holder.container.removeAllViews()
                is ShelfHolder -> holder.slot.release()
            }
            super.onViewRecycled(holder)
        }
    }

    private inner class MusicCardAdapter(
        private val items: List<MediaItem>,
        private val presentation: MediaCardPresentation
    ) : RecyclerView.Adapter<MediaCardHolder>() {
        init {
            setHasStableIds(true)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaCardHolder =
            MediaCardHolder(MediaCardFactory.createView(parent, presentation))

        override fun onBindViewHolder(holder: MediaCardHolder, position: Int) {
            val item = items[position]
            MediaCardFactory.bindView(
                holder,
                item,
                presentation,
                session,
                api,
                artworkFallbacks[item.id].orEmpty()
            )
            holder.itemView.setOnClickListener {
                if (item.type == "Audio") {
                    MusicPlaybackManager.play(it.context, session, items, position)
                } else {
                    MusicDetailsActivity.start(it.context, item.id)
                }
            }
            holder.itemView.setOnFocusChangeListener { view, focused ->
                PTVShapes.applyFocusEffect(view, focused)
                heroController.onItemFocused(
                    if (focused) HeroCandidate(
                        item = item,
                        source = HeroSource.RECENT,
                        fallbackArtworkItems = artworkFallbacks[item.id].orEmpty()
                    ) else null
                )
            }
        }

        override fun getItemCount(): Int = items.size

        override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

        override fun onViewRecycled(holder: MediaCardHolder) {
            holder.image.dispose()
            holder.itemView.setOnClickListener(null)
            holder.itemView.onFocusChangeListener = null
            super.onViewRecycled(holder)
        }
    }

    private class HeroHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)
    private class ShelfHolder(val slot: DiscoveryShelfSlotView) : RecyclerView.ViewHolder(slot)

    private data class MusicShelfDefinition(
        val id: String,
        val title: String,
        val presentation: MediaCardPresentation
    ) {
        fun asDiscoveryDefinition() = ShelfDefinition(
            id = id,
            type = DiscoveryShelfType.LIBRARY_SPECIFIC,
            title = title,
            presentation = presentation,
            itemTypes = listOf("MusicArtist", "MusicAlbum", "Audio"),
            dataSource = DiscoveryDataSource.USER_ITEMS,
            filter = DiscoveryFilter(DiscoveryFilterType.LIBRARY)
        )
    }

    private enum class MusicShelfLoadState {
        LOADING,
        CONTENT,
        EMPTY,
        ERROR
    }

    private data class MusicShelfUi(
        val definition: MusicShelfDefinition,
        val state: MusicShelfLoadState = MusicShelfLoadState.LOADING,
        val shelf: MediaShelf? = null,
        val message: String? = null
    )

    private companion object {
        const val HERO_VIEW_TYPE = Int.MIN_VALUE
        const val HERO_POOL_LIMIT = 12
        const val SIGNAL_LIMIT = 48
        const val ARTIST_LIMIT = 64
        const val ARTIST_SHELF_LIMIT = 12
        const val FAVORITE_ARTIST_LIMIT = 24
        const val ARTIST_RESOLUTION_LIMIT = 5
        const val ALBUM_RESOLUTION_LIMIT = 12
        const val RECENTLY_PLAYED_TITLE = "Recently played"
        const val RECENTLY_ADDED_ALBUMS_TITLE = "Recently added albums"
        const val ARTISTS_TITLE = "Artists"

        val MUSIC_SHELVES = listOf(
            MusicShelfDefinition(
                "music.recent",
                RECENTLY_PLAYED_TITLE,
                MediaCardPresentation.SQUARE
            ),
            MusicShelfDefinition(
                "music.albums.recent",
                RECENTLY_ADDED_ALBUMS_TITLE,
                MediaCardPresentation.SQUARE
            ),
            MusicShelfDefinition(
                "music.songs.recent",
                "Recently added songs",
                MediaCardPresentation.LANDSCAPE
            ),
            MusicShelfDefinition(
                "music.albums.favorite",
                "Favorite albums",
                MediaCardPresentation.SQUARE
            ),
            MusicShelfDefinition(
                "music.artists",
                ARTISTS_TITLE,
                MediaCardPresentation.SQUARE
            )
        )
    }
}
