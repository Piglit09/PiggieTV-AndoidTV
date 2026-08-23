package com.piggie.tv.data.discovery

import android.util.Log
import com.piggie.tv.data.api.HttpRequestFailure
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.api.JellyfinItemFields
import com.piggie.tv.data.api.NativeRequestScope
import com.piggie.tv.data.api.SafeNetworkDiagnostic
import com.piggie.tv.data.api.DebugDiscoveryFaultInjector
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.recommendations.PremiumRecommendationRanker
import com.piggie.tv.memory.MemoryPressurePolicy
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import android.os.SystemClock

object DiscoveryManager {
    private const val TAG = "DiscoveryManager"
    private var session: DiscoverySession? = null
    private val SESSION_EXPIRY = TimeUnit.HOURS.toMillis(24)
    private val diagnostics = mutableListOf<ShelfDiagnostic>()
    private val memoryCache = DiscoveryMemoryCache()
    private val routeGeneration = AtomicLong()
    private val transitionOrdinal = AtomicLong()
    private val currentGeneration = ConcurrentHashMap<DiscoveryPage, Long>()
    private val activeShelfLoads = AtomicInteger()
    private data class AdapterKey(val page: DiscoveryPage, val generationId: Long, val shelfId: String)
    private data class AdapterState(val attemptId: Int, val cards: Int, val visible: Int)
    private val adapterStates = mutableMapOf<AdapterKey, AdapterState>()

    fun getSession(api: JellyfinNativeApi, nativeSession: NativeSession): DiscoverySession {
        val current = session
        val scopeKey = "${nativeSession.serverId}|${nativeSession.userId}"
        if (current != null && current.scopeKey == scopeKey && (System.currentTimeMillis() - current.timestamp) < SESSION_EXPIRY) {
            return current
        }
        if (current != null && current.scopeKey != scopeKey) memoryCache.clear()
        
        Log.i(TAG, "Generating new Discovery Session")
        return DiscoverySession(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            scopeKey = scopeKey
        ).also { session = it }
    }

    fun onTrimMemory(level: Int) {
        val actions = MemoryPressurePolicy.actions(level)
        if (!actions.hasWork) return
        memoryCache.trimToPercent(actions.discoveryCachePercent)
        if (actions.releaseDiscoverySession) session = null
    }

    fun refresh(api: JellyfinNativeApi, nativeSession: NativeSession): DiscoverySession {
        session = null
        return getSession(api, nativeSession)
    }

    fun clearForLogout() {
        session = null
        memoryCache.clear()
        currentGeneration.clear()
        synchronized(diagnostics) { adapterStates.clear() }
    }

    fun manifest(page: DiscoveryPage): PageManifest {
        val definition = getPageDefinition(page.name)
        return PageManifest(page, definition.shelves.size, definition.shelves)
    }

    fun currentAggregate(page: DiscoveryPage): DiscoveryManifestAggregate {
        return currentPageDiagnostics(page).aggregate
    }

    fun currentGenerationId(page: DiscoveryPage): Long = currentGeneration[page] ?: 0L

    fun currentDiagnostics(page: DiscoveryPage): List<ShelfDiagnostic> {
        return currentPageDiagnostics(page).shelves
    }

    fun currentPageDiagnostics(page: DiscoveryPage): DiscoveryPageDiagnostics = synchronized(diagnostics) {
        val generation = currentGenerationId(page)
        val pageManifest = manifest(page)
        val snapshot = diagnostics.toList()
        val latest = DiscoveryDiagnostics.latestByShelf(pageManifest, generation, snapshot)
        val rows = pageManifest.shelves.mapNotNull { latest[it.id] }
        val cards = adapterStates
            .filterKeys { it.page == page && it.generationId == generation }
            .mapKeys { it.key.shelfId }
            .mapValues { it.value.cards }
        DiscoveryPageDiagnostics(
            generation,
            rows,
            DiscoveryDiagnostics.aggregate(pageManifest, generation, snapshot, cards)
        )
    }

    fun cacheStats(): DiscoveryMemoryCache.Stats = memoryCache.stats()
    fun activeShelfLoadCount(): Int = activeShelfLoads.get()
    fun diagnosticSampleCount(): Int = synchronized(diagnostics) { diagnostics.size }

    fun loadPage(
        api: JellyfinNativeApi,
        nativeSession: NativeSession,
        manifest: PageManifest,
        discoverySession: DiscoverySession,
        onShelf: (DiscoveryShelf) -> Unit
    ): DiscoveryPageRequest {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val requestScope = NativeRequestScope()
        val executor = DiscoveryPageExecutors.create()
        val attempts = ConcurrentHashMap<String, AtomicInteger>()
        val terminalAttempts = ConcurrentHashMap.newKeySet<String>()
        val startedAttempts = ConcurrentHashMap.newKeySet<String>()
        val ordered = manifest.shelves
            .sortedBy(ShelfDefinition::priority)
            .map { runtimeDefinition(it, discoverySession) }
        val generationId = routeGeneration.incrementAndGet()
        currentGeneration[manifest.page] = generationId
        synchronized(diagnostics) {
            adapterStates.keys.removeAll { it.page == manifest.page }
            ordered.forEach { definition ->
                adapterStates[AdapterKey(manifest.page, generationId, definition.id)] = AdapterState(0, 0, 0)
            }
        }
        val bypassFreshCache = DebugDiscoveryFaultInjector.shouldBypassFreshCache()
        ordered.forEach { definition ->
            val key = cacheKey(nativeSession, manifest.page, definition)
            val cached = memoryCache.get(key)
            val refreshAttempt = if (bypassFreshCache && cached?.entry?.shelf?.items?.isNotEmpty() == true) 1 else 0
            attempts[definition.id] = AtomicInteger(refreshAttempt)
            if (cached != null && cached.entry.shelf.items.isNotEmpty()) {
                val diagnostic = cached.entry.shelf.diagnostic.copy(
                    page = manifest.page,
                    status = ShelfStatus.READY,
                    finalState = DiscoveryFinalState.CONTENT,
                    generationId = generationId,
                    attemptId = 0,
                    retryCount = 0,
                    cacheHit = true,
                    cacheAgeMs = cached.ageMs,
                    cacheTtlRemainingMs = cached.ttlRemainingMs,
                    cacheEntryBytes = cached.entry.estimatedBytes,
                    cacheKeyCategory = cacheKeyCategory(definition),
                    cacheSourceShelfId = cached.entry.sourceShelfId,
                    requestAvoided = !bypassFreshCache,
                    cacheRefreshBypassed = bypassFreshCache,
                    networkRequestStarted = false,
                    inFlightCoalesced = false,
                    adapterPresent = true,
                    renderCount = cached.entry.shelf.items.size,
                    visibleCards = 0,
                    elapsedMs = 0,
                    httpMs = null,
                    httpStatus = null,
                    queueWaitMs = 0,
                    dnsMs = null,
                    connectMs = null,
                    tlsMs = null,
                    requestWriteMs = null,
                    timeToFirstByteMs = null,
                    responseReadMs = null,
                    responseBytes = null,
                    faultInjectionDelayMs = null,
                    parseMs = null,
                    filteringMs = null,
                    scoringMs = null,
                    dedupeMs = null,
                    firstCardSubmitMs = 0,
                    generationStartedMs = System.currentTimeMillis(),
                    requestStartedMs = null,
                    requestFinishedMs = System.currentTimeMillis(),
                    timestampMs = System.currentTimeMillis()
                    ,transitionOrdinal = 0
                )
                recordDiagnostic(diagnostic)
                onShelf(cached.entry.shelf.copy(diagnostic = diagnostic))
                terminalAttempts += "${definition.id}:0"
                if (!bypassFreshCache) return@forEach
            }
            val queuedAt = SystemClock.elapsedRealtime()
            recordDiagnostic(
                createDiagnostic(
                    definition, manifest.page, ShelfStatus.LOADING, 0, 0,
                    retryCount = refreshAttempt,
                    generationId = generationId,
                    attemptId = refreshAttempt,
                    cacheRefreshBypassed = bypassFreshCache && cached != null,
                    finalState = DiscoveryFinalState.QUEUED
                )
            )
            executor.execute {
                if (cancelled.get()) return@execute
                val attempt = attempts.getValue(definition.id).get()
                val attemptKey = "${definition.id}:$attempt"
                startedAttempts += attemptKey
                val queueWait = SystemClock.elapsedRealtime() - queuedAt
                recordDiagnostic(
                    createDiagnostic(
                        definition, manifest.page, ShelfStatus.LOADING, 0, queueWait,
                        retryCount = attempt,
                        generationId = generationId,
                        attemptId = attempt,
                        queueWaitMs = queueWait,
                        finalState = DiscoveryFinalState.LOADING,
                        networkRequestStarted = true,
                        cacheRefreshBypassed = bypassFreshCache && cached != null
                    )
                )
                loadShelf(api, nativeSession, definition, discoverySession, requestScope,
                    page = manifest.page,
                    retryCount = attempt,
                    generationId = generationId,
                    queueWaitMs = queueWait,
                    cacheRefreshBypassed = bypassFreshCache && cached != null,
                    launchInBackground = false,
                    onSuccess = { shelf ->
                        terminalAttempts += attemptKey
                        if (cancelled.get() || attempts[definition.id]?.get() != attempt) return@loadShelf
                        val withCacheEvidence = cacheSuccessfulShelf(nativeSession, manifest.page, shelf)
                        onShelf(retainStaleCardsOnFailure(withCacheEvidence, cached?.entry?.shelf))
                    },
                    onError = { }
                )
            }
        }

        return object : DiscoveryPageRequest {
            override fun cancel() {
                cancelled.set(true)
                requestScope.cancel()
                executor.shutdownNow()
                ordered.forEach { definition ->
                    val attempt = attempts[definition.id]?.get() ?: 0
                    val attemptKey = "${definition.id}:$attempt"
                    if (attemptKey !in terminalAttempts) {
                        recordDiagnostic(createDiagnostic(
                            definition, manifest.page, ShelfStatus.CANCELED_HIDDEN_ROUTE, 0, 0,
                            retryCount = attempt,
                            failure = "hidden-route",
                            generationId = generationId,
                            attemptId = attempt,
                            networkRequestStarted = attemptKey in startedAttempts
                        ))
                    }
                }
            }
            override fun retryShelf(shelfId: String) {
                if (cancelled.get()) return
                manifest.shelves.find { it.id == shelfId }
                    ?.let { runtimeDefinition(it, discoverySession) }
                    ?.let { definition ->
                    val attempt = attempts.getOrPut(definition.id) { AtomicInteger() }.incrementAndGet()
                    val cached = memoryCache.get(cacheKey(nativeSession, manifest.page, definition))
                    val queuedAt = SystemClock.elapsedRealtime()
                    recordDiagnostic(createDiagnostic(
                        definition, manifest.page, ShelfStatus.LOADING, 0, 0,
                        retryCount = attempt,
                        generationId = generationId,
                        attemptId = attempt,
                        cacheRefreshBypassed = true,
                        finalState = DiscoveryFinalState.QUEUED
                    ))
                    executor.execute {
                        if (cancelled.get()) return@execute
                        val attemptKey = "${definition.id}:$attempt"
                        startedAttempts += attemptKey
                        val queueWait = SystemClock.elapsedRealtime() - queuedAt
                        recordDiagnostic(createDiagnostic(
                            definition, manifest.page, ShelfStatus.LOADING, 0, queueWait,
                            retryCount = attempt,
                            generationId = generationId,
                            attemptId = attempt,
                            queueWaitMs = queueWait,
                            finalState = DiscoveryFinalState.LOADING,
                            networkRequestStarted = true,
                            cacheRefreshBypassed = true
                        ))
                        loadShelf(api, nativeSession, definition, discoverySession, requestScope,
                        page = manifest.page,
                        retryCount = attempt,
                        generationId = generationId,
                        queueWaitMs = queueWait,
                        cacheRefreshBypassed = true,
                        launchInBackground = false,
                        onSuccess = { shelf ->
                            terminalAttempts += attemptKey
                            if (!cancelled.get() && attempts[definition.id]?.get() == attempt) {
                                val withCacheEvidence = cacheSuccessfulShelf(nativeSession, manifest.page, shelf)
                                onShelf(retainStaleCardsOnFailure(withCacheEvidence, cached?.entry?.shelf))
                            }
                        },
                        onError = { /* Log error */ }
                    )
                    }
                }
            }
        }
    }

    fun recordRendered(shelf: DiscoveryShelf, childCount: Int) {
        val diagnostic = shelf.diagnostic
        synchronized(diagnostics) {
            val key = AdapterKey(diagnostic.page, diagnostic.generationId, shelf.definition.id)
            val current = adapterStates[key]
            if (current != null && current.attemptId <= diagnostic.attemptId) {
                adapterStates[key] = AdapterState(diagnostic.attemptId, shelf.items.size, childCount)
            }
            updateDiagnosticLocked(diagnostic) {
                it.copy(visibleCards = childCount, renderCompletedMs = System.currentTimeMillis(), adapterPresent = true)
            }
        }
    }

    fun recordAdapterState(shelf: DiscoveryShelf, adapterBuildMs: Long) {
        val diagnostic = shelf.diagnostic
        synchronized(diagnostics) {
            val key = AdapterKey(diagnostic.page, diagnostic.generationId, shelf.definition.id)
            val current = adapterStates[key]
            if (current != null && current.attemptId <= diagnostic.attemptId) {
                adapterStates[key] = AdapterState(diagnostic.attemptId, shelf.items.size, current.visible)
            }
            updateDiagnosticLocked(diagnostic) {
                val submittedMs = it.generationStartedMs?.let { started ->
                    (System.currentTimeMillis() - started).coerceAtLeast(0L)
                }
                it.copy(
                    renderCount = shelf.items.size,
                    adapterPresent = true,
                    adapterBuildMs = adapterBuildMs,
                    firstCardSubmitMs = submittedMs
                )
            }
        }
    }

    fun recordFirstPoster(shelf: DiscoveryShelf, elapsedMs: Long) {
        synchronized(diagnostics) {
            updateDiagnosticLocked(shelf.diagnostic) { it.copy(firstPosterMs = elapsedMs) }
        }
    }

    fun recentDiagnostics(): List<ShelfDiagnostic> = synchronized(diagnostics) { diagnostics.toList() }

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
                params["Fields"] = JellyfinItemFields.CARD
                
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
        requestScope: NativeRequestScope? = null,
        page: DiscoveryPage = DiscoveryPage.HOME,
        retryCount: Int = 0,
        generationId: Long = 0,
        queueWaitMs: Long = 0,
        cacheRefreshBypassed: Boolean = false,
        launchInBackground: Boolean = true,
        onSuccess: (DiscoveryShelf) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val enqueuedAt = SystemClock.elapsedRealtime() - queueWaitMs
        val generationStartedAt = System.currentTimeMillis() - queueWaitMs
        val load = load@{
            activeShelfLoads.incrementAndGet()
            val networkSamples = mutableListOf<SafeNetworkDiagnostic>()
            var parseTotalMs = 0L
            fun <T> capturedCall(action: () -> T): T = try {
                action()
            } finally {
                api.consumeNetworkDiagnostic()?.let(networkSamples::add)
                parseTotalMs += api.consumeParseDurationMs() ?: 0L
            }
            try {
                val requestStartedAt = System.currentTimeMillis()
                val resolvedLibraryId = if (definition.type == DiscoveryShelfType.LIBRARY_SPECIFIC && !definition.libraryName.isNullOrBlank()) {
                    capturedCall { api.findLibraryId(nativeSession, definition.libraryName) }
                } else {
                    definition.parentId
                }
                if (definition.type == DiscoveryShelfType.LIBRARY_SPECIFIC && resolvedLibraryId == null) {
                    val diagnostic = createDiagnostic(
                        definition, page, ShelfStatus.MISSING_LIBRARY, 0,
                        SystemClock.elapsedRealtime() - enqueuedAt,
                        retryCount = retryCount,
                        generationId = generationId,
                        attemptId = retryCount,
                        queueWaitMs = queueWaitMs,
                        network = combineNetworkDiagnostics(networkSamples),
                        parseMs = parseTotalMs,
                        requestStartedAt = requestStartedAt,
                        cacheRefreshBypassed = cacheRefreshBypassed
                    )
                    recordDiagnostic(diagnostic)
                    onSuccess(
                        DiscoveryShelf(
                            definition, emptyList(), ShelfStatus.MISSING_LIBRARY, diagnostic,
                            message = "The ${definition.libraryName} library is not available."
                        )
                    )
                    return@load
                }
                var params = DiscoveryQueryPlanner.itemParameters(
                    definition.itemTypes,
                    definition.filter,
                    definition.limit,
                    resolvedLibraryId
                )
                var endpoint = "/Users/[user]/Items"
                var deliveredDefinition = definition
                val rawItems = when (definition.type) {
                    DiscoveryShelfType.CONTINUE_WATCHING -> {
                        endpoint = "/Users/[user]/Items/Resume"
                        params = DiscoveryQueryPlanner.resumeParameters(definition.itemTypes, definition.limit)
                        capturedCall { api.fetchResume(nativeSession, definition.itemTypes, definition.limit) }
                    }
                    DiscoveryShelfType.NEXT_UP -> {
                        endpoint = "/Shows/NextUp"
                        params = linkedMapOf(
                            "IncludeItemTypes" to "Episode",
                            "Limit" to definition.limit.toString(),
                            "Fields" to DiscoveryQueryPlanner.CARD_FIELDS
                        )
                        capturedCall { api.fetchNextUp(nativeSession, definition.limit) }
                    }
                    DiscoveryShelfType.RECOMMENDED, DiscoveryShelfType.YOU_MAY_ALSO_LIKE -> {
                        val candidateLimit = (definition.limit * 3).coerceIn(definition.limit, 48)
                        endpoint = "/Users/[user]/Suggestions"
                        params = linkedMapOf(
                            "IncludeItemTypes" to definition.itemTypes.joinToString(","),
                            "Limit" to candidateLimit.toString(),
                            "Fields" to DiscoveryQueryPlanner.RECOMMENDATION_FIELDS
                        )
                        capturedCall {
                            api.fetchRecommendations(
                                nativeSession,
                                definition.itemTypes.joinToString(","),
                                candidateLimit
                            )
                        }
                    }
                    else -> {
                        var result = capturedCall { api.fetchItems(nativeSession, params) }
                        if (definition.type == DiscoveryShelfType.RANDOM_GENRE &&
                            DiscoveryShelfResultPolicy.eligible(result, definition).isEmpty()) {
                            val originalGenre = params["Genres"]
                            val reservedElsewhere = synchronized(discoverySession.genreByShelf) {
                                discoverySession.genreByShelf
                                    .filterKeys { it != definition.id }
                                    .values
                                    .toSet()
                            }
                            for (genre in listOf("Horror", "Comedy", "Science Fiction", "Action", "Drama")) {
                                if (genre.equals(originalGenre, true)) continue
                                if (reservedElsewhere.any { it.equals(genre, true) }) continue
                                val alternate = params.toMutableMap().apply { put("Genres", genre) }
                                val candidate = capturedCall { api.fetchItems(nativeSession, alternate) }
                                params = alternate
                                result = candidate
                                deliveredDefinition = definition.copy(
                                    title = genre,
                                    filter = definition.filter.copy(value = genre)
                                )
                                synchronized(discoverySession.genreByShelf) {
                                    discoverySession.genreByShelf[definition.id] = genre
                                }
                                if (DiscoveryShelfResultPolicy.eligible(candidate, deliveredDefinition).isNotEmpty()) {
                                    break
                                }
                            }
                        }
                        result
                    }
                }
                val network = combineNetworkDiagnostics(networkSamples)

                val filterStarted = SystemClock.elapsedRealtime()
                val eligible = DiscoveryShelfResultPolicy.eligible(rawItems, definition)
                val filteringMs = SystemClock.elapsedRealtime() - filterStarted
                val dedupeStarted = SystemClock.elapsedRealtime()
                val deduplicated = DiscoveryShelfResultPolicy.deduplicate(eligible)
                val dedupeMs = SystemClock.elapsedRealtime() - dedupeStarted
                val scoringStarted = SystemClock.elapsedRealtime()
                val finalItems = if (
                    definition.type == DiscoveryShelfType.RECOMMENDED ||
                    definition.type == DiscoveryShelfType.YOU_MAY_ALSO_LIKE
                ) {
                    PremiumRecommendationRanker.rankSuggestions(deduplicated, definition.limit)
                } else {
                    deduplicated.take(definition.limit)
                }
                val scoringMs = SystemClock.elapsedRealtime() - scoringStarted
                val status = if (finalItems.isNotEmpty()) ShelfStatus.READY else ShelfStatus.EMPTY

                val viewMore = if (definition.type == DiscoveryShelfType.LIBRARY_SPECIFIC || 
                                 definition.type == DiscoveryShelfType.RANDOM_GENRE ||
                                 definition.type == DiscoveryShelfType.RANDOM_STUDIO) {
                    MediaItem(
                        id = "view-more-${definition.id}",
                        title = deliveredDefinition.title,
                        type = "ViewMore",
                        year = null,
                        imageTag = null,
                        playbackPositionTicks = 0,
                        runtimeTicks = 0,
                        seriesName = null,
                        episodeLabel = null
                    )
                } else null

                val duration = SystemClock.elapsedRealtime() - enqueuedAt
                Log.d(TAG, "Generated shelf: ${definition.title} in ${duration}ms count=${finalItems.size}")
                val diagnostic = createDiagnostic(
                    definition = deliveredDefinition,
                    page = page,
                    status = status,
                    count = rawItems.size,
                    duration = duration,
                    retryCount = retryCount,
                    generationId = generationId,
                    attemptId = retryCount,
                    queueWaitMs = queueWaitMs,
                    query = queryManifest(deliveredDefinition, endpoint, params),
                    network = network,
                    eligibleCount = eligible.size,
                    deduplicatedCount = deduplicated.size,
                    adapterCount = finalItems.size,
                    parseMs = parseTotalMs,
                    filteringMs = filteringMs,
                    scoringMs = scoringMs,
                    dedupeMs = dedupeMs,
                    generationStartedAt = generationStartedAt,
                    requestStartedAt = requestStartedAt,
                    cacheRefreshBypassed = cacheRefreshBypassed
                )
                recordDiagnostic(diagnostic)
                onSuccess(DiscoveryShelf(deliveredDefinition, finalItems, status, diagnostic, viewMore, message = emptyMessage(deliveredDefinition, status)))
            } catch (e: Exception) {
                if (requestScope?.isCancelled == true) return@load
                Log.e(TAG, "Error loading shelf: ${definition.title}", e)
                val (status, message) = shelfFailure(e)
                val network = combineNetworkDiagnostics(networkSamples)
                val failedShelf = DiscoveryShelf(
                        definition = definition,
                        items = emptyList(),
                        status = status,
                        diagnostic = createDiagnostic(
                            definition, page, status, 0,
                            SystemClock.elapsedRealtime() - enqueuedAt,
                            retryCount = retryCount,
                            failure = e::class.java.simpleName,
                            httpStatus = (e as? HttpRequestFailure)?.statusCode,
                            generationId = generationId,
                            attemptId = retryCount,
                            queueWaitMs = queueWaitMs,
                            network = network,
                            parseMs = parseTotalMs,
                            generationStartedAt = generationStartedAt,
                            cacheRefreshBypassed = cacheRefreshBypassed
                        ),
                        message = message
                    )
                recordDiagnostic(failedShelf.diagnostic)
                onSuccess(failedShelf)
            } finally {
                activeShelfLoads.decrementAndGet()
            }
        }
        val scopedLoad = {
            DebugDiscoveryFaultInjector.withShelfScope(definition.id, generationId, retryCount) {
                if (requestScope == null) {
                    load()
                } else {
                    api.withRequestScope(requestScope, load)
                }
            }
        }
        if (launchInBackground) kotlin.concurrent.thread(block = scopedLoad) else scopedLoad()
    }

    internal fun shelfFailure(error: Throwable): Pair<ShelfStatus, String> = when (error) {
        is java.net.SocketTimeoutException -> ShelfStatus.TIMEOUT to "The server took too long to respond."
        is java.io.InterruptedIOException -> if (error.message?.contains("timeout", true) == true) {
            ShelfStatus.TIMEOUT to "The server took too long to respond."
        } else {
            ShelfStatus.HTTP_ERROR to "PiggieTV could not reach the Jellyfin server."
        }
        is HttpRequestFailure -> if (error.statusCode == 400) {
            ShelfStatus.INVALID_QUERY to "PiggieTV sent a query the server could not accept (HTTP 400)."
        } else ShelfStatus.HTTP_ERROR to when (error.statusCode) {
            401, 403 -> "Your Jellyfin session is no longer authorized."
            502, 503, 504, 522, 523, 524 -> "The Jellyfin server is temporarily unavailable (HTTP ${error.statusCode})."
            else -> "The Jellyfin server returned HTTP ${error.statusCode}."
        }
        is java.io.IOException -> ShelfStatus.HTTP_ERROR to "PiggieTV could not reach the Jellyfin server."
        is org.json.JSONException -> ShelfStatus.RENDER_ERROR to "The server response could not be read."
        else -> ShelfStatus.HTTP_ERROR to "PiggieTV could not load this shelf."
    }

    private fun createDiagnostic(
        definition: ShelfDefinition,
        page: DiscoveryPage,
        status: ShelfStatus,
        count: Int,
        duration: Long,
        retryCount: Int = 0,
        failure: String? = null,
        httpStatus: Int? = null,
        generationId: Long = 0,
        attemptId: Int = retryCount,
        queueWaitMs: Long? = null,
        finalState: DiscoveryFinalState = status.toFinalState(),
        query: ShelfQueryManifest? = queryManifest(definition),
        network: SafeNetworkDiagnostic? = null,
        eligibleCount: Int = count,
        deduplicatedCount: Int = eligibleCount,
        adapterCount: Int = deduplicatedCount,
        parseMs: Long? = null,
        filteringMs: Long? = null,
        scoringMs: Long? = null,
        dedupeMs: Long? = null,
        generationStartedAt: Long = System.currentTimeMillis() - duration,
        requestStartedAt: Long = System.currentTimeMillis() - duration,
        cacheRefreshBypassed: Boolean = false,
        networkRequestStarted: Boolean = finalState !in setOf(
            DiscoveryFinalState.NOT_STARTED,
            DiscoveryFinalState.QUEUED,
            DiscoveryFinalState.CANCELED_HIDDEN_ROUTE,
            DiscoveryFinalState.CANCELED_REPLACED_REQUEST
        )
    ) = ShelfDiagnostic(
        shelfId = definition.id,
        shelfTitle = definition.title,
        page = page,
        status = status,
        query = query,
        resultCount = count,
        deduplicatedCount = deduplicatedCount,
        renderCount = adapterCount,
        filteredCount = eligibleCount,
        elapsedMs = duration,
        httpMs = network?.totalMs,
        httpStatus = httpStatus ?: network?.responseStatus ?: if (status == ShelfStatus.READY || status == ShelfStatus.EMPTY || status == ShelfStatus.NO_ITEMS) 200 else null,
        cacheHit = false,
        retryCount = retryCount,
        failure = failure,
        generationStartedMs = generationStartedAt,
        requestStartedMs = requestStartedAt,
        requestFinishedMs = System.currentTimeMillis(),
        finalState = finalState,
        generationId = generationId,
        attemptId = attemptId,
        queueWaitMs = queueWaitMs,
        dnsMs = network?.dnsMs,
        connectMs = network?.connectMs,
        tlsMs = network?.tlsMs,
        requestWriteMs = network?.requestWriteMs,
        timeToFirstByteMs = network?.timeToFirstByteMs,
        responseReadMs = network?.responseReadMs,
        responseBytes = network?.responseBytes,
        faultInjectionDelayMs = network?.debugDelayMs,
        parseMs = parseMs,
        filteringMs = filteringMs,
        scoringMs = scoringMs,
        dedupeMs = dedupeMs,
        firstCardSubmitMs = null,
        cacheKeyCategory = cacheKeyCategory(definition),
        cacheSourceShelfId = definition.id,
        cacheRefreshBypassed = cacheRefreshBypassed,
        networkRequestStarted = networkRequestStarted
    )

    internal fun queryManifest(
        definition: ShelfDefinition,
        endpoint: String = when (definition.type) {
            DiscoveryShelfType.CONTINUE_WATCHING -> "/Users/[user]/Items/Resume"
            DiscoveryShelfType.NEXT_UP -> "/Shows/NextUp"
            DiscoveryShelfType.RECOMMENDED, DiscoveryShelfType.YOU_MAY_ALSO_LIKE -> "/Users/[user]/Suggestions"
            else -> "/Users/[user]/Items"
        },
        params: Map<String, String> = when (definition.type) {
            DiscoveryShelfType.CONTINUE_WATCHING -> DiscoveryQueryPlanner.resumeParameters(definition.itemTypes, definition.limit)
            else -> DiscoveryQueryPlanner.itemParameters(definition.itemTypes, definition.filter, definition.limit, definition.parentId)
        }
    ): ShelfQueryManifest {
        return ShelfQueryManifest(definition.id, definition.dataSource, endpoint, "credentials redacted", definition.libraryName, params, params["SortBy"], definition.limit)
    }

    private fun recordDiagnostic(value: ShelfDiagnostic) = synchronized(diagnostics) {
        diagnostics.add(value.copy(transitionOrdinal = transitionOrdinal.incrementAndGet()))
        while (diagnostics.size > 128) diagnostics.removeAt(0)
    }

    private fun updateDiagnosticLocked(source: ShelfDiagnostic, transform: (ShelfDiagnostic) -> ShelfDiagnostic) {
        val index = diagnostics.indexOfLast {
            it.page == source.page &&
                it.generationId == source.generationId &&
                it.shelfId == source.shelfId &&
                it.attemptId == source.attemptId
        }
        if (index >= 0) {
            diagnostics[index] = transform(diagnostics[index]).copy(
                transitionOrdinal = transitionOrdinal.incrementAndGet()
            )
        }
    }

    private fun cacheKey(session: NativeSession, page: DiscoveryPage, definition: ShelfDefinition): String =
        listOf("v1", session.serverId, session.userId, page.name, cacheKeyCategory(definition), definition.itemTypes.joinToString(","), definition.parentId.orEmpty(), definition.filter.type.name, definition.filter.value.orEmpty(), definition.limit.toString()).joinToString("|")

    private fun cacheKeyCategory(definition: ShelfDefinition): String = when (definition.type) {
        DiscoveryShelfType.CONTINUE_WATCHING -> "RESUME"
        DiscoveryShelfType.NEXT_UP -> "NEXT_UP"
        DiscoveryShelfType.RECOMMENDED, DiscoveryShelfType.YOU_MAY_ALSO_LIKE -> "SUGGESTIONS"
        DiscoveryShelfType.RANDOM_GENRE -> "GENRE_ITEMS"
        else -> "USER_ITEMS"
    }

    private fun estimateShelfBytes(shelf: DiscoveryShelf): Long = shelf.items.sumOf { item ->
        fun strings(values: Iterable<String>): Long = values.sumOf { 40L + it.length * 2L }
        val scalarStrings = listOfNotNull(
            item.id, item.title, item.type, item.year, item.imageTag, item.backdropTag, item.logoTag,
            item.seriesName, item.episodeLabel, item.overview, item.officialRating, item.container,
            item.seriesId, item.seasonId, item.album, item.albumArtist, item.albumId, item.artistId,
            item.director, item.lastPlayedDate, item.dateCreated, item.thumbImageTag,
            item.parentBackdropItemId, item.parentLogoItemId, item.parentLogoImageTag,
            item.parentPrimaryImageItemId, item.parentPrimaryImageTag, item.parentThumbItemId,
            item.parentThumbImageTag, item.seriesPrimaryImageTag
        )
        384L + strings(scalarStrings) + strings(item.genres) + strings(item.studios) +
            strings(item.artists) + strings(item.backdropImageTags) + strings(item.parentBackdropImageTags) +
            item.people.sumOf { person ->
                96L + strings(listOfNotNull(person.id, person.name, person.role, person.type, person.primaryImageTag))
            }
    }

    private fun cacheSuccessfulShelf(
        session: NativeSession,
        page: DiscoveryPage,
        shelf: DiscoveryShelf
    ): DiscoveryShelf {
        if (shelf.status != ShelfStatus.READY || shelf.items.isEmpty()) return shelf
        val estimatedBytes = estimateShelfBytes(shelf)
        memoryCache.put(cacheKey(session, page, shelf.definition), shelf, estimatedBytes)
        val enriched = shelf.copy(
            diagnostic = shelf.diagnostic.copy(cacheEntryBytes = estimatedBytes)
        )
        synchronized(diagnostics) {
            updateDiagnosticLocked(shelf.diagnostic) { it.copy(cacheEntryBytes = estimatedBytes) }
        }
        return enriched
    }

    private fun combineNetworkDiagnostics(samples: List<SafeNetworkDiagnostic>): SafeNetworkDiagnostic? {
        if (samples.isEmpty()) return null
        if (samples.size == 1) return samples.single()
        fun sum(selector: (SafeNetworkDiagnostic) -> Long?): Long? {
            val values = samples.mapNotNull(selector)
            return values.takeIf(List<Long>::isNotEmpty)?.sum()
        }
        val last = samples.last()
        return last.copy(
            totalMs = sum(SafeNetworkDiagnostic::totalMs),
            dnsMs = sum(SafeNetworkDiagnostic::dnsMs),
            connectMs = sum(SafeNetworkDiagnostic::connectMs),
            tlsMs = sum(SafeNetworkDiagnostic::tlsMs),
            requestWriteMs = sum(SafeNetworkDiagnostic::requestWriteMs),
            timeToFirstByteMs = sum(SafeNetworkDiagnostic::timeToFirstByteMs),
            responseReadMs = sum(SafeNetworkDiagnostic::responseReadMs),
            responseBytes = sum(SafeNetworkDiagnostic::responseBytes),
            debugDelayMs = sum(SafeNetworkDiagnostic::debugDelayMs)
        )
    }

    private fun emptyMessage(definition: ShelfDefinition, status: ShelfStatus): String? {
        if (status != ShelfStatus.EMPTY) return null
        return when (definition.type) {
            DiscoveryShelfType.NEXT_UP -> "Nothing is up next right now."
            DiscoveryShelfType.CONTINUE_WATCHING -> "Nothing to continue right now."
            DiscoveryShelfType.RANDOM_GENRE -> "No titles are available for this genre."
            else -> "Nothing is available right now."
        }
    }

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
                ShelfDefinition("home.continue", DiscoveryShelfType.CONTINUE_WATCHING, "Continue Watching", MediaCardPresentation.LANDSCAPE, listOf("Movie", "Episode"), filter = DiscoveryFilter(DiscoveryFilterType.CONTINUE_WATCHING), priority = 10),
                ShelfDefinition("home.nextup", DiscoveryShelfType.NEXT_UP, "Next Up", MediaCardPresentation.LANDSCAPE, listOf("Episode"), filter = DiscoveryFilter(DiscoveryFilterType.NEXT_UP), priority = 20),
                ShelfDefinition("home.added", DiscoveryShelfType.RECENTLY_ADDED, "Recently Added", MediaCardPresentation.POSTER, listOf("Movie", "Series"), filter = DiscoveryFilter(DiscoveryFilterType.RECENTLY_ADDED), priority = 30),
                ShelfDefinition("home.recommended", DiscoveryShelfType.RECOMMENDED, "Recommended", MediaCardPresentation.POSTER, listOf("Movie", "Series"), filter = DiscoveryFilter(DiscoveryFilterType.RECOMMENDED), priority = 40),
                ShelfDefinition("home.trending", DiscoveryShelfType.TRENDING, "Trending", MediaCardPresentation.POSTER, listOf("Movie", "Series"), filter = DiscoveryFilter(DiscoveryFilterType.POPULAR), priority = 50),
                ShelfDefinition("home.latest", DiscoveryShelfType.LATEST_RELEASES, "Latest Releases", MediaCardPresentation.POSTER, listOf("Movie"), filter = DiscoveryFilter(DiscoveryFilterType.LATEST_RELEASES), priority = 60),
                ShelfDefinition("home.genre.1", DiscoveryShelfType.RANDOM_GENRE, "Horror", MediaCardPresentation.POSTER, listOf("Movie", "Series"), filter = DiscoveryFilter(DiscoveryFilterType.RANDOM_GENRE, "Horror"), priority = 70),
                ShelfDefinition("home.genre.2", DiscoveryShelfType.RANDOM_GENRE, "Comedy", MediaCardPresentation.POSTER, listOf("Movie", "Series"), filter = DiscoveryFilter(DiscoveryFilterType.RANDOM_GENRE, "Comedy"), priority = 80)
            )
            "MOVIES" -> listOf(
                ShelfDefinition("movies.added", DiscoveryShelfType.RECENTLY_ADDED, "Recently Added", MediaCardPresentation.POSTER, listOf("Movie"), filter = DiscoveryFilter(DiscoveryFilterType.RECENTLY_ADDED)),
                ShelfDefinition("movies.continue", DiscoveryShelfType.CONTINUE_WATCHING, "Continue Watching", MediaCardPresentation.LANDSCAPE, listOf("Movie"), filter = DiscoveryFilter(DiscoveryFilterType.CONTINUE_WATCHING)),
                ShelfDefinition("movies.like", DiscoveryShelfType.YOU_MAY_ALSO_LIKE, "You May Also Like", MediaCardPresentation.POSTER, listOf("Movie"), filter = DiscoveryFilter(DiscoveryFilterType.RECOMMENDED)),
                ShelfDefinition("movies.popular", DiscoveryShelfType.POPULAR, "Popular Movies", MediaCardPresentation.POSTER, listOf("Movie"), filter = DiscoveryFilter(DiscoveryFilterType.POPULAR)),
                ShelfDefinition("movies.genre.1", DiscoveryShelfType.RANDOM_GENRE, "Genres", MediaCardPresentation.POSTER, listOf("Movie"), filter = DiscoveryFilter(DiscoveryFilterType.RANDOM_GENRE, "Action")),
                ShelfDefinition("movies.genre.2", DiscoveryShelfType.RANDOM_GENRE, "More Genres", MediaCardPresentation.POSTER, listOf("Movie"), filter = DiscoveryFilter(DiscoveryFilterType.RANDOM_GENRE, "Drama")),
                ShelfDefinition("movies.studio.1", DiscoveryShelfType.RANDOM_STUDIO, "Studios", MediaCardPresentation.POSTER, listOf("Movie"), filter = DiscoveryFilter(DiscoveryFilterType.RANDOM_STUDIO, "Marvel")),
                ShelfDefinition("movies.studio.2", DiscoveryShelfType.RANDOM_STUDIO, "More Studios", MediaCardPresentation.POSTER, listOf("Movie"), filter = DiscoveryFilter(DiscoveryFilterType.RANDOM_STUDIO, "Warner"))
            )
            "SHOWS" -> listOf(
                ShelfDefinition("shows.added", DiscoveryShelfType.RECENTLY_ADDED, "Recently Added", MediaCardPresentation.POSTER, listOf("Series"), filter = DiscoveryFilter(DiscoveryFilterType.RECENTLY_ADDED)),
                ShelfDefinition("shows.continue", DiscoveryShelfType.CONTINUE_WATCHING, "Continue Watching", MediaCardPresentation.LANDSCAPE, listOf("Episode"), filter = DiscoveryFilter(DiscoveryFilterType.CONTINUE_WATCHING)),
                ShelfDefinition("shows.popular", DiscoveryShelfType.POPULAR, "Popular Shows", MediaCardPresentation.POSTER, listOf("Series"), filter = DiscoveryFilter(DiscoveryFilterType.POPULAR)),
                ShelfDefinition("shows.anime", DiscoveryShelfType.LIBRARY_SPECIFIC, "Anime", MediaCardPresentation.POSTER, listOf("Series"), libraryName = "Anime", filter = DiscoveryFilter(DiscoveryFilterType.LIBRARY, "Anime")),
                ShelfDefinition("shows.cartoons", DiscoveryShelfType.LIBRARY_SPECIFIC, "Cartoons", MediaCardPresentation.POSTER, listOf("Series"), libraryName = "Cartoons", filter = DiscoveryFilter(DiscoveryFilterType.LIBRARY, "Cartoons")),
                ShelfDefinition("shows.tv", DiscoveryShelfType.LIBRARY_SPECIFIC, "TV Shows", MediaCardPresentation.POSTER, listOf("Series"), libraryName = "Shows", filter = DiscoveryFilter(DiscoveryFilterType.LIBRARY, "Shows")),
                ShelfDefinition("shows.episodes", DiscoveryShelfType.LATEST_EPISODES, "Latest Episodes", MediaCardPresentation.LANDSCAPE, listOf("Episode"), filter = DiscoveryFilter(DiscoveryFilterType.RECENTLY_ADDED)),
                ShelfDefinition("shows.genre", DiscoveryShelfType.RANDOM_GENRE, "Genre Discovery", MediaCardPresentation.POSTER, listOf("Series"), filter = DiscoveryFilter(DiscoveryFilterType.RANDOM_GENRE, "Drama"))
            )
            else -> emptyList()
        }
        return PageDefinition(route, pageShelves)
    }

    private fun runtimeDefinition(definition: ShelfDefinition, session: DiscoverySession): ShelfDefinition {
        if (definition.type != DiscoveryShelfType.RANDOM_GENRE) return definition
        val selected = synchronized(session.genreByShelf) {
            session.genreByShelf.getOrPut(definition.id) { definition.filter.value ?: definition.title }
        }
        return definition.copy(title = selected, filter = definition.filter.copy(value = selected))
    }

    private fun retainStaleCardsOnFailure(
        fresh: DiscoveryShelf,
        cached: DiscoveryShelf?
    ): DiscoveryShelf = if (
        fresh.status in setOf(
            ShelfStatus.TIMEOUT,
            ShelfStatus.HTTP_ERROR,
            ShelfStatus.INVALID_QUERY,
            ShelfStatus.MISSING_LIBRARY,
            ShelfStatus.FAILED_RENDER,
            ShelfStatus.RENDER_ERROR
        ) && cached?.items?.isNotEmpty() == true
    ) {
        fresh.copy(items = cached.items, viewMoreItem = cached.viewMoreItem)
    } else {
        fresh
    }
}
