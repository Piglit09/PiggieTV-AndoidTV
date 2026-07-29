package com.piggie.tv.data.reader.sources

import android.content.Context
import android.graphics.Bitmap
import com.piggie.tv.data.reader.ReaderBitmapDecoder
import com.piggie.tv.data.reader.ReaderSource
import com.piggie.tv.util.NaturalOrderComparator
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class CbzReaderSource(private val file: File) : ReaderSource {
    private var zipFile: ZipFile? = null
    private var pages: List<ZipEntry> = emptyList()

    override suspend fun open(context: Context): Boolean {
        return try {
            zipFile = ZipFile(file)
            pages = zipFile?.entries()?.asSequence()
                ?.filter { !it.isDirectory && isImage(it.name) }
                ?.sortedWith { e1, e2 -> NaturalOrderComparator.compare(e1.name, e2.name) }
                ?.toList() ?: emptyList()
            pages.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    override fun getPageCount(): Int = pages.size

    override suspend fun getPage(index: Int, targetWidth: Int, targetHeight: Int): Bitmap? {
        if (index !in pages.indices) return null
        val entry = pages[index]
        return ReaderBitmapDecoder.decode(
            openStream = { zipFile?.getInputStream(entry) },
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
    }

    override fun release() {
        zipFile?.close()
        zipFile = null
        pages = emptyList()
    }

    private fun isImage(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    }
}
