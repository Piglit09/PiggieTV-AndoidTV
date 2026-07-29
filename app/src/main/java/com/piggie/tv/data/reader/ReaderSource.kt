package com.piggie.tv.data.reader

import android.content.Context
import android.graphics.Bitmap

interface ReaderSource {
    suspend fun open(context: Context): Boolean
    fun getPageCount(): Int
    suspend fun getPage(index: Int, targetWidth: Int, targetHeight: Int): Bitmap?
    fun release()
}
