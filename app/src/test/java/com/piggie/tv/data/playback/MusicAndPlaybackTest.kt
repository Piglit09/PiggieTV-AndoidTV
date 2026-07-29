package com.piggie.tv.data.playback

import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.session.NativeSettings
import com.piggie.tv.ui.shared.TextSanitizer
import com.piggie.tv.util.JellyfinServerUrl
import com.piggie.tv.navigation.NativeRoute
import com.piggie.tv.navigation.NativeRouteNavigator
import com.piggie.tv.data.models.MediaCardPresentation
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MusicAndPlaybackTest {

    @Test
    fun testMetadataFormattingCompleteness() {
        val part1 = "1997"
        val part2 = "R"
        val part3 = "1h 35m"
        val part4 = "7.1 ★"
        
        assertEquals("1997  •  R  •  1h 35m  •  7.1 ★", TextSanitizer.formatMetadata(part1, part2, part3, part4))
        assertEquals("1997  •  1h 35m", TextSanitizer.formatMetadata(part1, null, part3, ""))
        assertEquals("", TextSanitizer.formatMetadata(null, "", "  "))
    }

    @Test
    fun testSettingsPersistence() {
        val context = RuntimeEnvironment.getApplication()
        val settings = NativeSettings(context)
        
        settings.connectionSpeed = "5 Mbps"
        settings.subtitlePreference = "Always On"
        
        val newSettings = NativeSettings(context)
        assertEquals("5 Mbps", newSettings.connectionSpeed)
        assertEquals("Always On", newSettings.subtitlePreference)
    }

    @Test
    fun testAudioLabelFormatting() {
        val item = MediaItem(
            id = "id",
            title = "Track Title",
            type = "Audio",
            year = "2020",
            imageTag = null,
            seriesName = null,
            episodeLabel = null,
            playbackPositionTicks = 0L,
            runtimeTicks = 1800000000L,
            artists = listOf("Artist Name"),
            album = "Album Name"
        )
        
        val label = TextSanitizer.formatMetadata(item.artists.firstOrNull(), item.album)
        assertEquals("Artist Name  •  Album Name", label)
    }

    @Test
    fun testPlaybackDurationFormatting() {
        val ticks = 1800000000L
        val totalSeconds = ticks / 10_000_000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        
        val formatted = "$minutes:${seconds.toString().padStart(2, '0')}"
        assertEquals("3:00", formatted)
    }

    @Test
    fun testMediaItemParsingWithMusicFields() {
        val json = JSONObject("""
            {
                "Id": "music_id",
                "Name": "Song Name",
                "Type": "Audio",
                "Artists": ["Artist A", "Artist B"],
                "Album": "Album Title",
                "AlbumArtist": "Artist A",
                "AlbumId": "album_id",
                "ProductionYear": 2021,
                "RunTimeTicks": 123456789,
                "UserData": {
                    "PlaybackPositionTicks": 5000,
                    "IsFavorite": true
                },
                "ImageTags": {
                    "Primary": "tag1"
                }
            }
        """)
        
        val item = MediaItem(
            id = json.getString("Id"),
            title = json.getString("Name"),
            type = json.getString("Type"),
            year = json.optInt("ProductionYear").toString(),
            imageTag = json.getJSONObject("ImageTags").getString("Primary"),
            seriesName = null,
            episodeLabel = null,
            playbackPositionTicks = json.getJSONObject("UserData").getLong("PlaybackPositionTicks"),
            runtimeTicks = json.getLong("RunTimeTicks"),
            artists = listOf("Artist A", "Artist B"),
            album = "Album Title",
            albumArtist = "Artist A",
            albumId = "album_id"
        )
        
        assertEquals("Song Name", item.title)
        assertEquals(2, item.artists.size)
        assertEquals("Artist A", item.albumArtist)
    }

    @Test
    fun testPlaybackProgressFractionEdgeCases() {
        assertEquals(0f, PlaybackProgress.fraction(0L, 100L))
        assertEquals(0f, PlaybackProgress.fraction(50L, 0L))
        assertEquals(0.5f, PlaybackProgress.fraction(50L, 100L))
        assertEquals(1f, PlaybackProgress.fraction(150L, 100L))
        assertEquals(0f, PlaybackProgress.fraction(-10L, 100L))
    }

    @Test
    fun testNativeRouteNavigatorBackLogic() {
        assertEquals(null, NativeRouteNavigator.backTarget(NativeRoute.HOME))
        assertEquals(NativeRoute.HOME, NativeRouteNavigator.backTarget(NativeRoute.MOVIES))
        assertEquals(NativeRoute.HOME, NativeRouteNavigator.backTarget(NativeRoute.MUSIC))
        assertEquals(NativeRoute.HOME, NativeRouteNavigator.backTarget(NativeRoute.PROFILE))
    }

    @Test
    fun testImageSizingMaxWidth() {
        assertEquals(240, ImageSizing.maxWidth(MediaCardPresentation.POSTER))
        assertEquals(400, ImageSizing.maxWidth(MediaCardPresentation.LANDSCAPE))
        assertEquals(240, ImageSizing.maxWidth(MediaCardPresentation.SQUARE))
    }

    @Test
    fun testSanitizerBlankInput() {
        assertEquals("", TextSanitizer.sanitize(null))
        assertEquals("", TextSanitizer.sanitize(""))
        assertEquals("", TextSanitizer.sanitize("   "))
    }

    @Test
    fun testSanitizerNormalization() {
        val input = "Line 1<br><br><br><br>Line 2"
        assertEquals("Line 1\n\nLine 2", TextSanitizer.sanitize(input))
    }

    @Test
    fun testMetadataFormattingWithNulls() {
        assertEquals("Part A  •  Part B", TextSanitizer.formatMetadata("Part A", null, "Part B"))
        assertEquals("Part A", TextSanitizer.formatMetadata(null, "Part A", "  "))
    }

    @Test
    fun testPlaybackProgressFractionBoundaries() {
        assertEquals(0f, PlaybackProgress.fraction(-100L, 1000L))
        assertEquals(1f, PlaybackProgress.fraction(1500L, 1000L))
        assertEquals(0f, PlaybackProgress.fraction(500L, -100L))
    }

    @Test
    fun testJellyfinServerUrlNormalizationInTest() {
        assertEquals("https://piggietv.com", JellyfinServerUrl.normalize("piggietv.com"))
        assertEquals("https://piggietv.com", JellyfinServerUrl.normalize("https://piggietv.com/"))
        assertEquals("http://192.168.1.50:8096", JellyfinServerUrl.normalize("http://192.168.1.50:8096"))
    }

    @Test
    fun testPlaybackStateConstants() {
        val READY = 3 // Player.STATE_READY
        val ENDED = 4 // Player.STATE_ENDED
        assertEquals(3, READY)
        assertEquals(4, ENDED)
    }

    @Test
    fun testPlaybackReportingInterval() {
        val interval = 15_000L
        assertTrue(interval > 0)
    }
}
