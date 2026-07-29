package com.piggie.tv.data.music

import com.piggie.tv.data.models.MediaItem
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class MusicHeroEntry(
    val item: MediaItem,
    val affinity: MusicAffinityCandidate,
    val fallbackArtworkItems: List<MediaItem> = emptyList()
)

data class MusicHeroAffinityResult(
    val entries: List<MusicHeroEntry>,
    val selection: MusicAffinitySelection?,
    val recentTrackCount: Int,
    val frequentTrackCount: Int,
    val resolvedArtistCount: Int,
    val resolvedAlbumCount: Int
)

/**
 * Keeps the listening-history inputs both meaningful and bounded.
 *
 * Sorting Jellyfin's complete Audio library by DatePlayed does not guarantee that every returned
 * item was played. UserData is the authority: a timestamp is the strongest signal, while a
 * positive play count or completed item is a bounded compatibility fallback for servers that omit
 * LastPlayedDate.
 */
object MusicListeningSignalPolicy {
    const val MAX_SIGNAL_TRACKS = 96

    fun hasRecentPlaySignal(item: MediaItem): Boolean =
        !item.lastPlayedDate.isNullOrBlank() || item.playCount > 0 || item.isPlayed

    fun recent(items: List<MediaItem>, limit: Int = MAX_SIGNAL_TRACKS): List<MediaItem> =
        items.asSequence()
            .filter { it.type == "Audio" && hasRecentPlaySignal(it) }
            .take(limit.coerceIn(1, MAX_SIGNAL_TRACKS))
            .toList()

    fun frequent(items: List<MediaItem>, limit: Int = MAX_SIGNAL_TRACKS): List<MediaItem> =
        items.asSequence()
            .filter { it.type == "Audio" && (it.playCount > 0 || it.isPlayed) }
            .take(limit.coerceIn(1, MAX_SIGNAL_TRACKS))
            .toList()
}

/**
 * Converts bounded Jellyfin history/catalog responses into the explicit affinity tiers used by
 * [MusicAffinityScorer]. It never invents an unrelated artist when listening history is present.
 */
object MusicAffinityAssembler {
    fun assemble(
        recentTracks: List<MediaItem>,
        frequentTracks: List<MediaItem>,
        favoriteArtists: List<MediaItem>,
        recentAlbums: List<MediaItem>,
        catalogArtists: List<MediaItem>,
        resolvedItems: List<MediaItem> = emptyList()
    ): MusicHeroAffinityResult {
        val boundedRecentTracks = MusicListeningSignalPolicy.recent(recentTracks)
        val boundedFrequentTracks = MusicListeningSignalPolicy.frequent(frequentTracks)
        val artists = (catalogArtists + favoriteArtists + resolvedItems)
            .filter { it.type == "MusicArtist" }
            .distinctBy(MediaItem::id)
        val albums = (recentAlbums + resolvedItems)
            .filter { it.type == "MusicAlbum" }
            .distinctBy(MediaItem::id)
        val artistById = artists.associateBy(MediaItem::id)
        val artistByName = artists.associateBy { normalize(it.title) }
        val albumById = albums.associateBy(MediaItem::id)
        val albumByName = albums.associateBy { normalize(it.title) }
        val artworkTracksByAlbum = (boundedRecentTracks + boundedFrequentTracks)
            .asSequence()
            .filter { !it.albumId.isNullOrBlank() && !it.imageTag.isNullOrBlank() }
            .distinctBy(MediaItem::id)
            .groupBy { requireNotNull(it.albumId) }
        val models = linkedMapOf<String, MusicAffinityCandidate>()
        val mediaById = linkedMapOf<String, MediaItem>()
        val albumFallbacksByArtist = linkedMapOf<String, MutableList<MediaItem>>()

        fun resolveArtist(track: MediaItem): MediaItem? =
            track.artistId?.let(artistById::get)
                ?: track.artists.firstOrNull()?.let { artistByName[normalize(it)] }
                ?: track.albumArtist?.let { artistByName[normalize(it)] }

        fun resolveAlbum(track: MediaItem): MediaItem? =
            track.albumId?.let(albumById::get)
                ?: track.album?.let { albumByName[normalize(it)] }

        fun merge(candidate: MusicAffinityCandidate, media: MediaItem) {
            val existing = models[candidate.id]
            models[candidate.id] = if (existing == null) {
                candidate
            } else {
                existing.copy(
                    playCount = maxOf(existing.playCount, candidate.playCount),
                    recentArtistPlays =
                        maxOf(existing.recentArtistPlays, candidate.recentArtistPlays),
                    recentAlbumPlays =
                        maxOf(existing.recentAlbumPlays, candidate.recentAlbumPlays),
                    completedTracks =
                        maxOf(existing.completedTracks, candidate.completedTracks),
                    isFavorite = existing.isFavorite || candidate.isFavorite,
                    lastPlayedAtMs = listOfNotNull(
                        existing.lastPlayedAtMs,
                        candidate.lastPlayedAtMs
                    ).maxOrNull(),
                    addedAtMs = listOfNotNull(
                        existing.addedAtMs,
                        candidate.addedAtMs
                    ).maxOrNull()
                )
            }
            mediaById[candidate.id] = media
        }

        boundedRecentTracks
            .mapIndexedNotNull { index, track ->
                resolveArtist(track)?.let { Triple(index, track, it) }
            }
            .groupBy { it.third.id }
            .values
            .forEach { observations ->
                val artist = observations.first().third
                val tracks = observations.map { it.second }
                merge(
                    MusicAffinityCandidate(
                        id = artist.id,
                        title = artist.title,
                        kind = MusicAffinityKind.ARTIST,
                        playCount = tracks.sumOf { it.playCount.coerceAtLeast(0) },
                        recentArtistPlays = tracks.size,
                        completedTracks = tracks.count(MediaItem::isPlayed),
                        isFavorite = artist.isFavorite,
                        lastPlayedAtMs = observations.maxOf {
                            orderingTimestamp(it.second.lastPlayedDate, it.first)
                        },
                        addedAtMs = parseTimestamp(artist.dateCreated)
                    ),
                    artist
                )
                observations.mapNotNull { resolveAlbum(it.second) }
                    .distinctBy(MediaItem::id)
                    .forEach {
                        albumFallbacksByArtist
                            .getOrPut(artist.id) { mutableListOf() }
                            .add(it)
                    }
            }

        boundedRecentTracks
            .mapIndexedNotNull { index, track ->
                resolveAlbum(track)?.let { Triple(index, track, it) }
            }
            .groupBy { it.third.id }
            .values
            .forEach { observations ->
                val album = observations.first().third
                val tracks = observations.map { it.second }
                merge(
                    MusicAffinityCandidate(
                        id = album.id,
                        title = album.title,
                        kind = MusicAffinityKind.ALBUM,
                        playCount = tracks.sumOf { it.playCount.coerceAtLeast(0) },
                        recentAlbumPlays = tracks.size,
                        completedTracks = tracks.count(MediaItem::isPlayed),
                        isFavorite = album.isFavorite,
                        lastPlayedAtMs = observations.maxOf {
                            orderingTimestamp(it.second.lastPlayedDate, it.first)
                        },
                        addedAtMs = parseTimestamp(album.dateCreated)
                    ),
                    album
                )
            }

        boundedFrequentTracks
            .mapNotNull { track -> resolveArtist(track)?.let { track to it } }
            .groupBy { it.second.id }
            .values
            .forEach { observations ->
                val artist = observations.first().second
                val tracks = observations.map { it.first }
                merge(
                    MusicAffinityCandidate(
                        id = artist.id,
                        title = artist.title,
                        kind = MusicAffinityKind.ARTIST,
                        playCount = tracks.sumOf {
                            it.playCount.coerceAtLeast(if (it.isPlayed) 1 else 0)
                        },
                        completedTracks = tracks.count(MediaItem::isPlayed),
                        isFavorite = artist.isFavorite,
                        lastPlayedAtMs = tracks.mapNotNull {
                            parseTimestamp(it.lastPlayedDate)
                        }.maxOrNull(),
                        addedAtMs = parseTimestamp(artist.dateCreated)
                    ),
                    artist
                )
            }

        favoriteArtists.forEach { artist ->
            merge(
                MusicAffinityCandidate(
                    id = artist.id,
                    title = artist.title,
                    kind = MusicAffinityKind.ARTIST,
                    playCount = artist.playCount,
                    isFavorite = true,
                    lastPlayedAtMs = parseTimestamp(artist.lastPlayedDate),
                    addedAtMs = parseTimestamp(artist.dateCreated)
                ),
                artist
            )
        }

        recentAlbums.forEachIndexed { index, album ->
            merge(
                MusicAffinityCandidate(
                    id = album.id,
                    title = album.title,
                    kind = MusicAffinityKind.ALBUM,
                    playCount = album.playCount,
                    isFavorite = album.isFavorite,
                    lastPlayedAtMs = parseTimestamp(album.lastPlayedDate),
                    addedAtMs =
                        parseTimestamp(album.dateCreated)
                            ?: orderingFallback(index)
                ),
                album
            )
        }

        val selection = MusicAffinityScorer.select(models.values.toList())
        val orderedModels = models.values
            .filter(::isRelevant)
            .sortedWith(
                compareBy<MusicAffinityCandidate> { tier(it) }
                    .thenByDescending { affinityStrength(it) }
                    .thenBy { it.id }
            )
            .let { ordered ->
                val selectedId = selection?.candidate?.id
                if (selectedId == null) ordered
                else ordered.sortedBy { if (it.id == selectedId) 0 else 1 }
            }
        val entries = orderedModels.mapNotNull { model ->
            mediaById[model.id]?.let { item ->
                val fallbacks = when (model.kind) {
                    MusicAffinityKind.ARTIST ->
                        albumFallbacksByArtist[model.id]
                            .orEmpty()
                            .distinctBy(MediaItem::id)
                            .take(3)
                    MusicAffinityKind.ALBUM -> buildList {
                        addAll(artworkTracksByAlbum[model.id].orEmpty().take(2))
                        addAll(
                            artists
                                .filter { artist ->
                                    item.artistId == artist.id ||
                                        item.albumArtist?.equals(
                                            artist.title,
                                            ignoreCase = true
                                        ) == true ||
                                        item.artists.any {
                                            it.equals(artist.title, ignoreCase = true)
                                        }
                                }
                                .take(2)
                        )
                    }.distinctBy(MediaItem::id)
                }
                MusicHeroEntry(item, model, fallbacks)
            }
        }

        return MusicHeroAffinityResult(
            entries = entries,
            selection = selection,
            recentTrackCount = boundedRecentTracks.size,
            frequentTrackCount = boundedFrequentTracks.size,
            resolvedArtistCount = artists.size,
            resolvedAlbumCount = albums.size
        )
    }

    private fun isRelevant(candidate: MusicAffinityCandidate): Boolean =
        candidate.recentArtistPlays > 0 ||
            candidate.playCount > 0 ||
            candidate.completedTracks > 0 ||
            candidate.recentAlbumPlays > 0 ||
            candidate.isFavorite ||
            candidate.addedAtMs != null

    private fun tier(candidate: MusicAffinityCandidate): Int = when {
        candidate.kind == MusicAffinityKind.ARTIST &&
            candidate.recentArtistPlays > 0 -> 0
        candidate.kind == MusicAffinityKind.ARTIST &&
            (candidate.playCount > 0 || candidate.completedTracks > 0) -> 1
        candidate.kind == MusicAffinityKind.ALBUM &&
            candidate.recentAlbumPlays > 0 -> 2
        candidate.kind == MusicAffinityKind.ARTIST && candidate.isFavorite -> 3
        else -> 4
    }

    private fun affinityStrength(candidate: MusicAffinityCandidate): Long =
        candidate.lastPlayedAtMs
            ?: candidate.addedAtMs
            ?: candidate.playCount.toLong() * 10L +
                candidate.completedTracks.toLong() * 3L

    private fun orderingTimestamp(value: String?, index: Int): Long =
        parseTimestamp(value) ?: orderingFallback(index)

    /**
     * Unknown dates retain stable server order but always sort below any real Unix timestamp.
     */
    private fun orderingFallback(index: Int): Long = -(index.toLong() + 1L)

    private fun parseTimestamp(value: String?): Long? {
        value ?: return null
        val normalized = value
            .replace(Regex("(\\.\\d{3})\\d+"), "$1")
            .let { timestamp ->
                if (timestamp.contains('.')) timestamp
                else timestamp.replace("Z", ".000Z")
            }
        return runCatching {
            SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSSX",
                Locale.US
            ).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(normalized)?.time
        }.getOrNull()
    }

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT)
}
