package com.piggie.tv.data.reader.sources

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.piggie.tv.data.reader.ReaderSource
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.min

class PdfReaderSource(private val file: File) : ReaderSource {
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null

    override suspend fun open(context: Context): Boolean {
        return try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd!!)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun getPageCount(): Int = renderer?.pageCount ?: 0

    override suspend fun getPage(index: Int, targetWidth: Int, targetHeight: Int): Bitmap? {
        val page = renderer?.openPage(index) ?: return null
        return try {
            val scale = min(
                targetWidth.coerceAtLeast(1).toFloat() / page.width,
                targetHeight.coerceAtLeast(1).toFloat() / page.height
            ).coerceAtMost(2f)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } finally {
            page.close()
        }
    }

    override fun release() {
        renderer?.close()
        pfd?.close()
        renderer = null
        pfd = null
    }
}
