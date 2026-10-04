package com.piggie.tv.auth

enum class QuickConnectState {
    IDLE,
    REQUESTING,
    WAITING_FOR_APPROVAL,
    APPROVED,
    AUTHENTICATING,
    SUCCESS,
    EXPIRED,
    CANCELLED,
    FAILED,
}

enum class QuickConnectFailure {
    NETWORK,
    SERVER,
    INVALID_RESPONSE,
    SECURE_STORAGE,
}

enum class QuickConnectCommitResult {
    SUCCESS,
    STALE,
    STORAGE_FAILURE,
}

data class QuickConnectSnapshot(
    val generation: Long,
    val state: QuickConnectState,
    val code: String? = null,
    val failure: QuickConnectFailure? = null,
) {
    override fun toString(): String =
        "QuickConnectSnapshot(generation=$generation, state=$state, code=${if (code == null) "none" else "[REDACTED]"}, failure=$failure)"
}

/**
 * Main-thread state owner for one Quick Connect screen.
 *
 * Methods are synchronized as a second line of defence for cancellation callbacks and tests. A
 * generation must match before any transition can occur, so a result from a replaced Activity or
 * regenerated code cannot publish state or claim persistence.
 */
class QuickConnectStateController {
    private var generation = 0L
    private var snapshot = QuickConnectSnapshot(generation, QuickConnectState.IDLE)

    @Synchronized
    fun snapshot(): QuickConnectSnapshot = snapshot

    @Synchronized
    fun beginAttempt(): QuickConnectSnapshot {
        generation += 1L
        snapshot = QuickConnectSnapshot(generation, QuickConnectState.REQUESTING)
        return snapshot
    }

    @Synchronized
    fun isCurrent(candidateGeneration: Long): Boolean =
        candidateGeneration == generation && snapshot.state !in TERMINAL_STATES

    @Synchronized
    fun waitingForApproval(candidateGeneration: Long, code: String? = snapshot.code): Boolean {
        if (!owns(candidateGeneration)) return false
        if (snapshot.state !in WAITING_ENTRY_STATES) return false
        val displayCode = code?.takeIf(String::isNotBlank) ?: return false
        snapshot = snapshot.copy(
            state = QuickConnectState.WAITING_FOR_APPROVAL,
            code = displayCode,
            failure = null,
        )
        return true
    }

    @Synchronized
    fun approved(candidateGeneration: Long): Boolean = transition(
        candidateGeneration,
        expected = setOf(QuickConnectState.WAITING_FOR_APPROVAL),
        next = QuickConnectState.APPROVED,
    )

    @Synchronized
    fun authenticating(candidateGeneration: Long): Boolean = transition(
        candidateGeneration,
        expected = setOf(QuickConnectState.APPROVED),
        next = QuickConnectState.AUTHENTICATING,
    )

    @Synchronized
    fun expire(candidateGeneration: Long): Boolean {
        if (!owns(candidateGeneration) || snapshot.state in TERMINAL_STATES) return false
        snapshot = snapshot.copy(state = QuickConnectState.EXPIRED, failure = null)
        return true
    }

    @Synchronized
    fun cancel(candidateGeneration: Long): Boolean {
        if (!owns(candidateGeneration) || snapshot.state in TERMINAL_STATES) return false
        snapshot = snapshot.copy(state = QuickConnectState.CANCELLED, failure = null)
        return true
    }

    @Synchronized
    fun fail(candidateGeneration: Long, failure: QuickConnectFailure): Boolean {
        if (!owns(candidateGeneration) || snapshot.state in TERMINAL_STATES) return false
        snapshot = snapshot.copy(state = QuickConnectState.FAILED, failure = failure)
        return true
    }

    /**
     * Claims the sole persistence opportunity for this generation.
     *
     * The callback is invoked at most once and only from AUTHENTICATING. A shared auth coordinator
     * can therefore make the durable write conditional on its application-wide generation too.
     */
    @Synchronized
    fun complete(
        candidateGeneration: Long,
        commit: () -> QuickConnectCommitResult,
    ): QuickConnectCommitResult {
        if (!owns(candidateGeneration) || snapshot.state != QuickConnectState.AUTHENTICATING) {
            return QuickConnectCommitResult.STALE
        }

        return when (val result = commit()) {
            QuickConnectCommitResult.SUCCESS -> {
                snapshot = snapshot.copy(state = QuickConnectState.SUCCESS, failure = null)
                result
            }
            QuickConnectCommitResult.STORAGE_FAILURE -> {
                snapshot = snapshot.copy(
                    state = QuickConnectState.FAILED,
                    failure = QuickConnectFailure.SECURE_STORAGE,
                )
                result
            }
            QuickConnectCommitResult.STALE -> {
                snapshot = snapshot.copy(state = QuickConnectState.CANCELLED, failure = null)
                result
            }
        }
    }

    private fun transition(
        candidateGeneration: Long,
        expected: Set<QuickConnectState>,
        next: QuickConnectState,
    ): Boolean {
        if (!owns(candidateGeneration) || snapshot.state !in expected) return false
        snapshot = snapshot.copy(state = next, failure = null)
        return true
    }

    private fun owns(candidateGeneration: Long): Boolean = candidateGeneration == generation

    private companion object {
        val TERMINAL_STATES = setOf(
            QuickConnectState.SUCCESS,
            QuickConnectState.EXPIRED,
            QuickConnectState.CANCELLED,
            QuickConnectState.FAILED,
        )

        val WAITING_ENTRY_STATES = setOf(
            QuickConnectState.REQUESTING,
            QuickConnectState.WAITING_FOR_APPROVAL,
        )
    }
}
