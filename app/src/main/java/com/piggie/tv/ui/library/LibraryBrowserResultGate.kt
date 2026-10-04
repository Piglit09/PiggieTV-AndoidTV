package com.piggie.tv.ui.library

import java.util.concurrent.atomic.AtomicLong

/** Retires a queued browser result after Sort, Filter, Retry, or Activity teardown. */
class LibraryBrowserResultGate {
    private val current = AtomicLong()

    fun start(): Long = current.incrementAndGet()

    fun accepts(generation: Long): Boolean = current.get() == generation

    fun retire() {
        current.incrementAndGet()
    }
}
