package com.piggie.tv.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemePreset {
    PIGGIE_PURPLE,
    OLED_BLACK,
    DEEP_BLUE,
    CYBERPUNK
}

object PTVTheme {
    private val _current = MutableStateFlow(ThemePreset.PIGGIE_PURPLE)
    val current: StateFlow<ThemePreset> = _current

    fun set(preset: ThemePreset) {
        _current.value = preset
    }
}
