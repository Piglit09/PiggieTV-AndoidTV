package com.piggie.tv.data.discovery

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryPageExecutorsTest {
    @Test fun idleWorkersTimeOutAndLaterRetryCreatesAWorker() {
        val executor = DiscoveryPageExecutors.create(25, TimeUnit.MILLISECONDS)
        val completed = AtomicInteger()
        try {
            val initialLoad = CountDownLatch(1)
            executor.execute {
                completed.incrementAndGet()
                initialLoad.countDown()
            }
            assertTrue("initial page work did not run", initialLoad.await(2, TimeUnit.SECONDS))
            assertTrue("idle discovery worker did not time out", awaitPoolSize(executor, 0))
            assertFalse("worker timeout must not shut down retry capability", executor.isShutdown)

            val retry = CountDownLatch(1)
            executor.execute {
                completed.incrementAndGet()
                retry.countDown()
            }
            assertTrue("retry did not recreate a discovery worker", retry.await(2, TimeUnit.SECONDS))
            assertEquals(2, completed.get())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun awaitPoolSize(executor: ThreadPoolExecutor, expected: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (executor.poolSize == expected) return true
            Thread.sleep(10)
        }
        return executor.poolSize == expected
    }
}
