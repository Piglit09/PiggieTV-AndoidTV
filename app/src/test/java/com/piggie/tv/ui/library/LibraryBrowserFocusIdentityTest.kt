package com.piggie.tv.ui.library

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.piggie.tv.R
import com.piggie.tv.auth.AuthSessionCoordinator
import com.piggie.tv.data.api.SessionOrigin
import com.piggie.tv.data.discovery.DiscoveryLibraryRoutePolicy
import com.piggie.tv.data.discovery.DiscoveryPage
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.EncryptedSessionToken
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.data.session.SessionTokenCipher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-xhdpi")
class LibraryBrowserFocusIdentityTest {
    private lateinit var store: SecureSessionStore
    private var browserController: ActivityController<LibraryBrowserActivity>? = null

    @Before fun prepare() {
        val application: Application = RuntimeEnvironment.getApplication()
        SecureSessionStore.installTokenCipherFactoryForTests { PassthroughTokenCipher }
        store = SecureSessionStore(application)
    }

    @After fun cleanUp() {
        browserController?.let { runCatching { it.pause().stop().destroy() } }
        store.clear()
        AuthSessionCoordinator.resetForTests()
        SecureSessionStore.installTokenCipherFactoryForTests(null)
    }

    @Test fun boundFullLibraryCardCarriesAndClearsItsFocusedMediaIdentity() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Items":[
              {"Id":"movies-root","Name":"Movies","Type":"CollectionFolder"}
            ]}"""))
            server.enqueue(MockResponse().setBody("""{"Items":[
              {"Id":"movie-one","Name":"Movie One","Type":"Movie"}
            ]}"""))
            val session = NativeSession(
                "token", "server", "user", "User", server.url("/").toString().removeSuffix("/")
            )
            val attempt = AuthSessionCoordinator.beginAttempt()
            assertTrue(AuthSessionCoordinator.commitAuthenticated(attempt, session) {
                store.save(session, SessionOrigin.STORED)
            })
            val launchingActivity = Robolectric.buildActivity(Activity::class.java).setup().get()
            LibraryBrowserActivity.start(
                launchingActivity,
                requireNotNull(DiscoveryLibraryRoutePolicy.browseRequest(DiscoveryPage.MOVIES))
            )
            val intent = requireNotNull(shadowOf(launchingActivity).nextStartedActivity)
            browserController = Robolectric.buildActivity(LibraryBrowserActivity::class.java, intent)
                .create().start().resume()
            val browser = requireNotNull(browserController).get()
            val decor = browser.window.decorView
            val width = View.MeasureSpec.makeMeasureSpec(1_920, View.MeasureSpec.EXACTLY)
            val height = View.MeasureSpec.makeMeasureSpec(1_080, View.MeasureSpec.EXACTLY)
            var card: View? = null
            for (attempt in 0 until 100) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                decor.measure(width, height)
                decor.layout(0, 0, 1_920, 1_080)
                card = descendants(decor).filterIsInstance<RecyclerView>()
                    .firstOrNull()?.findViewHolderForAdapterPosition(0)?.itemView
                if (card != null) break
                Thread.sleep(20)
            }

            val boundCard = requireNotNull(card) { "scoped browser did not render its first card" }
            assertEquals("movie-one", boundCard.getTag(R.id.ptv_discovery_item_id))
            assertEquals(2, server.requestCount)
            val recycler = descendants(decor).filterIsInstance<RecyclerView>().first()
            @Suppress("UNCHECKED_CAST")
            val adapter = recycler.adapter as RecyclerView.Adapter<com.piggie.tv.ui.widgets.MediaCardHolder>
            val holder = requireNotNull(recycler.findViewHolderForAdapterPosition(0))
                as com.piggie.tv.ui.widgets.MediaCardHolder
            adapter.onViewRecycled(holder)
            assertEquals(null, boundCard.getTag(R.id.ptv_discovery_item_id))
        }
    }

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
        }
    }

    private object PassthroughTokenCipher : SessionTokenCipher {
        override fun encrypt(token: ByteArray, additionalAuthenticatedData: ByteArray) =
            EncryptedSessionToken(token + ByteArray(16), ByteArray(12))

        override fun decrypt(encryptedToken: EncryptedSessionToken, additionalAuthenticatedData: ByteArray): ByteArray =
            encryptedToken.ciphertext.copyOfRange(0, encryptedToken.ciphertext.size - 16)
    }
}
