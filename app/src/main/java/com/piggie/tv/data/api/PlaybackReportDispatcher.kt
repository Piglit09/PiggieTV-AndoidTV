package com.piggie.tv.data.api

import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal enum class PlaybackReportKind {
    PLAYING,
    PROGRESS,
    STOPPED
}

/**
 * Serializes reports within one Jellyfin server/user lane without building an unbounded offline
 * queue. This keeps an old session's Stopped event ahead of the next session's Playing event.
 * Only the latest pending progress sample is retained, and a terminal stop supersedes it because
 * Stopped already carries the final position.
 */
internal class PlaybackReportDispatcher(
    private val executor: Executor = DEFAULT_EXECUTOR
) {
    private data class PendingReport(
        val kind: PlaybackReportKind,
        var send: () -> Unit,
        val callbacks: MutableList<(Boolean) -> Unit> = mutableListOf()
    )

    private data class SessionState(
        var running: Boolean = false,
        var currentKind: PlaybackReportKind? = null,
        val pending: MutableList<PendingReport> = mutableListOf()
    )

    private val lock = Any()
    private val sessions = mutableMapOf<String, SessionState>()

    fun enqueue(
        sessionKey: String,
        kind: PlaybackReportKind,
        send: () -> Unit,
        onComplete: (Boolean) -> Unit = {}
    ) {
        var shouldStart = false
        synchronized(lock) {
            val state = sessions.getOrPut(sessionKey) { SessionState() }
            when (kind) {
                PlaybackReportKind.PLAYING -> state.pending += PendingReport(
                    kind = kind,
                    send = send
                )

                PlaybackReportKind.PROGRESS -> enqueueProgress(state, send)
                PlaybackReportKind.STOPPED -> enqueueStopped(state, send, onComplete)
            }
            if (!state.running && state.pending.isNotEmpty()) {
                state.running = true
                shouldStart = true
            }
        }
        if (shouldStart) executor.execute { drain(sessionKey) }
    }

    private fun enqueueProgress(state: SessionState, send: () -> Unit) {
        val last = state.pending.lastOrNull()
        when {
            last?.kind == PlaybackReportKind.STOPPED -> Unit
            last?.kind == PlaybackReportKind.PROGRESS -> last.send = send
            last == null && state.currentKind == PlaybackReportKind.STOPPED -> Unit
            else -> state.pending += PendingReport(PlaybackReportKind.PROGRESS, send)
        }
    }

    private fun enqueueStopped(
        state: SessionState,
        send: () -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        val lastPlayingIndex = state.pending.indexOfLast {
            it.kind == PlaybackReportKind.PLAYING
        }
        val currentLifetimeStart = lastPlayingIndex + 1
        for (index in state.pending.lastIndex downTo currentLifetimeStart) {
            if (state.pending[index].kind == PlaybackReportKind.PROGRESS) {
                state.pending.removeAt(index)
            }
        }
        val existingStop = state.pending
            .subList(currentLifetimeStart, state.pending.size)
            .lastOrNull { it.kind == PlaybackReportKind.STOPPED }
        if (existingStop != null) {
            existingStop.send = send
            existingStop.callbacks += onComplete
        } else {
            state.pending += PendingReport(
                kind = PlaybackReportKind.STOPPED,
                send = send,
                callbacks = mutableListOf(onComplete)
            )
        }
    }

    private fun drain(sessionKey: String) {
        while (true) {
            val report = synchronized(lock) {
                val state = sessions[sessionKey] ?: return
                state.currentKind = null
                if (state.pending.isEmpty()) {
                    state.running = false
                    sessions.remove(sessionKey, state)
                    return
                }
                state.pending.removeAt(0).also { state.currentKind = it.kind }
            }
            val result = runCatching(report.send)
            result.exceptionOrNull()?.let {
                Log.w("JellyfinApi", "Playback report failed", it)
            }
            report.callbacks.forEach { callback ->
                runCatching { callback(result.isSuccess) }
            }
        }
    }

    private companion object {
        val threadNumber = AtomicInteger(0)
        val DEFAULT_EXECUTOR = Executors.newCachedThreadPool { runnable ->
            Thread(
                runnable,
                "ptv-playback-reporter-${threadNumber.incrementAndGet()}"
            ).apply { isDaemon = true }
        }
    }
}
