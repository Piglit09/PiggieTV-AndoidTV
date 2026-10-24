package com.piggie.tv.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.piggie.tv.data.api.cancelPendingPlaybackReports
import com.piggie.tv.data.discovery.DiscoveryManager
import com.piggie.tv.data.playback.MusicPlaybackManager
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.ui.player.MediaDetailsSeedStore

/** One ordered logout path for every Android TV entry point. */
object AuthLogout {
    /**
     * Invalidates async auth ownership first, then synchronously clears credentials while no newer
     * attempt can commit. Non-credential account caches are cleared regardless of storage outcome.
     */
    fun clearLocalAuthentication(
        store: SecureSessionStore,
        cancelAdditionalRequests: () -> Unit = {},
    ): Boolean {
        val cleared = AuthSessionCoordinator.invalidateAndClear(store::clear)
        cancelPendingPlaybackReports()
        runCatching(cancelAdditionalRequests)
        MusicPlaybackManager.shutdown()
        DiscoveryManager.clearForLogout()
        MediaDetailsSeedStore.clear()
        return cleared
    }

    fun returnToLogin(activity: Activity, storageClearFailed: Boolean = false) {
        activity.startActivity(loginIntent(activity, storageClearFailed))
        activity.finish()
    }

    fun loginIntent(context: Context, storageClearFailed: Boolean = false): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (storageClearFailed) putExtra(MainActivity.EXTRA_STORAGE_CLEAR_FAILED, true)
        }
}
