package com.piggie.tv.data.reader

import android.content.Context
import java.io.File

class ReaderCache(private val context: Context) {
    private val cacheDir = File(context.cacheDir, "reader_cache")
    private val maxCacheSize = 500 * 1024 * 1024L // 500MB

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
    }

    fun getFile(serverId: String, userId: String, itemId: String): File {
        val userDir = File(cacheDir, "${serverId}_$userId")
        if (!userDir.exists()) userDir.mkdirs()
        return File(userDir, itemId)
    }

    fun evictLru() {
        val files = cacheDir.walk().filter { it.isFile }.toList().sortedBy { it.lastModified() }
        var currentSize = files.sumOf { it.length() }

        for (file in files) {
            if (currentSize <= maxCacheSize) break
            currentSize -= file.length()
            file.delete()
        }
    }

    fun clear() {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }
}
