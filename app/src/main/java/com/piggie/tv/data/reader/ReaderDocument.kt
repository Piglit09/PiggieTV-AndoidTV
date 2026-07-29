package com.piggie.tv.data.reader

data class ReaderDocument(
    val itemId: String,
    val title: String,
    val format: ReaderFormat,
    val pageCount: Int,
    val supportsZoom: Boolean = true,
    val supportsTextSettings: Boolean = false
)
