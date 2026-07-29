package com.piggie.tv.data.models

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReadingMetadataTest {

    @Test
    fun testBookItemProperties() {
        val item = MediaItem(
            id = "b1",
            title = "The Great Gatsby",
            type = "Book",
            year = "1925",
            imageTag = "tag",
            seriesName = "Classic Collection",
            episodeLabel = "Volume 1",
            playbackPositionTicks = 1000L,
            runtimeTicks = 5000L,
            pageCount = 218
        )
        
        assertEquals(218, item.pageCount)
        assertEquals("Book", item.type)
        assertEquals("Classic Collection", item.seriesName)
    }

    @Test
    fun testMediaCardPresentationForBooks() {
        val book = MediaItem(id = "1", title = "B", type = "Book", year = null, imageTag = null, seriesName = null, episodeLabel = null, playbackPositionTicks = 0, runtimeTicks = 0)
        val comic = MediaItem(id = "2", title = "C", type = "Book", year = null, imageTag = null, seriesName = "Comic Series", episodeLabel = "Issue 1", playbackPositionTicks = 0, runtimeTicks = 0)
        
        assertEquals(MediaCardPresentation.POSTER, MediaCardPresentationSelector.forItem(book, "Recently added books"))
        assertEquals(MediaCardPresentation.POSTER, MediaCardPresentationSelector.forItem(comic, "Continue reading"))
    }

    @Test
    fun testPageImageUrlConstruction() {
        val session = NativeSession("t", "s", "u", "n", "https://ptv.io")
        val url = "https://ptv.io/Items/book123/Images/Page/5"
        val api = com.piggie.tv.data.api.JellyfinNativeApi(RuntimeEnvironment.getApplication())
        assertEquals(url, api.getPageImageUrl(session, "book123", 5))
    }
}
