package com.piggie.tv.data.music

import com.piggie.tv.data.models.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicAffinityAssemblerTest {
    @Test
    fun recentTrackResolvesArtistAndAlbumArtworkFallback() {
        val artist = item("artist", "Artist", "MusicArtist")
        val album = item("album", "Album", "MusicAlbum", imageTag = "album-art")
        val track = item(
            "track",
            "Track",
            "Audio",
            artistId = artist.id,
            albumId = album.id,
            artists = listOf(artist.title),
            playCount = 4,
            lastPlayedDate = "2026-07-28T12:00:00.0000000Z"
        )

        val result = MusicAffinityAssembler.assemble(
            recentTracks = listOf(track),
            frequentTracks = emptyList(),
            favoriteArtists = emptyList(),
            recentAlbums = listOf(album),
            catalogArtists = listOf(artist)
        )

        assertEquals("artist", result.selection?.candidate?.id)
        assertEquals(
            MusicAffinityReason.RECENTLY_PLAYED_ARTIST,
            result.selection?.reason
        )
        assertEquals("album", result.entries.first().fallbackArtworkItems.first().id)
    }

    @Test
    fun historyArtistStillBeatsNewAlbumAndUnrelatedCatalogArtist() {
        val listened = item("listened", "Listened", "MusicArtist")
        val unrelated = item("unrelated", "Unrelated", "MusicArtist")
        val newAlbum = item(
            "new-album",
            "New Album",
            "MusicAlbum",
            dateCreated = "2026-07-28T12:00:00Z"
        )
        val track = item(
            "track",
            "Track",
            "Audio",
            artistId = listened.id,
            artists = listOf(listened.title),
            lastPlayedDate = "2026-07-28T11:00:00Z"
        )

        val result = MusicAffinityAssembler.assemble(
            recentTracks = listOf(track),
            frequentTracks = emptyList(),
            favoriteArtists = emptyList(),
            recentAlbums = listOf(newAlbum),
            catalogArtists = listOf(unrelated, listened)
        )

        assertEquals("listened", result.entries.first().item.id)
        assertTrue(result.entries.none { it.item.id == unrelated.id })
    }

    @Test
    fun unplayedAudioWithoutUserDataEvidenceIsNotRecentHistory() {
        val listenedArtist = item("listened", "Listened", "MusicArtist")
        val unrelatedArtist = item("unrelated", "Unrelated", "MusicArtist")
        val unrelatedTrack = item(
            "unplayed-track",
            "Unplayed",
            "Audio",
            artistId = unrelatedArtist.id,
            artists = listOf(unrelatedArtist.title)
        )
        val listenedTrack = item(
            "listened-track",
            "Listened track",
            "Audio",
            artistId = listenedArtist.id,
            artists = listOf(listenedArtist.title),
            lastPlayedDate = "2026-07-28T12:00:00.0000000Z"
        )

        val result = MusicAffinityAssembler.assemble(
            recentTracks = listOf(unrelatedTrack, listenedTrack),
            frequentTracks = emptyList(),
            favoriteArtists = emptyList(),
            recentAlbums = emptyList(),
            catalogArtists = listOf(unrelatedArtist, listenedArtist)
        )

        assertEquals("listened", result.selection?.candidate?.id)
        assertEquals(1, result.recentTrackCount)
        assertTrue(result.entries.none { it.item.id == unrelatedArtist.id })
    }

    @Test
    fun missingRecentTimestampNeverSortsAheadOfRealTimestamp() {
        val undatedArtist = item("undated", "Undated", "MusicArtist")
        val datedArtist = item("dated", "Dated", "MusicArtist")
        val undatedPlayedTrack = item(
            "undated-track",
            "Undated track",
            "Audio",
            artistId = undatedArtist.id,
            artists = listOf(undatedArtist.title),
            playCount = 1
        )
        val datedTrack = item(
            "dated-track",
            "Dated track",
            "Audio",
            artistId = datedArtist.id,
            artists = listOf(datedArtist.title),
            lastPlayedDate = "2026-07-28T12:00:00Z"
        )

        val result = MusicAffinityAssembler.assemble(
            recentTracks = listOf(undatedPlayedTrack, datedTrack),
            frequentTracks = emptyList(),
            favoriteArtists = emptyList(),
            recentAlbums = emptyList(),
            catalogArtists = listOf(undatedArtist, datedArtist)
        )

        assertEquals("dated", result.selection?.candidate?.id)
        assertEquals(
            MusicAffinityReason.RECENTLY_PLAYED_ARTIST,
            result.selection?.reason
        )
    }

    @Test
    fun signalPoliciesRemainBounded() {
        val tracks = (0 until 120).map { index ->
            item(
                id = "track-$index",
                title = "Track $index",
                type = "Audio",
                playCount = 1
            )
        }

        assertEquals(
            MusicListeningSignalPolicy.MAX_SIGNAL_TRACKS,
            MusicListeningSignalPolicy.recent(tracks).size
        )
        assertEquals(
            MusicListeningSignalPolicy.MAX_SIGNAL_TRACKS,
            MusicListeningSignalPolicy.frequent(tracks).size
        )
    }

    @Test
    fun albumFallbacksUseEmbeddedTrackBeforeArtist() {
        val artist = item(
            "artist",
            "Artist",
            "MusicArtist",
            imageTag = "artist-art"
        )
        val album = item(
            "album",
            "Album",
            "MusicAlbum",
            artistId = artist.id
        )
        val track = item(
            "track",
            "Track",
            "Audio",
            imageTag = "embedded-track-art",
            artistId = artist.id,
            albumId = album.id,
            artists = listOf(artist.title),
            playCount = 1,
            lastPlayedDate = "2026-07-28T12:00:00Z"
        )

        val result = MusicAffinityAssembler.assemble(
            recentTracks = listOf(track),
            frequentTracks = emptyList(),
            favoriteArtists = emptyList(),
            recentAlbums = listOf(album),
            catalogArtists = listOf(artist)
        )
        val albumEntry = result.entries.first { it.item.id == album.id }

        assertEquals(
            listOf(track.id, artist.id),
            albumEntry.fallbackArtworkItems.take(2).map(MediaItem::id)
        )
    }

    private fun item(
        id: String,
        title: String,
        type: String,
        imageTag: String? = null,
        artistId: String? = null,
        albumId: String? = null,
        artists: List<String> = emptyList(),
        playCount: Int = 0,
        lastPlayedDate: String? = null,
        dateCreated: String? = null
    ) = MediaItem(
        id = id,
        title = title,
        type = type,
        year = null,
        imageTag = imageTag,
        seriesName = null,
        episodeLabel = null,
        playbackPositionTicks = 0,
        runtimeTicks = 0,
        artistId = artistId,
        albumId = albumId,
        artists = artists,
        playCount = playCount,
        lastPlayedDate = lastPlayedDate,
        dateCreated = dateCreated
    )
}
