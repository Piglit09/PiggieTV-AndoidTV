package com.piggie.tv.data.api

import android.content.Context
import android.os.Build
import android.util.Log
import com.piggie.tv.data.models.*
import com.piggie.tv.data.playback.ImageSizing
import com.piggie.tv.data.playback.PlaybackProgress
import com.piggie.tv.util.JellyfinServerUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import kotlin.concurrent.thread

private const val LOG_TAG = "JellyfinApi"

data class ServerInfo(val name: String, val version: String)
data class QuickConnectTicket(val secret: String, val code: String)

data class NativeDiagnostics(
    val serverUrl: String,
    val serverId: String,
    val userId: String,
    val appVersion: String,
    val androidVersion: String,
    val device: String,
    val displayMetrics: String? = null,
    val lastApiError: String?,
    val lastNetworkDetails: String?,
    val lastSuccessAt: Long?,
    val sessionOrigin: SessionOrigin
)

enum class SessionOrigin { STORED, QUICK_CONNECT, PASSWORD }

class HttpRequestFailure(val statusCode: Int, val responseBody: String) : IOException("HTTP " + statusCode)

class JellyfinNativeApi(private val context: Context) {
    @Volatile private var lastApiError: String? = null
    @Volatile private var lastSuccessAt: Long? = null
    private val deviceId: String by lazy {
        val prefs = context.getSharedPreferences("ptv_native_device", Context.MODE_PRIVATE)
        prefs.getString("id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
    }
    private val transport by lazy { NativeHttpTransport(::authorization) }

    fun ensureDeviceId(): String {
        return deviceId.also { id ->
            Log.d(LOG_TAG, "Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (API " + Build.VERSION.SDK_INT + ")")
            Log.d(LOG_TAG, "Device ID confirmed: " + maskIdentifier(id))
        }
    }

    fun validateServer(server: String): ServerInfo {
        val endpoint = JellyfinServerUrl.normalize(server) + "/System/Info/Public"
        val result = JSONObject(request(endpoint))
        return ServerInfo(result.optString("ServerName"), result.optString("Version"))
    }

    fun quickConnectEnabled(server: String): Boolean {
        val endpoint = JellyfinServerUrl.normalize(server) + "/QuickConnect/Enabled"
        return runCatching { request(endpoint) }.getOrNull()?.toBoolean() ?: false
    }

    fun authenticateWithPassword(server: String, username: String, password: String): NativeSession {
        ensureDeviceId()
        val payload = JSONObject().apply {
            put("Username", username)
            put("Pw", password)
        }
        
        logRequestStructure("AuthenticateByName", payload)
        
        return authenticate(server, payload, "/Users/AuthenticateByName", username)
    }

    private fun logRequestStructure(tag: String, payload: JSONObject) {
        val keys = mutableListOf<String>()
        val it = payload.keys()
        while (it.hasNext()) keys.add(it.next())
        
        val usernameLen = payload.optString("Username").length
        val passwordLen = payload.optString("Pw").length
        
        Log.d(LOG_TAG, "Request structure [$tag]: keys=$keys, userLen=$usernameLen, pwLen=$passwordLen, bytes=${payload.toString().toByteArray().size}")
    }

    fun authenticateWithQuickConnect(server: String, secret: String): NativeSession =
        authenticate(server, JSONObject().put("Secret", secret), "/Users/AuthenticateWithQuickConnect", "PiggieTV user")

    fun initiateQuickConnect(server: String): QuickConnectTicket {
        val result = JSONObject(request(JellyfinServerUrl.normalize(server) + "/QuickConnect/Initiate", method = "POST", body = "{}"))
        return QuickConnectTicket(result.optString("Secret"), result.optString("Code")).also {
            check(it.secret.isNotBlank() && it.code.isNotBlank()) { "Quick Connect response was incomplete" }
        }
    }

    fun validateSession(session: NativeSession): NativeSession {
        val user = JSONObject(request(session.serverUrl + "/Users/" + encode(session.userId), token = session.token))
        return session.copy(
            userId = user.optString("Id").ifBlank { session.userId },
            userName = user.optString("Name").ifBlank { session.userName }
        )
    }

    fun loadHomeIncrementally(session: NativeSession, onShelf: (MediaShelf) -> Unit) {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,SeriesName,IndexNumber,ParentIndexNumber,RunTimeTicks,OfficialRating,Genres"
        val requests = listOf(
            Triple("Continue watching", session.serverUrl + "/Users/" + user + "/Items/Resume?Limit=18&Fields=" + fields, MediaCardPresentation.LANDSCAPE),
            Triple("Next up", session.serverUrl + "/Shows/NextUp?UserId=" + user + "&Limit=18&Fields=" + fields, MediaCardPresentation.LANDSCAPE),
            Triple("Recently added", session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Movie,Series&Recursive=true&SortBy=DateCreated&SortOrder=Descending&Limit=24&Fields=" + fields, MediaCardPresentation.POSTER)
        )
        val failures = mutableListOf<Throwable>()
        var emitted = false
        requests.forEach { (title, endpoint, presentation) ->
            try {
                val items = parseItems(request(endpoint, token = session.token))
                if (items.isNotEmpty()) {
                    emitted = true
                    onShelf(MediaShelf(title, items, presentation))
                }
            } catch (error: Throwable) {
                failures += error
            }
        }
        if (!emitted && failures.isNotEmpty()) throw failures.first()
    }

    fun loadMoviesIncrementally(session: NativeSession, onShelf: (MediaShelf) -> Unit) {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,RunTimeTicks,OfficialRating,Genres"
        val requests = listOf(
            Triple("Continue watching", session.serverUrl + "/Users/" + user + "/Items/Resume?IncludeItemTypes=Movie&Limit=12&Fields=" + fields, MediaCardPresentation.LANDSCAPE),
            Triple("Recently added", session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Movie&Recursive=true&SortBy=DateCreated&SortOrder=Descending&Limit=18&Fields=" + fields, MediaCardPresentation.POSTER)
        )
        requests.forEach { (title, endpoint, presentation) ->
            runCatching { parseItems(request(endpoint, token = session.token)) }.onSuccess { items ->
                if (items.isNotEmpty()) onShelf(MediaShelf(title, items, presentation))
            }
        }
    }

    fun loadShowsIncrementally(session: NativeSession, onShelf: (MediaShelf) -> Unit) {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,RunTimeTicks,OfficialRating,Genres,SeriesName,IndexNumber,ParentIndexNumber"
        val requests = listOf(
            Triple("Next up", session.serverUrl + "/Shows/NextUp?UserId=" + user + "&Limit=12&Fields=" + fields, MediaCardPresentation.LANDSCAPE),
            Triple("Recently added", session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Series&Recursive=true&SortBy=DateCreated&SortOrder=Descending&Limit=18&Fields=" + fields, MediaCardPresentation.POSTER)
        )
        requests.forEach { (title, endpoint, presentation) ->
            runCatching { parseItems(request(endpoint, token = session.token)) }.onSuccess { items ->
                if (items.isNotEmpty()) onShelf(MediaShelf(title, items, presentation))
            }
        }
    }

    fun loadMovies(session: NativeSession, startIndex: Int = 0, limit: Int = 50, sortBy: String = "SortName", sortOrder: String = "Ascending"): List<MediaItem> {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,RunTimeTicks,CommunityRating,OfficialRating,Genres"
        val endpoint = session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Movie&Recursive=true&SortBy=" + sortBy + "&SortOrder=" + sortOrder + "&StartIndex=" + startIndex + "&Limit=" + limit + "&Fields=" + fields
        return parseItems(request(endpoint, token = session.token))
    }

    fun loadShows(session: NativeSession, startIndex: Int = 0, limit: Int = 50, sortBy: String = "SortName", sortOrder: String = "Ascending"): List<MediaItem> {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,RunTimeTicks,CommunityRating,OfficialRating,Genres"
        val endpoint = session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Series&Recursive=true&SortBy=" + sortBy + "&SortOrder=" + sortOrder + "&StartIndex=" + startIndex + "&Limit=" + limit + "&Fields=" + fields
        return parseItems(request(endpoint, token = session.token))
    }

    fun loadItem(session: NativeSession, itemId: String): MediaItem {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,RunTimeTicks,CommunityRating,OfficialRating,Genres,Overview,MediaSources"
        val endpoint = session.serverUrl + "/Users/" + user + "/Items/" + encode(itemId) + "?Fields=" + fields
        return parseItem(JSONObject(request(endpoint, token = session.token)))
    }

    fun loadSeasons(session: NativeSession, seriesId: String): List<MediaItem> {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData"
        val endpoint = session.serverUrl + "/Shows/" + encode(seriesId) + "/Seasons?UserId=" + user + "&Fields=" + fields
        return parseItems(request(endpoint, token = session.token))
    }

    fun loadEpisodes(session: NativeSession, seriesId: String, seasonId: String): List<MediaItem> {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,RunTimeTicks,IndexNumber,ParentIndexNumber,OfficialRating,Genres"
        val endpoint = session.serverUrl + "/Shows/" + encode(seriesId) + "/Episodes?SeasonId=" + encode(seasonId) + "&UserId=" + user + "&Fields=" + fields
        return parseItems(request(endpoint, token = session.token))
    }

    fun loadNextUpForSeries(session: NativeSession, seriesId: String): MediaItem? {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,RunTimeTicks,SeriesName,IndexNumber,ParentIndexNumber"
        val endpoint = session.serverUrl + "/Shows/NextUp?UserId=" + user + "&SeriesId=" + seriesId + "&Fields=" + fields
        return runCatching { parseItems(request(endpoint, token = session.token)).firstOrNull() }.getOrNull()
    }

    fun loadHero(session: NativeSession): MediaItem? {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,RunTimeTicks,CommunityRating,OfficialRating,Genres,Overview"
        // Try to get a suggested/latest movie for hero
        val endpoint = session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Movie&Recursive=true&SortBy=DateCreated&SortOrder=Descending&Limit=1&Fields=" + fields
        return parseItems(request(endpoint, token = session.token)).firstOrNull()
    }

    fun loadLibraries(session: NativeSession): List<MediaItem> =
        parseItems(request(session.serverUrl + "/Users/" + encode(session.userId) + "/Views", token = session.token))

    fun search(session: NativeSession, query: String): List<MediaItem> =
        parseItems(request(session.serverUrl + "/Users/" + encode(session.userId) + "/Items?SearchTerm=" + encode(query) + "&Recursive=true&Limit=40&Fields=ImageTags,ProductionYear,UserData,RunTimeTicks,SeriesName,IndexNumber&IncludeItemTypes=Movie,Series,MusicArtist,MusicAlbum,Audio,Book,Person", token = session.token))

    fun imageUrl(session: NativeSession, item: MediaItem, presentation: MediaCardPresentation): String =
        session.serverUrl + "/Items/" + encode(item.id) + "/Images/Primary?maxWidth=" + ImageSizing.maxWidth(presentation) + "&quality=90" +
            item.imageTag?.takeIf(String::isNotBlank)?.let { "&tag=" + encode(it) }.orEmpty()

    fun backdropUrl(session: NativeSession, item: MediaItem, maxWidth: Int = 1280): String =
        session.serverUrl + "/Items/" + encode(item.id) + "/Images/Backdrop?maxWidth=" + maxWidth + "&quality=80" +
            item.backdropTag?.takeIf(String::isNotBlank)?.let { "&tag=" + encode(it) }.orEmpty()

    fun logoUrl(session: NativeSession, item: MediaItem, maxWidth: Int = 400): String =
        session.serverUrl + "/Items/" + encode(item.id) + "/Images/Logo?maxWidth=" + maxWidth + "&quality=90" +
            item.logoTag?.takeIf(String::isNotBlank)?.let { "&tag=" + encode(it) }.orEmpty()

    fun authorization(token: String?): String {
        return JellyfinAuthorizationHeader.build(deviceId, appVersion(), token)
    }

    fun requestPlaybackInfo(session: NativeSession, itemId: String, profile: JSONObject): String {
        val endpoint = session.serverUrl + "/Items/" + encode(itemId) + "/PlaybackInfo?UserId=" + encode(session.userId)
        return request(endpoint, method = "POST", body = profile.toString(), token = session.token)
    }

    fun reportPlaying(session: NativeSession, itemId: String, playSessionId: String, positionTicks: Long) {
        thread {
            val payload = JSONObject().apply {
                put("ItemId", itemId)
                put("PlaySessionId", playSessionId)
                put("PositionTicks", positionTicks)
            }
            runCatching { request(session.serverUrl + "/Sessions/Playing", method = "POST", body = payload.toString(), token = session.token) }
        }
    }

    fun reportProgress(session: NativeSession, itemId: String, playSessionId: String, positionTicks: Long, isPaused: Boolean) {
        thread {
            val payload = JSONObject().apply {
                put("ItemId", itemId)
                put("PlaySessionId", playSessionId)
                put("PositionTicks", positionTicks)
                put("IsPaused", isPaused)
            }
            runCatching { request(session.serverUrl + "/Sessions/Playing/Progress", method = "POST", body = payload.toString(), token = session.token) }
        }
    }

    fun reportStopped(session: NativeSession, itemId: String, playSessionId: String, positionTicks: Long) {
        thread {
            val payload = JSONObject().apply {
                put("ItemId", itemId)
                put("PlaySessionId", playSessionId)
                put("PositionTicks", positionTicks)
            }
            runCatching { request(session.serverUrl + "/Sessions/Playing/Stopped", method = "POST", body = payload.toString(), token = session.token) }
        }
    }

    fun loadMusicHomeIncrementally(session: NativeSession, onShelf: (MediaShelf) -> Unit) {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,Artists,Album,AlbumArtist,AlbumId"
        val requests = listOf(
            Triple("Recently played", session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Audio&Recursive=true&SortBy=DatePlayed&SortOrder=Descending&Limit=12&Fields=" + fields, MediaCardPresentation.SQUARE),
            Triple("Recently added albums", session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=MusicAlbum&Recursive=true&SortBy=DateCreated&SortOrder=Descending&Limit=12&Fields=" + fields, MediaCardPresentation.SQUARE),
            Triple("Recently added songs", session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Audio&Recursive=true&SortBy=DateCreated&SortOrder=Descending&Limit=12&Fields=" + fields, MediaCardPresentation.LANDSCAPE),
            Triple("Favorite albums", session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=MusicAlbum&Recursive=true&Filters=IsFavorite&Limit=12&Fields=" + fields, MediaCardPresentation.SQUARE)
        )
        requests.forEach { (title, endpoint, presentation) ->
            runCatching { parseItems(request(endpoint, token = session.token)) }.onSuccess { items ->
                if (items.isNotEmpty()) onShelf(MediaShelf(title, items, presentation))
            }
        }
    }

    fun loadReadingHomeIncrementally(session: NativeSession, onShelf: (MediaShelf) -> Unit) {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,SeriesName,IndexNumber,RunTimeTicks,OfficialRating,Genres"
        val requests = listOf(
            Triple("Continue reading", session.serverUrl + "/Users/" + user + "/Items/Resume?IncludeItemTypes=Book&Limit=12&Fields=" + fields, MediaCardPresentation.POSTER),
            Triple("Recently added books", session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Book&Recursive=true&SortBy=DateCreated&SortOrder=Descending&Limit=18&Fields=" + fields, MediaCardPresentation.POSTER),
            Triple("Favorite books", session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Book&Recursive=true&Filters=IsFavorite&Limit=12&Fields=" + fields, MediaCardPresentation.POSTER)
        )
        requests.forEach { (title, endpoint, presentation) ->
            runCatching { parseItems(request(endpoint, token = session.token)) }.onSuccess { items ->
                if (items.isNotEmpty()) onShelf(MediaShelf(title, items, presentation))
            }
        }
    }

    fun loadArtists(session: NativeSession, startIndex: Int = 0, limit: Int = 50): List<MediaItem> {
        val user = encode(session.userId)
        val endpoint = session.serverUrl + "/Artists?UserId=" + user + "&StartIndex=" + startIndex + "&Limit=" + limit + "&Fields=PrimaryImageAspectRatio,ImageTags"
        return parseItems(request(endpoint, token = session.token))
    }

    fun loadAlbums(session: NativeSession, startIndex: Int = 0, limit: Int = 50, sortBy: String = "SortName", sortOrder: String = "Ascending"): List<MediaItem> {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,Artists,AlbumArtist"
        val endpoint = session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=MusicAlbum&Recursive=true&SortBy=" + sortBy + "&SortOrder=" + sortOrder + "&StartIndex=" + startIndex + "&Limit=" + limit + "&Fields=" + fields
        return parseItems(request(endpoint, token = session.token))
    }

    fun loadSongs(session: NativeSession, startIndex: Int = 0, limit: Int = 50): List<MediaItem> {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,Artists,Album,AlbumArtist,AlbumId,RunTimeTicks"
        val endpoint = session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Audio&Recursive=true&SortBy=SortName&SortOrder=Ascending&StartIndex=" + startIndex + "&Limit=" + limit + "&Fields=" + fields
        return parseItems(request(endpoint, token = session.token))
    }

    fun loadBooks(session: NativeSession, startIndex: Int = 0, limit: Int = 50): List<MediaItem> {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,SeriesName,IndexNumber,OfficialRating,Genres"
        val endpoint = session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Book&Recursive=true&SortBy=SortName&SortOrder=Ascending&StartIndex=" + startIndex + "&Limit=" + limit + "&Fields=" + fields
        return parseItems(request(endpoint, token = session.token))
    }

    fun loadPlaylists(session: NativeSession): List<MediaItem> {
        val user = encode(session.userId)
        val endpoint = session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Playlist&Recursive=true&Fields=PrimaryImageAspectRatio,ImageTags"
        return parseItems(request(endpoint, token = session.token))
    }

    fun loadAlbumSongs(session: NativeSession, albumId: String): List<MediaItem> {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,Artists,Album,AlbumArtist,AlbumId,RunTimeTicks,IndexNumber"
        val endpoint = session.serverUrl + "/Users/" + user + "/Items?ParentId=" + encode(albumId) + "&Fields=" + fields
        return parseItems(request(endpoint, token = session.token))
    }

    fun loadArtistAlbums(session: NativeSession, artistId: String): List<MediaItem> {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,Artists"
        val endpoint = session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=MusicAlbum&Recursive=true&ArtistIds=" + encode(artistId) + "&Fields=" + fields
        return parseItems(request(endpoint, token = session.token))
    }

    fun loadPlaylistSongs(session: NativeSession, playlistId: String): List<MediaItem> {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,Artists,Album,AlbumArtist,AlbumId,RunTimeTicks"
        val endpoint = session.serverUrl + "/Playlists/" + encode(playlistId) + "/Items?UserId=" + user + "&Fields=" + fields
        return parseItems(request(endpoint, token = session.token))
    }

    fun loadArtistSongs(session: NativeSession, artistId: String): List<MediaItem> {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,Artists,Album,AlbumArtist,AlbumId,RunTimeTicks"
        val endpoint = session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Audio&Recursive=true&ArtistIds=" + encode(artistId) + "&Fields=" + fields
        return parseItems(request(endpoint, token = session.token))
    }

    fun setFavorite(session: NativeSession, itemId: String, isFavorite: Boolean) {
        val user = encode(session.userId)
        val method = if (isFavorite) "POST" else "DELETE"
        val body = if (isFavorite) "" else null // Empty string for POST, null for DELETE
        request(session.serverUrl + "/Users/" + user + "/FavoriteItems/" + encode(itemId), method = method, body = body, token = session.token)
    }

    fun setPlayed(session: NativeSession, itemId: String, isPlayed: Boolean) {
        val user = encode(session.userId)
        val method = if (isPlayed) "POST" else "DELETE"
        val body = if (isPlayed) "" else null // Empty string for POST, null for DELETE
        request(session.serverUrl + "/Users/" + user + "/PlayedItems/" + encode(itemId), method = method, body = body, token = session.token)
    }

    fun getPageImageUrl(session: NativeSession, bookId: String, pageIndex: Int): String {
        return session.serverUrl + "/Items/" + bookId + "/Images/Page/" + pageIndex + "?api_key=" + session.token
    }

    fun diagnostics(session: NativeSession, origin: SessionOrigin): NativeDiagnostics {
        val metrics = context.resources.displayMetrics
        val sw = context.resources.configuration.smallestScreenWidthDp
        val metricsString = "${metrics.widthPixels}x${metrics.heightPixels} (density=${metrics.density}, dpi=${metrics.densityDpi}, sw=${sw}dp)"
        
        return NativeDiagnostics(
            serverUrl = session.serverUrl,
            serverId = maskIdentifier(session.serverId),
            userId = maskIdentifier(session.userId),
            appVersion = appVersion(),
            androidVersion = Build.VERSION.RELEASE,
            device = Build.MANUFACTURER + " " + Build.MODEL,
            displayMetrics = metricsString,
            lastApiError = lastApiError ?: latestSafeNetworkFailure,
            lastNetworkDetails = latestSafeNetworkDetails,
            lastSuccessAt = lastSuccessAt,
            sessionOrigin = origin
        )
    }

    private fun authenticate(server: String, payload: JSONObject, path: String, fallbackName: String): NativeSession {
        val normalizedServer = JellyfinServerUrl.normalize(server)
        val response = JSONObject(request(normalizedServer + path, method = "POST", body = payload.toString()))
        val user = response.optJSONObject("User")
        return NativeSession(
            response.optString("AccessToken"),
            response.optString("ServerId"),
            user?.optString("Id").orEmpty(),
            user?.optString("Name").takeUnless { it.isNullOrBlank() } ?: fallbackName,
            normalizedServer
        ).also { check(it.isComplete()) { "Authentication response missing required fields" } }
    }

    private fun parseItems(body: String): List<MediaItem> {
        val array = JSONObject(body).optJSONArray("Items") ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(parseItem(item))
            }
        }
    }

    private fun parseItem(item: JSONObject): MediaItem {
        val id = item.optString("Id")
        val userData = item.optJSONObject("UserData")
        val season = if (item.has("ParentIndexNumber")) item.optInt("ParentIndexNumber") else -1
        val episode = if (item.has("IndexNumber")) item.optInt("IndexNumber") else -1
        val imageTags = item.optJSONObject("ImageTags")
        val genres = item.optJSONArray("Genres")?.let { arr ->
            List(arr.length()) { arr.optString(it) }
        } ?: emptyList()

        val label = when {
            season >= 0 && episode >= 0 -> "S$season E$episode"
            episode >= 0 -> "Episode $episode"
            else -> null
        }

        val artists = item.optJSONArray("Artists")?.let { arr ->
            List(arr.length()) { arr.optString(it) }
        } ?: emptyList()

        return MediaItem(
            id = id,
            title = item.optString("Name").ifBlank { "Untitled" },
            type = item.optString("Type"),
            year = item.optInt("ProductionYear", 0).takeIf { it > 0 }?.toString(),
            imageTag = imageTags?.optString("Primary"),
            backdropTag = imageTags?.optString("Backdrop"),
            logoTag = imageTags?.optString("Logo"),
            seriesName = item.optString("SeriesName").ifBlank { null },
            episodeLabel = label,
            playbackPositionTicks = userData?.optLong("PlaybackPositionTicks", 0L) ?: 0L,
            runtimeTicks = item.optLong("RunTimeTicks", 0L),
            overview = item.optString("Overview"),
            communityRating = item.optDouble("CommunityRating", 0.0).toFloat().takeIf { it > 0 },
            officialRating = item.optString("OfficialRating"),
            genres = genres,
            productionYear = item.optInt("ProductionYear", 0).takeIf { it > 0 },
            seriesId = item.optString("SeriesId").ifBlank { null },
            seasonId = item.optString("SeasonId").ifBlank { null },
            artists = artists,
            album = item.optString("Album").ifBlank { null },
            albumArtist = item.optString("AlbumArtist").ifBlank { null },
            albumId = item.optString("AlbumId").ifBlank { null },
            artistId = item.optJSONArray("ArtistItems")?.optJSONObject(0)?.optString("Id"),
            indexNumber = if (item.has("IndexNumber")) item.optInt("IndexNumber") else null,
            parentIndexNumber = if (item.has("ParentIndexNumber")) item.optInt("ParentIndexNumber") else null,
            isFavorite = userData?.optBoolean("IsFavorite", false) ?: false,
            isPlayed = userData?.optBoolean("Played", false) ?: false,
            pageCount = item.optInt("PageCount", 0)
        )
    }

    private fun request(endpoint: String, method: String = "GET", body: String? = null, token: String? = null): String {
        return try {
            val response = transport.execute(endpoint, method, body, token)
            lastApiError = null
            lastSuccessAt = System.currentTimeMillis()
            response
        } catch (error: Throwable) {
            lastApiError = transport.latestDiagnostic()?.summary()
                ?: when (error) {
                    is HttpRequestFailure -> "HTTP " + error.statusCode
                    else -> error::class.java.simpleName
                }
            latestSafeNetworkFailure = lastApiError
            latestSafeNetworkDetails = transport.latestDiagnostic()?.timingDetails()
            throw error
        }
    }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    private fun maskIdentifier(value: String): String =
        if (value.length <= 8) "****" else value.take(4) + "..." + value.takeLast(4)

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        @Volatile var latestSafeNetworkFailure: String? = null
        @Volatile var latestSafeNetworkDetails: String? = null
    }
}
