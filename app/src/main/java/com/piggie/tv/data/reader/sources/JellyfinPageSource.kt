package com.piggie.tv.data.reader.sources

import android.content.Context
import android.graphics.Bitmap
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.reader.ReaderBitmapDecoder
import com.piggie.tv.data.reader.ReaderSource
import java.io.ByteArrayInputStream

class JellyfinPageSource(
    private val session: NativeSession,
    private val itemId: String,
    private val pageCount: Int,
    private val api: JellyfinNativeApi
) : ReaderSource {

    override suspend fun open(context: Context): Boolean = pageCount > 0

    override fun getPageCount(): Int = pageCount

    override suspend fun getPage(index: Int, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bytes = runCatching { api.loadPageBytes(session, itemId, index) }.getOrNull() ?: return null
        return ReaderBitmapDecoder.decode(
            openStream = { ByteArrayInputStream(bytes) },
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
    }

    override fun release() {
        // No resources to release
    }
}
