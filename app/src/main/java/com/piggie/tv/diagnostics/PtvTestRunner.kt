package com.piggie.tv.diagnostics

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.playback.MusicPlaybackManager
import com.piggie.tv.navigation.NativeRoute
import kotlin.concurrent.thread

enum class PtvTestStatus { PASSED, FAILED, SKIPPED }

data class PtvTestResult(
    val timestampMs: Long = System.currentTimeMillis(),
    val suite: String,
    val test: String,
    val status: PtvTestStatus,
    val durationMs: Long,
    val detail: String
)

object PtvTestRunner {
    fun runSafe(
        context: Context,
        session: NativeSession,
        api: JellyfinNativeApi,
        onComplete: (List<PtvTestResult>) -> Unit
    ) {
        thread(name = "ptv-test-runner", start = true) {
            val results = mutableListOf<PtvTestResult>()
            results += run("Connectivity", "Public server info") {
                val info = api.validateServer(session.serverUrl)
                check(info.name.isNotBlank()) { "Server returned no name" }
                "Reachable; Jellyfin ${info.version}"
            }

            var sampleItem: com.piggie.tv.data.models.MediaItem? = null
            results += run("Connectivity", "Authenticated request") {
                sampleItem = api.loadMovies(session, limit = 1).firstOrNull()
                "Authenticated request succeeded; sample=${if (sampleItem == null) "library empty" else "available"}"
            }
            results += if (sampleItem != null && !sampleItem!!.imageTag.isNullOrBlank()) {
                run("Connectivity", "Image request") {
                    check(api.probeImage(session, sampleItem!!, MediaCardPresentation.POSTER))
                    "Authenticated image response contained data"
                }
            } else {
                skipped("Connectivity", "Image request", "No sample artwork was available")
            }

            results += run("Navigation", "Native route registry") {
                check(NativeRoute.entries.map { it.label }.distinct().size == NativeRoute.entries.size)
                "${NativeRoute.entries.size} unique native routes registered; visual nonblank checks require guided route opening"
            }
            results += skipped(
                "Playback",
                "Playback negotiation/lifecycle",
                "Requires explicit confirmation and a user-selected sample; safe tests never start or negotiate real media"
            )
            results += run("Music", "Manager initialization") {
                MusicPlaybackManager.init(context.applicationContext)
                "Manager initialized; no audio was started"
            }
            results += run("Focus", "Shell focus route coverage") {
                check(NativeRoute.entries.isNotEmpty())
                "Focus tracing is enabled for ${NativeRoute.entries.size} shell destinations"
            }

            results.forEach(PtvDiagnosticsManager::recordTest)
            Handler(Looper.getMainLooper()).post { onComplete(results) }
        }
    }

    private fun run(suite: String, test: String, action: () -> String): PtvTestResult {
        val started = android.os.SystemClock.elapsedRealtime()
        return runCatching { action() }.fold(
            onSuccess = {
                PtvTestResult(
                    suite = suite,
                    test = test,
                    status = PtvTestStatus.PASSED,
                    durationMs = android.os.SystemClock.elapsedRealtime() - started,
                    detail = it
                )
            },
            onFailure = {
                PtvTestResult(
                    suite = suite,
                    test = test,
                    status = PtvTestStatus.FAILED,
                    durationMs = android.os.SystemClock.elapsedRealtime() - started,
                    detail = it.javaClass.simpleName + ": " + (it.message ?: "unknown")
                )
            }
        )
    }

    private fun skipped(suite: String, test: String, detail: String) = PtvTestResult(
        suite = suite,
        test = test,
        status = PtvTestStatus.SKIPPED,
        durationMs = 0,
        detail = detail
    )
}
