package com.piggie.tv.updates

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.piggie.tv.BuildConfig
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.NativeSettings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import kotlin.concurrent.thread

data class GitHubRelease(
    val tagName: String,
    val name: String?,
    val isPrerelease: Boolean,
    val isDraft: Boolean,
    val htmlUrl: String?,
    val publishedAt: String?
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: tagName
}

sealed interface ReleaseCheckResult {
    data class Available(val release: GitHubRelease) : ReleaseCheckResult
    data class UpToDate(val message: String) : ReleaseCheckResult
    data class Skipped(val message: String) : ReleaseCheckResult
    data class Error(val message: String) : ReleaseCheckResult
}

class ReleaseUpdateManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("ptv_release_updates", Context.MODE_PRIVATE)
    private val httpClient = OkHttpClient.Builder().build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val settingsProvider by lazy { NativeSettings(context) }

    fun checkForUpdates(
        session: NativeSession,
        force: Boolean = false,
        onResult: ((ReleaseCheckResult) -> Unit)? = null
    ) {
        if (!force) {
            val lastChecked = prefs.getLong(KEY_LAST_CHECK_MS, 0L)
            val now = System.currentTimeMillis()
            if (now - lastChecked < CHECK_INTERVAL_MS) {
                onResult?.let {
                    mainHandler.post {
                        it(ReleaseCheckResult.Skipped("Update checks are on cooldown"))
                    }
                }
                return
            }
        }

        thread(name = "release-check-thread") {
            val now = System.currentTimeMillis()
            prefs.edit().putLong(KEY_LAST_CHECK_MS, now).apply()
            try {
                val latestReleases = fetchReleasesFromGitHub()
                val candidate = selectReleaseForSession(session, settingsProvider, latestReleases)
                if (candidate == null) {
                    postResult(
                        ReleaseCheckResult.UpToDate("PiggieTV is up to date"),
                        onResult
                    )
                    return@thread
                }

                val alreadyNotifiedTag = prefs.getString(KEY_LAST_NOTIFIED_TAG, null)
                if (candidate.tagName == alreadyNotifiedTag) {
                    postResult(
                        ReleaseCheckResult.UpToDate("No new releases since last notification"),
                        onResult
                    )
                    return@thread
                }

                if (postNotification(candidate)) {
                    prefs.edit().putString(KEY_LAST_NOTIFIED_TAG, candidate.tagName).apply()
                    postResult(ReleaseCheckResult.Available(candidate), onResult)
                } else {
                    postResult(
                        ReleaseCheckResult.Error("Update available, but notifications are blocked"),
                        onResult
                    )
                }
            } catch (error: Exception) {
                postResult(ReleaseCheckResult.Error(error.message.orEmpty()), onResult)
            }
        }
    }

    private fun fetchReleasesFromGitHub(): List<GitHubRelease> {
        val request = Request.Builder()
            .url(RELEASES_ENDPOINT)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PiggieTV-AndroidTV")
            .get()
            .build()

        val responseBody = runCatching { executeRequest(request) }.getOrElse { throwable ->
            throw IOException("GitHub release check failed: ${throwable.message}")
        }
        val array = JSONArray(responseBody)
        val releases = ArrayList<GitHubRelease>(array.length())
        repeat(array.length()) { index ->
            val release = array.optJSONObject(index) ?: return@repeat
            val tagName = release.optString("tag_name").trim()
            if (tagName.isBlank()) return@repeat

            releases.add(
                GitHubRelease(
                    tagName = tagName,
                    name = release.optString("name").ifBlank { null },
                    isPrerelease = release.optBoolean("prerelease"),
                    isDraft = release.optBoolean("draft"),
                    htmlUrl = release.optString("html_url").ifBlank { null },
                    publishedAt = release.optString("published_at").ifBlank { null }
                )
            )
        }
        return releases
    }

    private fun executeRequest(request: Request): String {
        return httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw IOException("GitHub API response had no body")
            if (!response.isSuccessful) {
                throw IOException("GitHub API request failed (${response.code}) $body")
            }
            body
        }
    }

    private fun selectReleaseForSession(
        session: NativeSession,
        settings: NativeSettings,
        releases: List<GitHubRelease>
    ): GitHubRelease? {
        val currentVersion = parseVersion(BuildConfig.VERSION_NAME)

        for (release in releases) {
            if (release.isDraft) continue
            if (release.isPrerelease && !canReceivePrerelease(session, settings)) continue
            if (currentVersion == null || isNewerVersion(release.tagName, currentVersion)) {
                return release
            }
        }

        return null
    }

    private fun canReceivePrerelease(session: NativeSession, settings: NativeSettings): Boolean {
        return session.isAdministrator || settings.notifyBetaReleases
    }

    private fun postNotification(candidate: GitHubRelease): Boolean {
        if (!hasPostNotificationPermission()) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)
        val openIntent = openReleaseIntent(candidate)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, RELEASE_NOTIFICATION_CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("PiggieTV Update Available")
            .setContentText("${candidate.displayName} (${candidate.tagName}) is available")
            .setAutoCancel(true)
            .setContentIntent(openIntent)

        manager.notify(RELEASE_NOTIFICATION_ID, builder.build())
        return true
    }

    private fun hasPostNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun openReleaseIntent(candidate: GitHubRelease): PendingIntent {
        val releaseUrl = candidate.htmlUrl?.let { if (it.isNotBlank()) it else null } ?: RELEASES_PAGE
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl))
        return PendingIntent.getActivity(
            context,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(RELEASE_NOTIFICATION_CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            RELEASE_NOTIFICATION_CHANNEL_ID,
            "PiggieTV Releases",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Shows when a new PiggieTV release is available"
        }
        manager.createNotificationChannel(channel)
    }

    private fun isNewerVersion(candidateTag: String, currentVersion: ReleaseVersion): Boolean {
        val candidateVersion = parseVersion(candidateTag) ?: return false
        return candidateVersion > currentVersion
    }

    private fun parseVersion(tag: String): ReleaseVersion? {
        var normalized = tag.trim()
        normalized = normalized.lowercase().let { value ->
            if (value.startsWith("v")) value.substring(1) else value
        }
        if (normalized.isBlank()) return null

        val basePart = normalized.substringBefore("-", "")
        val prePart = if (normalized.contains("-")) normalized.substringAfter("-") else ""
        if (basePart.isBlank()) return null

        val segments = basePart.split(".")
        val major = segments.getOrNull(0)?.toIntOrNull() ?: return null
        val minor = segments.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = segments.getOrNull(2)?.toIntOrNull() ?: 0

        if (prePart.isBlank()) {
            return ReleaseVersion(
                major = major,
                minor = minor,
                patch = patch,
                isPrerelease = false,
                prereleaseTier = ReleaseVersion.STABLE_TIER,
                prereleaseNumber = 0
            )
        }

        val pre = prePart.lowercase()
        val prereleaseTier = when {
            pre.startsWith("beta") || pre.startsWith("b") || pre.startsWith("rc") -> ReleaseVersion.BETA_TIER
            pre.startsWith("alpha") || pre.startsWith("a") -> ReleaseVersion.ALPHA_TIER
            else -> ReleaseVersion.BETA_TIER
        }
        val prereleaseNumber = Regex("(\\d+)").find(pre)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

        return ReleaseVersion(
            major = major,
            minor = minor,
            patch = patch,
            isPrerelease = true,
            prereleaseTier = prereleaseTier,
            prereleaseNumber = prereleaseNumber
        )
    }

    private fun postResult(result: ReleaseCheckResult, onResult: ((ReleaseCheckResult) -> Unit)?) {
        onResult?.let { callback ->
            mainHandler.post { callback(result) }
        }
    }

    private data class ReleaseVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val isPrerelease: Boolean,
        val prereleaseTier: Int,
        val prereleaseNumber: Int
    ) : Comparable<ReleaseVersion> {
        override fun compareTo(other: ReleaseVersion): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            if (patch != other.patch) return patch.compareTo(other.patch)
            if (prereleaseTier != other.prereleaseTier) return prereleaseTier.compareTo(other.prereleaseTier)
            if (isPrerelease && other.isPrerelease && prereleaseNumber != other.prereleaseNumber) {
                return prereleaseNumber.compareTo(other.prereleaseNumber)
            }
            return 0
        }

        companion object {
            const val ALPHA_TIER = 0
            const val BETA_TIER = 1
            const val STABLE_TIER = 2
        }
    }

    companion object {
        private const val RELEASES_ENDPOINT = "https://api.github.com/repos/Piglit09/PiggieTV-AndoidTV/releases"
        private const val RELEASES_PAGE = "https://github.com/Piglit09/PiggieTV-AndoidTV/releases"
        private const val RELEASE_NOTIFICATION_CHANNEL_ID = "ptv-release-updates"
        private const val RELEASE_NOTIFICATION_ID = 22001
        private const val KEY_LAST_CHECK_MS = "last_check_ms"
        private const val KEY_LAST_NOTIFIED_TAG = "last_notified_tag"
        private const val CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
    }
}
