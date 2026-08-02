package com.piggie.tv.data.discovery

object DiscoveryDiagnostics {
    const val SEPARATOR = "\u2022"

    fun latestByShelf(
        manifest: PageManifest,
        generationId: Long,
        samples: List<ShelfDiagnostic>
    ): Map<String, ShelfDiagnostic> = samples
        .asSequence()
        .filter { it.page == manifest.page && it.generationId == generationId }
        .filter { it.shelfId in manifest.shelves.map(ShelfDefinition::id) }
        .groupBy(ShelfDiagnostic::shelfId)
        .mapValues { (_, attempts) ->
            attempts.maxWith(compareBy<ShelfDiagnostic> { it.attemptId }.thenBy { it.transitionOrdinal })
        }

    fun aggregate(
        manifest: PageManifest,
        generationId: Long,
        samples: List<ShelfDiagnostic>,
        adapterCards: Map<String, Int>? = null
    ): DiscoveryManifestAggregate {
        val latest = latestByShelf(manifest, generationId, samples)
        val states = manifest.shelves.map { latest[it.id]?.finalState ?: DiscoveryFinalState.NOT_STARTED }
        val represented = adapterCards ?: latest.values
            .filter(ShelfDiagnostic::adapterPresent)
            .associate { it.shelfId to it.adapterCount }
        return DiscoveryManifestAggregate(
            page = manifest.page,
            generationId = generationId,
            expected = manifest.expectedShelves,
            defined = manifest.shelves.size,
            notStarted = states.count { it == DiscoveryFinalState.NOT_STARTED },
            queued = states.count { it == DiscoveryFinalState.QUEUED },
            requested = latest.values.count(ShelfDiagnostic::networkRequestStarted),
            loading = states.count { it == DiscoveryFinalState.LOADING },
            content = states.count { it == DiscoveryFinalState.CONTENT },
            empty = states.count { it == DiscoveryFinalState.EMPTY },
            failed = states.count { it in FAILURE_STATES },
            canceled = states.count { it in CANCELED_STATES },
            renderedShelves = represented.size,
            renderedCards = represented.values.sum()
        )
    }

    fun formatManifest(value: DiscoveryManifestAggregate): String = listOf(
        "expected ${value.expected}",
        "defined ${value.defined}",
        "not started ${value.notStarted}",
        "queued ${value.queued}",
        "requested ${value.requested}",
        "loading ${value.loading}",
        "content ${value.content}",
        "empty ${value.empty}",
        "failed ${value.failed}",
        "canceled ${value.canceled}",
        "rendered shelves ${value.renderedShelves}",
        "rendered cards ${value.renderedCards}"
    ).joinToString(" $SEPARATOR ")

    fun formatCounts(value: ShelfDiagnostic): String =
        "raw=${value.rawCount} eligible=${value.eligibleCount} " +
            "deduped=${value.deduplicatedCount} adapter=${value.adapterCount} " +
            "visible=${value.visibleCards ?: 0}"

    private val FAILURE_STATES = setOf(
        DiscoveryFinalState.TIMEOUT,
        DiscoveryFinalState.HTTP_ERROR,
        DiscoveryFinalState.INVALID_QUERY,
        DiscoveryFinalState.MISSING_LIBRARY,
        DiscoveryFinalState.RENDER_ERROR
    )
    private val CANCELED_STATES = setOf(
        DiscoveryFinalState.CANCELED_HIDDEN_ROUTE,
        DiscoveryFinalState.CANCELED_REPLACED_REQUEST
    )
}
