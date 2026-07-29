package com.piggie.tv.util

import android.util.Log
import com.piggie.tv.BuildConfig

object PTVLog {
    private const val TAG = "PTV"

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
    }

    /**
     * Logs a sensitive identifier after masking.
     */
    fun sensitive(label: String, value: String?) {
        if (BuildConfig.DEBUG) {
            val masked = if (value == null) "null" else if (value.length <= 8) "****" else value.take(4) + "..." + value.takeLast(4)
            Log.d(TAG, "$label: $masked")
        }
    }

    /**
     * Redacts URL query parameters for logging.
     */
    fun redactUrl(url: String): String {
        return url.substringBefore('?')
    }

    fun mask(value: String?): String = when {
        value.isNullOrBlank() -> "none"
        value.length <= 8 -> "****"
        else -> value.take(4) + "…" + value.takeLast(4)
    }
}
