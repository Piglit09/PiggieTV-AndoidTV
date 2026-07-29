package com.piggie.tv.ui.music

import com.piggie.tv.data.models.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicDetailsPoliciesTest {
    @Test
    fun artistArtworkUsesBackdropPrimaryAlbumThenBrandAndIsBounded() {
        val artist = item(
            id = "artist",
            type = "MusicArtist",
            imageTag = "artist-primary",
            backdropTag = "artist-backdrop"
        )
        val albums = (1..6).map {
            item("album-$it", "MusicAlbum", imageTag = "album-art-$it")
        }

        val candidates = MusicDetailsArtworkPolicy.candidates(
            item = artist,
            songs = emptyList(),
            relevantAlbums = albums
        )

        assertEquals(
            listOf(
                MusicArtworkSource.ARTIST_BACKDROP,
                MusicArtworkSource.ARTIST_PRIMARY,
                MusicArtworkSource.RELEVANT_ALBUM_PRIMARY,
                MusicArtworkSource.RELEVANT_ALBUM_PRIMARY,
                MusicArtworkSource.BRAND
            ),
            candidates.map(MusicArtworkCandidate::source)
        )
        assertEquals(
            MusicDetailsArtworkPolicy.MAX_REMOTE_ATTEMPTS,
            candidates.count(MusicArtworkCandidate::isRemote)
        )
    }

    @Test
    fun albumArtworkUsesAlbumTrackArtistThenLocalBrand() {
        val album = item("album", "MusicAlbum", imageTag = "album-primary")
        val track = item("track", "Audio", imageTag = "embedded-cover")
        val artist = item("artist", "MusicArtist", imageTag = "artist-primary")

        val candidates = MusicDetailsArtworkPolicy.candidates(
            item = album,
            songs = listOf(track),
            artist = artist
        )

        assertEquals(
            listOf(
                MusicArtworkSource.ALBUM_PRIMARY,
                MusicArtworkSource.EMBEDDED_TRACK_PRIMARY,
                MusicArtworkSource.ARTIST_PRIMARY,
                MusicArtworkSource.BRAND
            ),
            candidates.map(MusicArtworkCandidate::source)
        )
        assertTrue(candidates.last().imageType == MusicArtworkImageType.BRAND)
    }

    @Test
    fun relatedArtistsExcludeCurrentDeduplicateAndStopAtSixteen() {
        val candidates = buildList {
            add(item("current", "MusicArtist"))
            repeat(20) { add(item("artist-$it", "MusicArtist")) }
            add(item("artist-1", "MusicArtist"))
            add(item("album", "MusicAlbum"))
            add(item("song", "Audio"))
        }

        val result = MusicDetailsRelatedPolicy.filter(
            currentItemId = "current",
            currentItemType = "MusicArtist",
            candidates = candidates
        )

        assertEquals(16, result.size)
        assertEquals(16, result.map(MediaItem::id).distinct().size)
        assertTrue(result.none { it.id == "current" })
        assertTrue(result.all { it.type == "MusicArtist" })
        assertEquals("Similar Artists", MusicDetailsRelatedPolicy.title("MusicArtist"))
    }

    @Test
    fun albumRelatedShelfAcceptsNativeMusicDetailsDestinationsOnly() {
        val result = MusicDetailsRelatedPolicy.filter(
            currentItemId = "album-current",
            currentItemType = "MusicAlbum",
            candidates = listOf(
                item("album-current", "MusicAlbum"),
                item("album-other", "MusicAlbum"),
                item("artist", "MusicArtist"),
                item("playlist", "Playlist"),
                item("audio", "Audio"),
                item("movie", "Movie")
            )
        )

        assertEquals(
            listOf("album-other", "artist", "playlist"),
            result.map(MediaItem::id)
        )
        assertEquals("More From This Artist", MusicDetailsRelatedPolicy.title("MusicAlbum"))
    }

    @Test
    fun initialTrackRowsAndPlaybackQueueAreBounded() {
        val source = (0 until MusicDetailsTrackPolicy.INITIAL_LIMIT + 25).map {
            item("track-$it", "Audio")
        }

        val bounded = MusicDetailsTrackPolicy.initial(source)

        assertEquals(MusicDetailsTrackPolicy.INITIAL_LIMIT, bounded.size)
        assertEquals("track-0", bounded.first().id)
        assertEquals(
            "track-${MusicDetailsTrackPolicy.INITIAL_LIMIT - 1}",
            bounded.last().id
        )
    }

    private fun item(
        id: String,
        type: String,
        imageTag: String? = null,
        backdropTag: String? = null
    ) = MediaItem(
        id = id,
        title = id,
        type = type,
        year = null,
        imageTag = imageTag,
        backdropTag = backdropTag,
        seriesName = null,
        episodeLabel = null,
        playbackPositionTicks = 0,
        runtimeTicks = 0
    )
}
