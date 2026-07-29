package com.piggie.tv.ui.state

sealed class NonBlankUiState {
    data object Loading : NonBlankUiState()
    data object Content : NonBlankUiState()
    data object Empty : NonBlankUiState()
    data class Unsupported(val reason: String) : NonBlankUiState()
    data class Error(val message: String, val retryable: Boolean) : NonBlankUiState()
    data class TimedOut(val message: String = "Loading timed out", val retryable: Boolean = true) : NonBlankUiState()
}

object NonBlankStatePolicy {
    fun watchdog(state: NonBlankUiState, loadingForMs: Long, thresholdMs: Long): NonBlankUiState =
        if (state == NonBlankUiState.Loading && loadingForMs >= thresholdMs) {
            NonBlankUiState.TimedOut()
        } else {
            state
        }
}
