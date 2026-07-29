package com.piggie.tv.data.reader

import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ReaderRepositoryFormatTest {
    @Test fun epubZipMimetypeIsUnsupportedEpubInsteadOfCbz() {
        val context = RuntimeEnvironment.getApplication()
        val repository = ReaderRepository(context, JellyfinNativeApi(context), ReaderCache(context))
        val fixture = File.createTempFile("reader-format-", ".bin").apply { deleteOnExit() }
        ZipOutputStream(fixture.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("OPS/chapter.xhtml"))
            zip.write("<html/>".toByteArray())
            zip.closeEntry()
        }
        val item = MediaItem(
            id = "fixture",
            title = "EPUB fixture",
            type = "Book",
            year = null,
            imageTag = null,
            seriesName = null,
            episodeLabel = null,
            playbackPositionTicks = 0,
            runtimeTicks = 0,
            container = "zip"
        )
        val method = ReaderRepository::class.java.getDeclaredMethod(
            "detectFormat",
            File::class.java,
            MediaItem::class.java
        ).apply { isAccessible = true }

        assertEquals(ReaderFormat.EPUB, method.invoke(repository, fixture, item))
    }
}
