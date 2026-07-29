package com.piggie.tv.data.api

import android.content.Context
import android.os.Build
import android.util.Log
import com.piggie.tv.data.models.*
import com.piggie.tv.data.playback.ImageSizing
import com.piggie.tv.data.playback.PlaybackProgress
import com.piggie.tv.data.playback.NextEpisodeSelector
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

data class PlaybackInfoMetadata(
    val audioTracks: List<AudioTrack>,
    val subtitleTracks: List<SubtitleTrack>
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

    fun withRequestScope(scope: NativeRequestScope, action: () -> Unit) {
        action()
    }

    fun cancelInFlight() {
        transport.cancelInFlight()
    }

    fun cancelInFlightRequests() = cancelInFlight()

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
        return authenticate(server, payload, "/Users/AuthenticateByName", username)
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
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,SeriesName,IndexNumber,ParentIndexNumber,RunTimeTicks,OfficialRating,Genres,CriticRating,People"
        val requests = listOf(
            Triple("Continue watching", session.serverUrl + "/Users/" + user + "/Items/Resume?Limit=18&Fields=" + fields, MediaCardPresentation.LANDSCAPE),
            Triple("Next up", session.serverUrl + "/Shows/NextUp?UserId=" + user + "&Limit=18&Fields=" + fields, MediaCardPresentation.LANDSCAPE),
            Triple("Recently added", session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Movie,Series&Recursive=true&SortBy=DateCreated&SortOrder=Descending&Limit=24&Fields=" + fields, MediaCardPresentation.POSTER)
        )
        requests.forEach { (title, endpoint, presentation) ->
            runCatching { parseItems(request(endpoint, token = session.token)) }.onSuccess { items ->
                if (items.isNotEmpty()) onShelf(MediaShelf(title, items, presentation))
            }
        }
    }

    fun loadMoviesIncrementally(session: NativeSession, onShelf: (MediaShelf) -> Unit) {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,RunTimeTicks,OfficialRating,Genres,CriticRating,People"
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
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,RunTimeTicks,OfficialRating,Genres,SeriesName,IndexNumber,ParentIndexNumber,CriticRating,People"
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
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,RunTimeTicks,CommunityRating,OfficialRating,Genres,CriticRating,People"
        val endpoint = session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Movie&Recursive=true&SortBy=" + sortBy + "&SortOrder=" + sortOrder + "&StartIndex=" + startIndex + "&Limit=" + limit + "&Fields=" + fields
        return parseItems(request(endpoint, token = session.token))
    }

    fun loadShows(session: NativeSession, startIndex: Int = 0, limit: Int = 50, sortBy: String = "SortName", sortOrder: String = "Ascending"): List<MediaItem> {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,RunTimeTicks,CommunityRating,OfficialRating,Genres,CriticRating,People"
        val endpoint = session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Series&Recursive=true&SortBy=" + sortBy + "&SortOrder=" + sortOrder + "&StartIndex=" + startIndex + "&Limit=" + limit + "&Fields=" + fields
        return parseItems(request(endpoint, token = session.token))
    }

    fun loadItem(session: NativeSession, itemId: String): MediaItem {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,RunTimeTicks,CommunityRating,OfficialRating,Genres,Overview,MediaSources,CriticRating,People"
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
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,RunTimeTicks,IndexNumber,ParentIndexNumber,OfficialRating,Genres,CriticRating,People"
        val endpoint = session.serverUrl + "/Shows/" + encode(seriesId) + "/Episodes?SeasonId=" + encode(seasonId) + "&UserId=" + user + "&Fields=" + fields
        return parseItems(request(endpoint, token = session.token))
    }

    fun loadNextUpForSeries(session: NativeSession, seriesId: String): MediaItem? {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,RunTimeTicks,SeriesName,IndexNumber,ParentIndexNumber"
        val endpoint = session.serverUrl + "/Shows/NextUp?UserId=" + user + "&SeriesId=" + seriesId + "&Fields=" + fields
        return runCatching { parseItems(request(endpoint, token = session.token)).firstOrNull() }.getOrNull()
    }

    fun fetchNextUp(session: NativeSession, limit: Int = 18): List<MediaItem> {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,RunTimeTicks,SeriesName,IndexNumber,ParentIndexNumber"
        val endpoint = session.serverUrl + "/Shows/NextUp?UserId=" + user + "&Limit=" + limit + "&Fields=" + fields
        return parseItems(request(endpoint, token = session.token))
    }

    fun loadNextEpisode(session: NativeSession, current: MediaItem): MediaItem? {
        val seriesId = current.seriesId ?: return null
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,RunTimeTicks,SeriesName,SeriesId,SeasonId,IndexNumber,ParentIndexNumber"
        val endpoint = session.serverUrl + "/Shows/" + encode(seriesId) + "/Episodes?UserId=" + user + "&Fields=" + fields + "&EnableUserData=true"
        val episodes = parseItems(request(endpoint, token = session.token))
        return NextEpisodeSelector.select(current.id, episodes)
            ?: loadNextUpForSeries(session, seriesId)?.takeIf { it.id != current.id }
    }

    fun loadHero(session: NativeSession): MediaItem? {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,RunTimeTicks,CommunityRating,OfficialRating,Genres,Overview,CriticRating,People"
        val endpoint = session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Movie&Recursive=true&SortBy=DateCreated&SortOrder=Descending&Limit=1&Fields=" + fields
        return parseItems(request(endpoint, token = session.token)).firstOrNull()
    }

    fun loadLibraries(session: NativeSession): List<MediaItem> =
        parseItems(request(session.serverUrl + "/Users/" + encode(session.userId) + "/Views", token = session.token))

    fun findLibraryId(session: NativeSession, name: String): String? {
        val libraries = loadLibraries(session)
        return libraries.find { it.title.equals(name, ignoreCase = true) }?.id
    }

    fun fetchItems(session: NativeSession, params: Map<String, String>): List<MediaItem> {
        val query = params.entries.joinToString("&") { "${it.key}=${encode(it.value)}" }
        val endpoint = "${session.serverUrl}/Users/${encode(session.userId)}/Items?$query"
        return parseItems(request(endpoint, token = session.token))
    }

    fun fetchGenres(session: NativeSession, itemTypes: List<String>): List<String> {
        val types = itemTypes.joinToString(",")
        val endpoint = "${session.serverUrl}/Genres?IncludeItemTypes=$types&UserId=${encode(session.userId)}&Recursive=true"
        val array = JSONObject(request(endpoint, token = session.token)).optJSONArray("Items") ?: JSONArray()
        return List(array.length()) { array.optJSONObject(it).optString("Name") }.filter { it.isNotBlank() }
    }

    fun fetchStudios(session: NativeSession, itemTypes: List<String>): List<String> {
        val types = itemTypes.joinToString(",")
        val endpoint = "${session.serverUrl}/Studios?IncludeItemTypes=$types&UserId=${encode(session.userId)}&Recursive=true"
        val array = JSONObject(request(endpoint, token = session.token)).optJSONArray("Items") ?: JSONArray()
        return List(array.length()) { array.optJSONObject(it).optString("Name") }.filter { it.isNotBlank() }
    }

    fun search(session: NativeSession, query: String): List<MediaItem> =
        parseItems(request(session.serverUrl + "/Users/" + encode(session.userId) + "/Items?SearchTerm=" + encode(query) + "&Recursive=true&Limit=40&Fields=ImageTags,ProductionYear,UserData,RunTimeTicks,SeriesName,IndexNumber&IncludeItemTypes=Movie,Series,MusicArtist,MusicAlbum,Audio,Book,Person", token = session.token))

    fun imageUrl(session: NativeSession, item: MediaItem, presentation: MediaCardPresentation): String =
        primaryImageUrl(session, item.id, item.imageTag, ImageSizing.maxWidth(presentation))

    fun primaryImageUrl(session: NativeSession, itemId: String, tag: String?, maxWidth: Int = 400): String =
        session.serverUrl + "/Items/" + encode(itemId) + "/Images/Primary?maxWidth=" + maxWidth + "&quality=90" +
            tag?.takeIf(String::isNotBlank)?.let { "&tag=" + encode(it) }.orEmpty()

    fun thumbUrl(session: NativeSession, itemId: String, tag: String?, maxWidth: Int = 640): String =
        session.serverUrl + "/Items/" + encode(itemId) + "/Images/Thumb?maxWidth=" + maxWidth + "&quality=90" +
            tag?.takeIf(String::isNotBlank)?.let { "&tag=" + encode(it) }.orEmpty()

    fun backdropUrl(session: NativeSession, item: MediaItem, maxWidth: Int = 1280): String =
        backdropUrl(session, item.id, item.backdropTag, maxWidth)

    fun backdropUrl(session: NativeSession, itemId: String, tag: String?, maxWidth: Int = 1280): String =
        session.serverUrl + "/Items/" + encode(itemId) + "/Images/Backdrop?maxWidth=" + maxWidth + "&quality=80" +
            tag?.takeIf(String::isNotBlank)?.let { "&tag=" + encode(it) }.orEmpty()

    fun logoUrl(session: NativeSession, item: MediaItem, maxWidth: Int = 400): String =
        logoUrl(session, item.id, item.logoTag, maxWidth)

    fun logoUrl(session: NativeSession, itemId: String, tag: String?, maxWidth: Int = 400): String =
        session.serverUrl + "/Items/" + encode(itemId) + "/Images/Logo?maxWidth=" + maxWidth + "&quality=90" +
            tag?.takeIf(String::isNotBlank)?.let { "&tag=" + encode(it) }.orEmpty()

    fun fetchRecommendations(session: NativeSession, itemType: String? = null): List<MediaItem> {
        val user = encode(session.userId)
        val typeParam = itemType?.let { "&includeItemTypes=$it" } ?: ""
        val endpoint = "${session.serverUrl}/Users/$user/Suggestions?Fields=PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,OfficialRating,Genres,CriticRating,People&Limit=24$typeParam"
        return parseItems(request(endpoint, token = session.token))
    }

    fun fetchCount(session: NativeSession, params: Map<String, String>): Int {
        val query = params.entries.joinToString("&") { "${it.key}=${encode(it.value)}" }
        val endpoint = "${session.serverUrl}/Users/${encode(session.userId)}/Items?$query&Limit=0"
        return JSONObject(request(endpoint, token = session.token)).optInt("TotalRecordCount", 0)
    }

    fun loadSimilar(session: NativeSession, itemId: String, limit: Int = 12): List<MediaItem> {
        val user = encode(session.userId)
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,RunTimeTicks,OfficialRating,Genres"
        val endpoint = session.serverUrl + "/Items/" + encode(itemId) + "/Similar?UserId=" + user + "&Limit=" + limit + "&Fields=" + fields
        return parseItems(request(endpoint, token = session.token))
    }

    fun loadPlaybackInfoMetadata(session: NativeSession, itemId: String, bitrate: Long): PlaybackInfoMetadata {
        val profile = JSONObject().apply { put("MaxStreamingBitrate", bitrate) }
        val response = JSONObject(request(session.serverUrl + "/Items/" + encode(itemId) + "/PlaybackInfo?UserId=" + encode(session.userId), method = "POST", body = profile.toString(), token = session.token))
        val mediaSources = response.optJSONArray("MediaSources")
        val firstSource = mediaSources?.optJSONObject(0)
        val mediaStreams = firstSource?.optJSONArray("MediaStreams")

        val audioTracks = mutableListOf<AudioTrack>()
        val subtitleTracks = mutableListOf<SubtitleTrack>()

        if (mediaStreams != null) {
            for (i in 0 until mediaStreams.length()) {
                val stream = mediaStreams.optJSONObject(i) ?: continue
                val type = stream.optString("Type")
                val index = stream.optInt("Index")
                val language = stream.optString("Language").takeIf { it.isNotBlank() }
                val title = stream.optString("DisplayTitle").takeUnless { it.isNullOrBlank() }
                val isDefault = stream.optBoolean("IsDefault", false)

                when (type) {
                    "Audio" -> audioTracks.add(AudioTrack(index, language, title, stream.optString("Codec"), isDefault, stream.optInt("Channels").takeIf { it > 0 }))
                    "Subtitle" -> subtitleTracks.add(SubtitleTrack(index, language, title, isDefault, stream.optString("DeliveryMethod"), stream.optBoolean("IsForced", false), stream.optString("Codec")))
                }
            }
        }
        return PlaybackInfoMetadata(audioTracks, subtitleTracks)
    }

    fun authorization(token: String?): String = JellyfinAuthorizationHeader.build(deviceId, appVersion(), token)

    fun requestPlaybackInfo(
        session: NativeSession,
        itemId: String,
        profile: JSONObject,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null
    ): String {
        var endpoint = session.serverUrl + "/Items/" + encode(itemId) + "/PlaybackInfo?UserId=" + encode(session.userId)
        audioStreamIndex?.let { endpoint += "&AudioStreamIndex=$it" }
        subtitleStreamIndex?.let { endpoint += "&SubtitleStreamIndex=$it" }
        return request(endpoint, method = "POST", body = profile.toString(), token = session.token)
    }

    fun reportPlaying(session: NativeSession, itemId: String, playSessionId: String, positionTicks: Long) {
        thread {
            val payload = JSONObject().apply { put("ItemId", itemId); put("PlaySessionId", playSessionId); put("PositionTicks", positionTicks) }
            runCatching { request(session.serverUrl + "/Sessions/Playing", method = "POST", body = payload.toString(), token = session.token) }
        }
    }

    fun reportProgress(session: NativeSession, itemId: String, playSessionId: String, positionTicks: Long, isPaused: Boolean) {
        thread {
            val payload = JSONObject().apply { put("ItemId", itemId); put("PlaySessionId", playSessionId); put("PositionTicks", positionTicks); put("IsPaused", isPaused) }
            runCatching { request(session.serverUrl + "/Sessions/Playing/Progress", method = "POST", body = payload.toString(), token = session.token) }
        }
    }

    fun reportStopped(session: NativeSession, itemId: String, playSessionId: String, positionTicks: Long) {
        thread {
            val payload = JSONObject().apply { put("ItemId", itemId); put("PlaySessionId", playSessionId); put("PositionTicks", positionTicks) }
            runCatching { request(session.serverUrl + "/Sessions/Playing/Stopped", method = "POST", body = payload.toString(), token = session.token) }
        }
    }

    fun reportStoppedNow(session: NativeSession, itemId: String, playSessionId: String, positionTicks: Long) {
        val payload = JSONObject().apply { put("ItemId", itemId); put("PlaySessionId", playSessionId); put("PositionTicks", positionTicks) }
        request(session.serverUrl + "/Sessions/Playing/Stopped", method = "POST", body = payload.toString(), token = session.token)
    }

    fun setFavorite(session: NativeSession, itemId: String, isFavorite: Boolean) {
        val method = if (isFavorite) "POST" else "DELETE"
        request(session.serverUrl + "/Users/" + encode(session.userId) + "/FavoriteItems/" + encode(itemId), method = method, body = if (isFavorite) "" else null, token = session.token)
    }

    fun setPlayed(session: NativeSession, itemId: String, isPlayed: Boolean) {
        val method = if (isPlayed) "POST" else "DELETE"
        request(session.serverUrl + "/Users/" + encode(session.userId) + "/PlayedItems/" + encode(itemId), method = method, body = if (isPlayed) "" else null, token = session.token)
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
        val fields = "PrimaryImageAspectRatio,ImageTags,ProductionYear,UserData,SeriesName,IndexNumber,RunTimeTicks,OfficialRating,Genres,MediaSources"
        val requests = listOf(
            Triple("Continue reading", session.serverUrl + "/Users/" + user + "/Items/Resume?IncludeItemTypes=Book&Limit=12&Fields=" + fields, MediaCardPresentation.POSTER),
            Triple("Recently added books", session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Book&Recursive=true&SortBy=DateCreated&SortOrder=Descending&Limit=18&Fields=" + fields, MediaCardPresentation.POSTER),
            Triple("Book Series", session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Series&Recursive=true&MediaTypes=Book&Limit=12&Fields=" + fields, MediaCardPresentation.POSTER),
            Triple("Authors", session.serverUrl + "/Users/" + user + "/Items?IncludeItemTypes=Person&MediaTypes=Book&Recursive=true&Limit=12&Fields=PrimaryImageAspectRatio,ImageTags", MediaCardPresentation.SQUARE)
        )
        requests.forEach { (title, endpoint, presentation) ->
            runCatching { parseItems(request(endpoint, token = session.token)) }.onSuccess { items ->
                if (items.isNotEmpty()) onShelf(MediaShelf(title, items, presentation))
            }
        }
    }

    fun reportReadingProgress(session: NativeSession, itemId: String, pageIndex: Int) {
        thread {
            val payload = JSONObject().apply { put("ItemId", itemId); put("PositionTicks", pageIndex.toLong() * 10_000_000L) }
            runCatching { request(session.serverUrl + "/Sessions/Playing/Progress", method = "POST", body = payload.toString(), token = session.token) }
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

    fun loadRecentMusicSignals(session: NativeSession, limit: Int = 48): List<MediaItem> = fetchItems(session, mapOf("IncludeItemTypes" to "Audio", "Recursive" to "true", "SortBy" to "DatePlayed", "SortOrder" to "Descending", "Limit" to limit.toString()))
    fun loadFrequentMusicSignals(session: NativeSession, limit: Int = 48): List<MediaItem> = fetchItems(session, mapOf("IncludeItemTypes" to "Audio", "Recursive" to "true", "SortBy" to "PlayCount", "SortOrder" to "Descending", "Limit" to limit.toString()))
    fun loadFavoriteArtists(session: NativeSession, limit: Int = 24): List<MediaItem> = fetchItems(session, mapOf("IncludeItemTypes" to "MusicArtist", "Recursive" to "true", "Filters" to "IsFavorite", "Limit" to limit.toString()))

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

    fun getPageImageUrl(session: NativeSession, bookId: String, pageIndex: Int): String =
        session.serverUrl + "/Items/" + encode(bookId) + "/Images/Page/" + pageIndex

    fun loadPageBytes(session: NativeSession, bookId: String, pageIndex: Int): ByteArray {
        var bytes = ByteArray(0)
        transport.download(getPageImageUrl(session, bookId, pageIndex), session.token) { input ->
            bytes = input.readBytes()
        }
        return bytes
    }

    fun downloadFile(session: NativeSession, itemId: String, action: (java.io.InputStream) -> Unit) {
        val endpoint = session.serverUrl + "/Items/" + encode(itemId) + "/Download"
        transport.download(endpoint, session.token, action)
    }

    fun probeImage(session: NativeSession, item: MediaItem, presentation: MediaCardPresentation): Boolean {
        var read = -1
        transport.download(imageUrl(session, item, presentation), session.token) { input ->
            read = input.read(ByteArray(1024))
        }
        return read > 0
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
        return List(array.length()) { parseItem(array.optJSONObject(it)) }
    }

    private fun parseItem(item: JSONObject): MediaItem {
        val id = item.optString("Id")
        val userData = item.optJSONObject("UserData")
        val season = if (item.has("ParentIndexNumber")) item.optInt("ParentIndexNumber") else -1
        val episode = if (item.has("IndexNumber")) item.optInt("IndexNumber") else -1
        val imageTags = item.optJSONObject("ImageTags")
        val genres = item.optJSONArray("Genres")?.let { arr -> List(arr.length()) { arr.optString(it) } } ?: emptyList()
        val label = when {
            season >= 0 && episode >= 0 -> "S$season E$episode"
            episode >= 0 -> "Episode $episode"
            else -> null
        }
        val artists = item.optJSONArray("Artists")?.let { arr -> List(arr.length()) { arr.optString(it) } } ?: emptyList()
        val mediaStreams = item.optJSONArray("MediaSources")?.optJSONObject(0)?.optJSONArray("MediaStreams")
        val audioTracks = mutableListOf<AudioTrack>()
        val subtitleTracks = mutableListOf<SubtitleTrack>()
        if (mediaStreams != null) {
            for (i in 0 until mediaStreams.length()) {
                val stream = mediaStreams.optJSONObject(i) ?: continue
                val type = stream.optString("Type")
                val index = stream.optInt("Index")
                val language = stream.optString("Language").takeIf { it.isNotBlank() }
                val title = stream.optString("DisplayTitle").takeUnless { it.isNullOrBlank() }
                val isDefault = stream.optBoolean("IsDefault", false)
                when (type) {
                    "Audio" -> audioTracks.add(AudioTrack(index, language, title, stream.optString("Codec"), isDefault, stream.optInt("Channels").takeIf { it > 0 }))
                    "Subtitle" -> subtitleTracks.add(SubtitleTrack(index, language, title, isDefault, stream.optString("DeliveryMethod"), stream.optBoolean("IsForced", false), stream.optString("Codec")))
                }
            }
        }
        val peopleArr = item.optJSONArray("People")
        val people = mutableListOf<Person>()
        var directorName: String? = null
        if (peopleArr != null) {
            for (i in 0 until peopleArr.length()) {
                val p = peopleArr.optJSONObject(i) ?: continue
                val person = Person(p.optString("Id"), p.optString("Name"), p.optString("Role"), p.optString("Type"), p.optString("PrimaryImageTag"))
                people.add(person)
                if (person.type == "Director") directorName = person.name
            }
        }
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
            container = item.optString("Container").ifBlank { item.optString("Path").let { if (it.contains(".")) it.substringAfterLast('.') else "" } }.takeUnless { it.isNullOrBlank() },
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
            pageCount = item.optInt("PageCount", 0),
            criticRating = item.optDouble("CriticRating", 0.0).toFloat().takeIf { it > 0 },
            director = directorName,
            people = people,
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks
        )
    }

    private fun request(endpoint: String, method: String = "GET", body: String? = null, token: String? = null): String {
        return try {
            val response = transport.execute(endpoint, method, body, token)
            lastApiError = null
            lastSuccessAt = System.currentTimeMillis()
            response
        } catch (error: Throwable) {
            lastApiError = transport.latestDiagnostic()?.summary() ?: when (error) { is HttpRequestFailure -> "HTTP " + error.statusCode else -> error::class.java.simpleName }
            latestSafeNetworkFailure = lastApiError
            latestSafeNetworkDetails = transport.latestDiagnostic()?.timingDetails()
            throw error
        }
    }

    private fun appVersion(): String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown" }.getOrDefault("unknown")
    private fun maskIdentifier(value: String): String = if (value.length <= 8) "****" else value.take(4) + "..." + value.takeLast(4)
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        @Volatile var latestSafeNetworkFailure: String? = null
        @Volatile var latestSafeNetworkDetails: String? = null
    }
}
