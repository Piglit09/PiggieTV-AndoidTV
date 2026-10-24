package com.piggie.tv.data.api

import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReportDispatcherTest {
    @Test
    fun `only latest pending progress sample is sent`() {
        val executor = QueuedExecutor()
        val dispatcher = PlaybackReportDispatcher(executor)
        val sent = mutableListOf<String>()

        dispatcher.enqueue("session", PlaybackReportKind.PLAYING, { sent += "playing" })
        dispatcher.enqueue("session", PlaybackReportKind.PROGRESS, { sent += "progress-1" })
        dispatcher.enqueue("session", PlaybackReportKind.PROGRESS, { sent += "progress-2" })
        executor.runNext()

        assertEquals(listOf("playing", "progress-2"), sent)
        assertTrue(executor.isEmpty())
    }

    @Test
    fun `stop discards pending progress and completes after delivery`() {
        val executor = QueuedExecutor()
        val dispatcher = PlaybackReportDispatcher(executor)
        val sent = mutableListOf<String>()
        var completed = false

        dispatcher.enqueue("session", PlaybackReportKind.PLAYING, { sent += "playing" })
        dispatcher.enqueue("session", PlaybackReportKind.PROGRESS, { sent += "progress" })
        dispatcher.enqueue(
            "session",
            PlaybackReportKind.STOPPED,
            { sent += "stopped" }
        ) { success -> completed = success }
        executor.runNext()

        assertEquals(listOf("playing", "stopped"), sent)
        assertTrue(completed)
    }

    @Test
    fun `authorization is rechecked when queued report reaches the executor`() {
        val executor = QueuedExecutor()
        val dispatcher = PlaybackReportDispatcher(executor)
        var authorized = true
        var sent = false
        var completed = true

        dispatcher.enqueue(
            "session",
            PlaybackReportKind.STOPPED,
            send = { sent = true },
            canSend = { authorized },
            onComplete = { completed = it },
        )
        authorized = false
        executor.runNext()

        assertFalse(sent)
        assertFalse(completed)
    }

    @Test
    fun `cancel all drops queued reports and fails completion`() {
        val executor = QueuedExecutor()
        val dispatcher = PlaybackReportDispatcher(executor)
        var sent = false
        var completed = true
        dispatcher.enqueue(
            "session",
            PlaybackReportKind.STOPPED,
            send = { sent = true },
            onComplete = { completed = it },
        )

        dispatcher.cancelAll()
        executor.runNext()

        assertFalse(sent)
        assertFalse(completed)
    }

    @Test
    fun `authorization cancellation is reported as unsuccessful`() {
        val executor = QueuedExecutor()
        val dispatcher = PlaybackReportDispatcher(executor)
        var completed = true
        dispatcher.enqueue(
            "session",
            PlaybackReportKind.STOPPED,
            send = { throw PlaybackReportCancelledException() },
            onComplete = { completed = it },
        )

        executor.runNext()

        assertFalse(completed)
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runNext() {
            tasks.removeFirst().run()
        }

        fun isEmpty(): Boolean = tasks.isEmpty()
    }
}
