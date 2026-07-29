package com.piggie.tv.data.reader

data class ReaderProgress(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val lastPageIndex: Int,
    val pageCount: Int,
    val lastReadTimestamp: Long = System.currentTimeMillis(),
    val isRtl: Boolean = false
)
