package com.piggie.tv.memory

/** A visible screen can release optional state while preserving its essential interaction state. */
interface MemoryPressureParticipant {
    fun onMemoryPressure(level: Int)
}
