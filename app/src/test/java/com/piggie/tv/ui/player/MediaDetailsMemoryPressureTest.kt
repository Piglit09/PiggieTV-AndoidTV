package com.piggie.tv.ui.player

import com.piggie.tv.memory.MemoryPressurePolicy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaDetailsMemoryPressureTest {
    @Test
    fun completePressureBeforeViewCreationIsIgnoredSafely() {
        val fragment = MediaDetailsFragment()

        fragment.onMemoryPressure(MemoryPressurePolicy.COMPLETE)
    }
}
