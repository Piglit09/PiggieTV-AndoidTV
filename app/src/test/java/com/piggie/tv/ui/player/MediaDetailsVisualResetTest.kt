package com.piggie.tv.ui.player

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.piggie.tv.R
import com.piggie.tv.auth.AuthSessionCoordinator
import com.piggie.tv.data.api.SessionOrigin
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.EncryptedSessionToken
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.data.session.SessionTokenCipher
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaDetailsVisualResetTest {
    private lateinit var store: SecureSessionStore
    private lateinit var server: MockWebServer
    private var controller: ActivityController<MediaDetailsActivity>? = null

    @Before
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(404)
            }
            start()
        }
        SecureSessionStore.installTokenCipherFactoryForTests { PassthroughTokenCipher }
        store = SecureSessionStore(RuntimeEnvironment.getApplication())
        val session = NativeSession(
            token = "details-visual-reset-token",
            serverId = "details-visual-reset-server",
            userId = "details-visual-reset-user",
            userName = "Details Test",
            serverUrl = server.url("/").toString().removeSuffix("/")
        )
        val attempt = AuthSessionCoordinator.beginAttempt()
        assertTrue(AuthSessionCoordinator.commitAuthenticated(attempt, session) {
            store.save(session, SessionOrigin.STORED)
        })
        MediaDetailsSeedStore.clear()
    }

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        MediaDetailsSeedStore.clear()
        store.clear()
        AuthSessionCoordinator.resetForTests()
        SecureSessionStore.installTokenCipherFactoryForTests(null)
        server.shutdown()
    }

    @Test
    fun uncachedNavigationAndFailureResetCinematicScene() {
        MediaDetailsSeedStore.put(
            MediaItem(
                id = "seed-movie",
                title = "Seed Movie",
                type = "Movie",
                year = "2026",
                imageTag = null,
                seriesName = null,
                episodeLabel = null,
                playbackPositionTicks = 0L,
                runtimeTicks = 0L
            )
        )
        val intent = Intent(RuntimeEnvironment.getApplication(), MediaDetailsActivity::class.java)
            .putExtra(MediaDetailsActivity.EXTRA_ITEM_ID, "seed-movie")
        controller = Robolectric.buildActivity(MediaDetailsActivity::class.java, intent).setup()
        val activity = requireNotNull(controller).get()
        activity.supportFragmentManager.executePendingTransactions()
        val fragment = activity.supportFragmentManager.fragments.filterIsInstance<MediaDetailsFragment>().single()
        val root = fragment.requireView() as FrameLayout
        val scrim = root.getChildAt(1)
        val legacy = AppCompatResources.getDrawable(activity, R.drawable.hero_gradient_overlay)!!
        assertNotEquals(pixel(legacy), pixel(scrim.background))

        MediaDetailsFragment::class.java.getDeclaredMethod("switchItem", String::class.java).apply {
            isAccessible = true
        }.invoke(fragment, "uncached-episode")

        assertEquals(pixel(legacy), pixel(scrim.background))
        val loadingOverview = requireNotNull(findText(root, "Title and playback actions will appear as soon as the item is available."))
        assertEquals(Int.MAX_VALUE, loadingOverview.maxWidth)
        assertEquals(activity.resources.getDimension(R.dimen.tv_text_size_body), loadingOverview.textSize, 0.5f)

        MediaDetailsFragment::class.java.getDeclaredMethod(
            "renderLoadFailure",
            Throwable::class.java,
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }.invoke(fragment, IllegalStateException("missing"), 0)

        requireNotNull(findText(root, "Details unavailable"))
        assertEquals(pixel(legacy), pixel(scrim.background))
        assertEquals(Int.MAX_VALUE, loadingOverview.maxWidth)
    }

    @Test
    fun movieSecondaryFactsFollowDescriptionAndActions() {
        val description = "A pilot crosses an ocean of clouds."
        MediaDetailsSeedStore.put(
            MediaItem(
                id = "hierarchy-movie",
                title = "Blue Meridian",
                type = "Movie",
                year = "2026",
                imageTag = null,
                seriesName = null,
                episodeLabel = null,
                overview = description,
                playbackPositionTicks = 0L,
                runtimeTicks = 71_400_000_000L
            )
        )
        val intent = Intent(RuntimeEnvironment.getApplication(), MediaDetailsActivity::class.java)
            .putExtra(MediaDetailsActivity.EXTRA_ITEM_ID, "hierarchy-movie")
        controller = Robolectric.buildActivity(MediaDetailsActivity::class.java, intent).setup()
        val activity = requireNotNull(controller).get()
        activity.supportFragmentManager.executePendingTransactions()
        val root = activity.supportFragmentManager.fragments
            .filterIsInstance<MediaDetailsFragment>().single().requireView()

        val factsRow = requireNotNull(findText(root, "PLAY TIME")).parent as LinearLayout
        val actionsRow = requireNotNull(findText(root, "Play")).parent as LinearLayout
        val overview = requireNotNull(findText(root, description))
        val informationColumn = actionsRow.parent as LinearLayout
        assertEquals(informationColumn, factsRow.parent)
        assertEquals(informationColumn, overview.parent)
        assertTrue(informationColumn.indexOfChild(overview) < informationColumn.indexOfChild(actionsRow))
        assertTrue(informationColumn.indexOfChild(actionsRow) < informationColumn.indexOfChild(factsRow))
    }

    private fun findText(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findText(view.getChildAt(index), text)?.let { return it }
            }
        }
        return null
    }

    private fun pixel(drawable: Drawable): Int {
        val bitmap = Bitmap.createBitmap(200, 40, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        drawable.setBounds(0, 0, 200, 40)
        drawable.draw(canvas)
        val result = bitmap.getPixel(8, 20)
        bitmap.recycle()
        return result
    }

    private object PassthroughTokenCipher : SessionTokenCipher {
        override fun encrypt(token: ByteArray, additionalAuthenticatedData: ByteArray) =
            EncryptedSessionToken(token + ByteArray(16), ByteArray(12))

        override fun decrypt(encryptedToken: EncryptedSessionToken, additionalAuthenticatedData: ByteArray): ByteArray =
            encryptedToken.ciphertext.copyOfRange(0, encryptedToken.ciphertext.size - 16)
    }
}
