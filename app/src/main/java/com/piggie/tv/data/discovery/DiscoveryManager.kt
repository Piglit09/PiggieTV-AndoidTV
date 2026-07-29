package com.piggie.tv.data.discovery

import android.util.Log
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import java.util.*
import java.util.concurrent.TimeUnit

object DiscoveryManager {
    private const val TAG = "DiscoveryManager"
    private var session: DiscoverySession? = null
    private val SESSION_EXPIRY = TimeUnit.HOURS.toMillis(24)
    private const val MIN_ITEMS_PER_SHELF = 5

    fun getSession(api: JellyfinNativeApi, nativeSession: NativeSession): DiscoverySession {
        val current = session
        if (current != null && (System.currentTimeMillis() - current.timestamp) < SESSION_EXPIRY) {
            return current
        }
        
        Log.i(TAG, "Generating new Discovery Session")
        return DiscoverySession(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis()
        ).also { session = it }
    }

    fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            session = null
        }
    }

    fun refresh(api: JellyfinNativeApi, nativeSession: NativeSession): DiscoverySession {
        session = null
        return getSession(api, nativeSession)
    }

    fun manifest(page: DiscoveryPage): PageManifest {
        val definition = getPageDefinition(page.name)
        return PageManifest(page, definition.shelves.size, definition.shelves)
    }

    fun loadPage(
        api: JellyfinNativeApi,
        nativeSession: NativeSession,
        manifest: PageManifest,
        discoverySession: DiscoverySession,
        onShelf: (DiscoveryShelf) -> Unit
    ): DiscoveryPageRequest {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        
        manifest.shelves.forEach { definition ->
            if (cancelled.get()) return@forEach
            loadShelf(api, nativeSession, definition, discoverySession, 
                onSuccess = { shelf -> if (!cancelled.get()) onShelf(shelf) },
                onError = { /* Log error */ }
            )
        }

        return object : DiscoveryPageRequest {
            override fun cancel() { cancelled.set(true) }
            override fun retryShelf(shelfId: String) {
                manifest.shelves.find { it.id == shelfId }?.let { definition ->
                    loadShelf(api, nativeSession, definition, discoverySession,
                        onSuccess = { shelf -> if (!cancelled.get()) onShelf(shelf) },
                        onError = { /* Log error */ }
                    )
                }
            }
        }
    }

    fun recordRendered(shelf: DiscoveryShelf, childCount: Int) {
        // Diagnostic tracking
    }

    fun recordFirstPoster(shelfId: String, elapsedMs: Long) {
        // Diagnostic tracking
    }

    fun recentDiagnostics(): List<ShelfDiagnostic> = emptyList()

    fun loadBrowser(
        api: JellyfinNativeApi,
        nativeSession: NativeSession,
        request: DiscoveryBrowseRequest,
        onResult: (DiscoveryBrowserResult) -> Unit
    ): DiscoveryPageRequest {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        
        kotlin.concurrent.thread {
            try {
                val params = mutableMapOf<String, String>()
                params["IncludeItemTypes"] = request.itemTypes.joinToString(",")
                params["Recursive"] = "true"
                params["Fields"] = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,OfficialRating,CommunityRating,Genres,RunTimeTicks"
                
                when (request.filter.type) {
                    DiscoveryFilterType.GENRE -> params["Genres"] = request.filter.value ?: ""
                    DiscoveryFilterType.STUDIO -> params["Studios"] = request.filter.value ?: ""
                    DiscoveryFilterType.LIBRARY -> params["ParentId"] = request.libraryId ?: ""
                    else -> {}
                }
                
                val items = api.fetchItems(nativeSession, params)
                if (!cancelled.get()) {
                    onResult(DiscoveryBrowserResult(status = ShelfStatus.READY, items = items))
                }
            } catch (e: Exception) {
                if (!cancelled.get()) {
                    onResult(DiscoveryBrowserResult(status = ShelfStatus.HTTP_ERROR, message = e.message))
                }
            }
        }
        
        return object : DiscoveryPageRequest {
            override fun cancel() { cancelled.set(true) }
            override fun retryShelf(shelfId: String) {}
        }
    }

    fun loadShelf(
        api: JellyfinNativeApi,
        nativeSession: NativeSession,
        definition: ShelfDefinition,
        discoverySession: DiscoverySession,
        onSuccess: (DiscoveryShelf) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val startTime = System.currentTimeMillis()
        kotlin.concurrent.thread {
            try {
                val params = mutableMapOf<String, String>()
                params["IncludeItemTypes"] = definition.itemTypes.joinToString(",")
                params["Recursive"] = "true"
                params["Fields"] = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,OfficialRating,CommunityRating,Genres,RunTimeTicks,CriticRating,People"
                params["Limit"] = definition.limit.toString()

                val items = when (definition.type) {
                    DiscoveryShelfType.RECOMMENDED, DiscoveryShelfType.YOU_MAY_ALSO_LIKE -> {
                        val type = definition.itemTypes.firstOrNull()
                        val suggested = runCatching { api.fetchRecommendations(nativeSession, type) }.getOrDefault(emptyList())
                        if (suggested.size >= 5) {
                            suggested.shuffled().take(16)
                        } else {
                            val p = params.toMutableMap()
                            p["SortBy"] = "CommunityRating,Random"
                            p["SortOrder"] = "Descending"
                            p["Limit"] = "24"
                            api.fetchItems(nativeSession, p).shuffled().take(16)
                        }
                    }
                    else -> {
                        when (definition.type) {
                            DiscoveryShelfType.CONTINUE_WATCHING -> {
                                params["Filters"] = "IsResumable"
                                params["SortBy"] = "DatePlayed"
                                params["SortOrder"] = "Descending"
                            }
                            DiscoveryShelfType.RECENTLY_ADDED -> {
                                params["SortBy"] = "DateCreated"
                                params["SortOrder"] = "Descending"
                            }
                            DiscoveryShelfType.LATEST_RELEASES -> {
                                params["SortBy"] = "PremiereDate"
                                params["SortOrder"] = "Descending"
                            }
                            DiscoveryShelfType.POPULAR, DiscoveryShelfType.TRENDING -> {
                                params["SortBy"] = "CommunityRating,ProductionYear,Random"
                                params["SortOrder"] = "Descending"
                            }
                            DiscoveryShelfType.RANDOM_GENRE -> {
                                val genres = runCatching { api.fetchGenres(nativeSession, definition.itemTypes) }.getOrDefault(emptyList())
                                val g = genres.randomOrNull() ?: ""
                                if (g.isEmpty()) {
                                    onSuccess(DiscoveryShelf(definition, emptyList(), ShelfStatus.READY, createDiagnostic(definition, 0, 0)))
                                    return@thread
                                }
                                params["Genres"] = g
                                params["SortBy"] = "Random"
                            }
                            DiscoveryShelfType.RANDOM_STUDIO -> {
                                val studios = runCatching { api.fetchStudios(nativeSession, definition.itemTypes) }.getOrDefault(emptyList())
                                val s = studios.randomOrNull() ?: ""
                                if (s.isEmpty()) {
                                    onSuccess(DiscoveryShelf(definition, emptyList(), ShelfStatus.READY, createDiagnostic(definition, 0, 0)))
                                    return@thread
                                }
                                params["Studios"] = s
                                params["SortBy"] = "Random"
                            }
                            DiscoveryShelfType.LIBRARY_SPECIFIC -> {
                                val libId = runCatching { api.findLibraryId(nativeSession, definition.libraryName ?: "") }.getOrNull()
                                params["ParentId"] = libId ?: "none"
                                params["SortBy"] = "DateCreated"
                                params["SortOrder"] = "Descending"
                            }
                            DiscoveryShelfType.LATEST_EPISODES -> {
                                params["IncludeItemTypes"] = "Episode"
                                params["SortBy"] = "DateCreated"
                                params["SortOrder"] = "Descending"
                            }
                            else -> {}
                        }
                        runCatching { api.fetchItems(nativeSession, params) }.getOrDefault(emptyList())
                    }
                }

                val status = if (items.size >= MIN_ITEMS_PER_SHELF || definition.type == DiscoveryShelfType.RECOMMENDED) {
                    ShelfStatus.READY
                } else {
                    ShelfStatus.NO_ITEMS
                }

                val finalItems = when (definition.type) {
                    DiscoveryShelfType.TRENDING, DiscoveryShelfType.POPULAR -> scoreItems(items)
                    else -> items
                }

                val viewMore = if (definition.type == DiscoveryShelfType.LIBRARY_SPECIFIC || 
                                 definition.type == DiscoveryShelfType.RANDOM_GENRE ||
                                 definition.type == DiscoveryShelfType.RANDOM_STUDIO) {
                    MediaItem(
                        id = "view-more-${definition.id}",
                        title = definition.title,
                        type = "ViewMore",
                        year = null,
                        imageTag = null,
                        playbackPositionTicks = 0,
                        runtimeTicks = 0,
                        seriesName = null,
                        episodeLabel = null
                    )
                } else null

                val duration = System.currentTimeMillis() - startTime
                Log.d(TAG, "Generated shelf: ${definition.title} in ${duration}ms count=${finalItems.size}")
                
                val diagnostic = createDiagnostic(definition, items.size, duration)
                onSuccess(DiscoveryShelf(definition, finalItems, status, diagnostic, viewMore))
            } catch (e: Exception) {
                Log.e(TAG, "Error loading shelf: ${definition.title}", e)
                onSuccess(DiscoveryShelf(definition, emptyList(), ShelfStatus.HTTP_ERROR, createDiagnostic(definition, 0, 0)))
            }
        }
    }

    private fun createDiagnostic(definition: ShelfDefinition, count: Int, duration: Long) = ShelfDiagnostic(
        shelfId = definition.id,
        shelfTitle = definition.title,
        page = DiscoveryPage.HOME,
        status = ShelfStatus.READY,
        query = null,
        resultCount = count,
        deduplicatedCount = count,
        renderCount = count,
        elapsedMs = duration,
        httpMs = null,
        httpStatus = 200,
        cacheHit = false,
        retryCount = 0
    )

    private fun scoreItems(items: List<MediaItem>): List<MediaItem> {
        val random = java.util.Random()
        return items.sortedByDescending { item ->
            val community = (item.communityRating ?: 0f) * 5.0f // 50%
            val yearBonus = item.productionYear?.let { 
                if (it >= 2024) 30 else if (it >= 2023) 15 else 0 
            } ?: 0 // ~30%
            val activityBonus = if (item.playbackPositionTicks > 0) 10 else 0 // 10%
            val jitter = random.nextFloat() * 2.0f // Small jitter
            community + yearBonus + activityBonus + jitter
        }
    }

    fun getPageDefinition(route: String): PageDefinition {
        val pageShelves = when (route) {
            "HOME" -> listOf(
                ShelfDefinition("home-continue", DiscoveryShelfType.CONTINUE_WATCHING, "Continue Watching", MediaCardPresentation.LANDSCAPE, listOf("Movie", "Episode"), filter = DiscoveryFilter(DiscoveryFilterType.CONTINUE_WATCHING)),
                ShelfDefinition("home-nextup", DiscoveryShelfType.NEXT_UP, "Next Up", MediaCardPresentation.LANDSCAPE, listOf("Episode"), filter = DiscoveryFilter(DiscoveryFilterType.NEXT_UP)),
                ShelfDefinition("home-collections", DiscoveryShelfType.LIBRARY_SPECIFIC, "Collections", MediaCardPresentation.POSTER, listOf("BoxSet"), filter = DiscoveryFilter(DiscoveryFilterType.COLLECTION)),
                ShelfDefinition("home-added", DiscoveryShelfType.RECENTLY_ADDED, "Recently Added", MediaCardPresentation.POSTER, listOf("Movie", "Series"), filter = DiscoveryFilter(DiscoveryFilterType.RECENTLY_ADDED)),
                ShelfDefinition("home-recommended", DiscoveryShelfType.RECOMMENDED, "Recommended For You", MediaCardPresentation.POSTER, listOf("Movie", "Series"), filter = DiscoveryFilter(DiscoveryFilterType.RECOMMENDED)),
                ShelfDefinition("home-trending", DiscoveryShelfType.TRENDING, "Trending Now", MediaCardPresentation.POSTER, listOf("Movie", "Series"), filter = DiscoveryFilter(DiscoveryFilterType.POPULAR)),
                ShelfDefinition("home-latest", DiscoveryShelfType.LATEST_RELEASES, "Latest Releases", MediaCardPresentation.POSTER, listOf("Movie"), filter = DiscoveryFilter(DiscoveryFilterType.LATEST_RELEASES)),
                ShelfDefinition("home-genre-action", DiscoveryShelfType.RANDOM_GENRE, "Genre Discovery", MediaCardPresentation.POSTER, listOf("Movie", "Series"), filter = DiscoveryFilter(DiscoveryFilterType.RANDOM_GENRE, "Action"))
            )
            "MOVIES" -> listOf(
                ShelfDefinition("movies-added", DiscoveryShelfType.RECENTLY_ADDED, "Recently Added", MediaCardPresentation.POSTER, listOf("Movie"), filter = DiscoveryFilter(DiscoveryFilterType.RECENTLY_ADDED)),
                ShelfDefinition("movies-continue", DiscoveryShelfType.CONTINUE_WATCHING, "Continue Watching", MediaCardPresentation.LANDSCAPE, listOf("Movie"), filter = DiscoveryFilter(DiscoveryFilterType.CONTINUE_WATCHING)),
                ShelfDefinition("movies-like", DiscoveryShelfType.YOU_MAY_ALSO_LIKE, "You May Also Like", MediaCardPresentation.POSTER, listOf("Movie"), filter = DiscoveryFilter(DiscoveryFilterType.RECOMMENDED)),
                ShelfDefinition("movies-popular", DiscoveryShelfType.POPULAR, "Popular Movies", MediaCardPresentation.POSTER, listOf("Movie"), filter = DiscoveryFilter(DiscoveryFilterType.POPULAR)),
                ShelfDefinition("movies-genre1", DiscoveryShelfType.RANDOM_GENRE, "Genres", MediaCardPresentation.POSTER, listOf("Movie"), filter = DiscoveryFilter(DiscoveryFilterType.RANDOM_GENRE, "Action")),
                ShelfDefinition("movies-studio1", DiscoveryShelfType.RANDOM_STUDIO, "Studios", MediaCardPresentation.POSTER, listOf("Movie"), filter = DiscoveryFilter(DiscoveryFilterType.RANDOM_STUDIO, "Marvel"))
            )
            "SHOWS" -> listOf(
                ShelfDefinition("shows-added", DiscoveryShelfType.RECENTLY_ADDED, "Recently Added", MediaCardPresentation.POSTER, listOf("Series"), filter = DiscoveryFilter(DiscoveryFilterType.RECENTLY_ADDED)),
                ShelfDefinition("shows-continue", DiscoveryShelfType.CONTINUE_WATCHING, "Continue Watching", MediaCardPresentation.LANDSCAPE, listOf("Series"), filter = DiscoveryFilter(DiscoveryFilterType.CONTINUE_WATCHING)),
                ShelfDefinition("shows-popular", DiscoveryShelfType.POPULAR, "Popular Shows", MediaCardPresentation.POSTER, listOf("Series"), filter = DiscoveryFilter(DiscoveryFilterType.POPULAR)),
                ShelfDefinition("shows-anime", DiscoveryShelfType.LIBRARY_SPECIFIC, "Anime", MediaCardPresentation.POSTER, listOf("Series"), libraryName = "Anime", filter = DiscoveryFilter(DiscoveryFilterType.LIBRARY, "Anime")),
                ShelfDefinition("shows-cartoons", DiscoveryShelfType.LIBRARY_SPECIFIC, "Cartoons", MediaCardPresentation.POSTER, listOf("Series"), libraryName = "Cartoons", filter = DiscoveryFilter(DiscoveryFilterType.LIBRARY, "Cartoons")),
                ShelfDefinition("shows-tv", DiscoveryShelfType.LIBRARY_SPECIFIC, "TV Shows", MediaCardPresentation.POSTER, listOf("Series"), libraryName = "Shows", filter = DiscoveryFilter(DiscoveryFilterType.LIBRARY, "Shows")),
                ShelfDefinition("shows-episodes", DiscoveryShelfType.LATEST_EPISODES, "Latest Episodes", MediaCardPresentation.LANDSCAPE, listOf("Episode"), filter = DiscoveryFilter(DiscoveryFilterType.RECENTLY_ADDED))
            )
            else -> emptyList()
        }
        return PageDefinition(route, pageShelves)
    }
}
