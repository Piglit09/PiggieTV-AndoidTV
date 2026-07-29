package com.piggie.tv.data.reader

import android.graphics.Bitmap

data class ReaderPage(
    val index: Int,
    val displayLabel: String,
    val width: Int = 0,
    val height: Int = 0,
    val isLoaded: Boolean = false,
    val error: String? = null
)
