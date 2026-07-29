package com.piggie.tv.data.reader

import android.content.Context
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.util.PTVLog
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class ReaderRepository(
    private val context: Context,
    private val api: JellyfinNativeApi,
    private val cache: ReaderCache
) {

    suspend fun getDocument(session: NativeSession, item: MediaItem): ReaderDocument {
        PTVLog.d("Reader document requested item=${PTVLog.mask(item.id)}")

        // 1. Try local cache first
        val cachedFile = cache.getFile(session.serverId, session.userId, item.id)

        if (!cachedFile.exists()) {
            PTVLog.d("Reader document cache miss; downloading")
            download(session, item.id, cachedFile)
        } else {
            PTVLog.d("Reader document cache hit bytes=${cachedFile.length()}")
        }

        // 3. Detect format
        val format = detectFormat(cachedFile, item)
        PTVLog.d("Reader document format=$format")

        return ReaderDocument(
            itemId = item.id,
            title = item.title,
            format = format,
            pageCount = item.pageCount
        )
    }

    private fun download(session: NativeSession, itemId: String, target: File) {
        val temp = File(target.absolutePath + ".tmp")
        api.downloadFile(session, itemId) { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output)
            }
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw java.io.IOException("Failed to save downloaded file")
        }
        cache.evictLru()
    }

    private fun detectFormat(file: File, item: MediaItem): ReaderFormat {
        PTVLog.d("Reader format detection item=${PTVLog.mask(item.id)} bytes=${file.length()}")
        if (!file.exists() || file.length() < 4) {
            PTVLog.w("Reader document does not exist or is too small")
            return ReaderFormat.UNKNOWN
        }

        val bytes = try {
            file.inputStream().use { input ->
                val b = ByteArray(4)
                input.read(b)
                b
            }
        } catch (e: Exception) {
            PTVLog.e("Reader document header read failed", e)
            ByteArray(4)
        }

        val isZip = bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        val isPdf = bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte()

        val extension = file.extension.lowercase()
        val container = item.container?.lowercase() ?: ""

        PTVLog.d("Reader detection isZip=$isZip isPdf=$isPdf ext=$extension container=$container")

        val isEpubZip = isZip && runCatching {
            ZipFile(file).use { zip ->
                zip.getEntry("mimetype")?.let { entry ->
                    zip.getInputStream(entry).bufferedReader().use { it.readText().trim() } == "application/epub+zip"
                } == true
            }
        }.getOrDefault(false)

        return when {
            isPdf || extension == "pdf" || container == "pdf" -> ReaderFormat.PDF
            isEpubZip || extension == "epub" || container == "epub" -> ReaderFormat.EPUB
            isZip || extension == "cbz" || container == "cbz" || extension == "zip" || container == "zip" -> ReaderFormat.CBZ
            extension == "cbr" || container == "cbr" -> ReaderFormat.CBR
            item.pageCount > 0 -> ReaderFormat.JELLYFIN_PAGES
            else -> ReaderFormat.UNKNOWN
        }
    }

    fun getSource(session: NativeSession, item: MediaItem, format: ReaderFormat): ReaderSource {
        val file = cache.getFile(session.serverId, session.userId, item.id)
        return when (format) {
            ReaderFormat.CBZ -> com.piggie.tv.data.reader.sources.CbzReaderSource(file)
            ReaderFormat.PDF -> com.piggie.tv.data.reader.sources.PdfReaderSource(file)
            ReaderFormat.JELLYFIN_PAGES -> com.piggie.tv.data.reader.sources.JellyfinPageSource(session, item.id, item.pageCount, api)
            else -> throw IllegalArgumentException("Unsupported format for native reading: $format")
        }
    }
}
