package com.piggie.tv.fixture

import android.content.Context
import android.os.Looper
import com.piggie.tv.data.api.PtvHttpClientOwner
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.updates.ReleaseCheckResult
import com.piggie.tv.updates.ReleaseUpdateManager
import com.piggie.tv.util.LocalFixtureNetworkGuard
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class FixtureValidationGuardTest {
    @Test
    fun metadataClientRejectsOtherLoopbackPortsBeforeConnection() {
        val request = Request.Builder().url("http://127.0.0.1:9/Items").build()
        val error = runCatching {
            PtvHttpClientOwner.metadataBaseClient.newCall(request).execute().use { }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(
            "Local fixture allows only http://127.0.0.1:18896 or http://10.16.0.125:18896",
            error?.message
        )
    }

    @Test
    fun networkGuardAllowsOnlyExactLocalFixtureEndpoints() {
        for (url in listOf("http://127.0.0.1:18896/Items", "http://10.16.0.125:18896/Items")) {
            assertTrue(LocalFixtureNetworkGuard.allowsFixtureUrl(Request.Builder().url(url).build().url))
        }
        for (url in listOf(
            "http://10.16.0.189:18896/Items",
            "http://10.16.0.126:18896/Items",
            "http://10.16.0.125:9/Items",
            "https://10.16.0.125:18896/Items",
            "http://fixture:fixture@10.16.0.125:18896/Items"
        )) {
            assertTrue(!LocalFixtureNetworkGuard.allowsFixtureUrl(Request.Builder().url(url).build().url))
        }
    }

    @Test
    fun automaticUpdateCheckIsSkippedBeforeCooldownLogic() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("ptv_release_updates", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_check_ms", System.currentTimeMillis())
            .commit()
        val session = NativeSession(
            token = "fixture-token",
            serverId = "fixture-server",
            userId = "fixture-user",
            userName = "fixture",
            serverUrl = "http://127.0.0.1:18896"
        )
        var result: ReleaseCheckResult? = null

        ReleaseUpdateManager(context).checkForUpdates(session, force = false) { result = it }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "Local fixture does not check for releases",
            (result as ReleaseCheckResult.Skipped).message
        )
    }
}
