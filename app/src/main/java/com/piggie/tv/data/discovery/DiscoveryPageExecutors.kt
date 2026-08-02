package com.piggie.tv.data.discovery

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Keeps each page load bounded to two concurrent shelves without retaining idle worker threads.
 *
 * The executor itself remains usable for a later manual retry. Timed-out core workers are created
 * again automatically by [ThreadPoolExecutor] when retry work is submitted.
 */
internal object DiscoveryPageExecutors {
    private const val WORKER_COUNT = 2
    private const val IDLE_KEEP_ALIVE_SECONDS = 5L

    fun create(
        idleKeepAlive: Long = IDLE_KEEP_ALIVE_SECONDS,
        unit: TimeUnit = TimeUnit.SECONDS
    ): ThreadPoolExecutor = ThreadPoolExecutor(
        WORKER_COUNT,
        WORKER_COUNT,
        idleKeepAlive,
        unit,
        LinkedBlockingQueue()
    ).apply {
        allowCoreThreadTimeOut(true)
    }
}
