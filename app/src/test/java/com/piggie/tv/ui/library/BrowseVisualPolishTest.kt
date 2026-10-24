package com.piggie.tv.ui.library

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
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
import com.piggie.tv.ui.rendering.TvRenderingRuntime
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "sw540dp-w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowseVisualPolishTest {
    private lateinit var store: SecureSessionStore
    private lateinit var server: MockWebServer
    private var controller: ActivityController<LibraryBrowserActivity>? = null
    private val originalManufacturer = Build.MANUFACTURER
    private val originalModel = Build.MODEL

    @Before fun prepare() {
        ShadowBuild.setManufacturer("Amazon")
        ShadowBuild.setModel("AFTKM")
        TvRenderingRuntime.configureDebugExperiment(null)
        SecureSessionStore.installTokenCipherFactoryForTests { PassthroughTokenCipher }
        store = SecureSessionStore(RuntimeEnvironment.getApplication())
        server = MockWebServer()
        server.start()
    }

    @After fun cleanUp() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        server.shutdown()
        store.clear()
        AuthSessionCoordinator.resetForTests()
        SecureSessionStore.installTokenCipherFactoryForTests(null)
        ShadowBuild.setManufacturer(originalManufacturer)
        ShadowBuild.setModel(originalModel)
        TvRenderingRuntime.configureDebugExperiment(null)
    }

    @Test fun focusRevealsBothProvenTitlesWithoutMovingArtworkCardsOrRows() {
        val browser = launch()
        val recycler = recycler(browser)
        browser.findViewById<Button>(R.id.ptv_library_view).requestFocus()
        val snapshots = (0..4).associateWith { geometry(card(recycler, it)) }
        listOf(0, 1, 2, 3).forEach { position ->
            val card = card(recycler, position)
            val title = card.findViewById<TextView>(R.id.card_title)
            assertEquals(1, title.maxLines)
            assertTrue(card.requestFocus())
            layout(browser)
            assertEquals("focused title must have its two-line region", 2, title.maxLines)
            assertComplete(title)
            snapshots.forEach { (index, bounds) -> assertEquals(bounds, geometry(card(recycler, index))) }
            assertEquals(1f, card.scaleX)
            assertEquals(1f, card.scaleY)
        }
    }

    @Test fun exceptionallyLongTitleUsesStaticBoundedFallbackAndCleansUpOnBlurRebindAndDetach() {
        val browser = launch()
        val recycler = recycler(browser)
        val longCard = card(recycler, 4)
        val title = longCard.findViewById<TextView>(R.id.card_title)
        browser.findViewById<Button>(R.id.ptv_library_view).requestFocus()
        layout(browser)
        assertEquals(1, title.maxLines)
        assertTrue("unfocused pathological title must be restrained", title.layout.getEllipsisCount(0) > 0)
        val before = geometry(longCard)
        longCard.requestFocus()
        layout(browser)
        val overlay = requireNotNull(browser.window.decorView.findViewWithTag<TextView>("ptv_browse_full_title"))
        assertEquals(LONG_TITLE, overlay.text.toString())
        assertFalse(overlay.isFocusable)
        assertNull(overlay.animation)
        assertComplete(overlay)
        assertTrue(overlay.width <= 720)
        assertTrue(overlay.height <= 360)
        assertEquals(before, geometry(longCard))
        card(recycler, 0).requestFocus()
        layout(browser)
        assertNull(browser.window.decorView.findViewWithTag<View>("ptv_browse_full_title"))
        longCard.requestFocus()
        layout(browser)
        recycler.adapter!!.notifyItemChanged(4)
        layout(browser)
        assertTrue(descendants(browser.window.decorView).count { it.tag == "ptv_browse_full_title" } <= 1)
        recycler.adapter = null
        layout(browser)
        assertNull(browser.window.decorView.findViewWithTag<View>("ptv_browse_full_title"))
    }

    @Test fun moviesComfortAndCompactFillUsefulWidthWithReadablePostersAndExplicitRowClearance() {
        val browser = launch()
        val viewButton = browser.findViewById<Button>(R.id.ptv_library_view)
        viewButton.performClick()
        layout(browser)
        assertDensity(browser, columns = 6, posterWidth = 272, expectedGap = 34..35)
        viewButton.performClick()
        layout(browser)
        assertDensity(browser, columns = 7, posterWidth = 232, expectedGap = 30..31)
    }

    @Test fun multirowTravelAndDetailsReturnRetainCardIdentityAndScrollPosition() {
        val browser = launch()
        browser.findViewById<Button>(R.id.ptv_library_view).performClick()
        layout(browser)
        val recycler = recycler(browser)
        assertEquals(20, recycler.adapter!!.itemCount)
        val first = card(recycler, 0)
        first.requestFocus()
        layout(browser)
        var focused = first
        listOf(View.FOCUS_RIGHT to 1, View.FOCUS_DOWN to 7, View.FOCUS_LEFT to 6,
            View.FOCUS_UP to 0, View.FOCUS_DOWN to 6, View.FOCUS_DOWN to 12,
            View.FOCUS_DOWN to 18, View.FOCUS_RIGHT to 19).forEach { (direction, expected) ->
            focused = requireNotNull(focused.focusSearch(direction))
            assertTrue(focused.requestFocus())
            layout(browser)
            assertEquals("movie-$expected", focused.getTag(R.id.ptv_discovery_item_id))
            assertTrue("travel must transfer actual view focus", focused.hasFocus())
        }
        assertEquals("movie-19", browser.window.decorView.findFocus()?.getTag(R.id.ptv_discovery_item_id))
        val manager = recycler.layoutManager as GridLayoutManager
        val firstVisible = manager.findFirstVisibleItemPosition()
        val offset = manager.findViewByPosition(firstVisible)!!.top
        assertTrue(firstVisible > 0)
        focused.performClick()
        assertTrue(shadowOf(browser).nextStartedActivity.component!!.className.endsWith("MediaDetailsActivity"))
        controller!!.pause().stop().restart().start().resume().visible()
        layout(browser)
        assertEquals("movie-19", browser.window.decorView.findFocus()?.getTag(R.id.ptv_discovery_item_id))
        assertEquals(firstVisible, manager.findFirstVisibleItemPosition())
        assertEquals(offset, manager.findViewByPosition(firstVisible)!!.top)
    }

    @Test fun scrolledMoviesArtworkCannotPaintOverHeadingOrControlsInEitherDensity() {
        val browser = launch()
        val viewButton = browser.findViewById<Button>(R.id.ptv_library_view)
        listOf(6, 7).forEach { columns ->
            viewButton.performClick()
            layout(browser)
            val recycler = recycler(browser)
            val first = card(recycler, 0)
            assertTrue(first.requestFocus())
            layout(browser)
            val secondRow = requireNotNull(first.focusSearch(View.FOCUS_DOWN))
            assertTrue(secondRow.requestFocus())
            layout(browser)
            assertEquals("movie-$columns", secondRow.getTag(R.id.ptv_discovery_item_id))
            assertTrue("the regression needs a partially scrolled first row", card(recycler, 0).top < 0)
            assertTrue("focused card must fit below the inset", secondRow.top >= recycler.paddingTop)
            assertTrue("focused card must fit above the bottom inset", secondRow.bottom <= recycler.height - recycler.paddingBottom)
            val firstVisible = (recycler.layoutManager as GridLayoutManager).findFirstVisibleItemPosition()
            val offset = card(recycler, firstVisible).top
            assertArtworkInsideViewport(browser, recycler)
            secondRow.performClick()
            assertTrue(shadowOf(browser).nextStartedActivity.component!!.className.endsWith("MediaDetailsActivity"))
            controller!!.pause().stop().restart().start().resume().visible()
            layout(browser)
            assertEquals("movie-$columns", browser.window.decorView.findFocus()?.getTag(R.id.ptv_discovery_item_id))
            assertEquals(offset, card(recycler, firstVisible).top)
            assertArtworkInsideViewport(browser, recycler)
        }
    }

    private fun assertArtworkInsideViewport(browser: Activity, recycler: RecyclerView) {
        // A distinctive local image makes actual drawing outside the viewport observable.
        // Checking only layout coordinates misses this bug: offscreen rows must still lay out.
        descendants(recycler).filterIsInstance<ImageView>().filter { it.id == R.id.card_image }
            .forEach { it.setImageDrawable(ColorDrawable(Color.MAGENTA)) }
        val decor = browser.window.decorView as ViewGroup
        val viewport = Rect(0, 0, recycler.width, recycler.height)
        decor.offsetDescendantRectToMyCoords(recycler, viewport)
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        try {
            decor.draw(Canvas(bitmap))
            var leakedPixels = 0
            var visiblePixels = 0
            for (y in 0 until viewport.top) for (x in viewport.left until viewport.right) {
                if (bitmap.getPixel(x, y) == Color.MAGENTA) leakedPixels++
            }
            for (y in viewport.top until minOf(viewport.bottom, bitmap.height)) {
                if (bitmap.getPixel(viewport.left + 100, y) == Color.MAGENTA) visiblePixels++
            }
            assertTrue("the real card artwork must have rendered", visiblePixels > 0)
            assertEquals("scrolled artwork must not cover the Movies header or controls", 0, leakedPixels)
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun showsBrowserGetsReadableTitlesWithoutMoviesPosterSizing() {
        assertShowsFamilyTitle(DiscoveryPage.SHOWS)
    }

    @Test fun animeBrowserGetsReadableTitlesWithoutMoviesPosterSizing() {
        assertShowsFamilyTitle(DiscoveryPage.ANIME)
    }

    @Test fun cartoonsBrowserGetsReadableTitlesWithoutMoviesPosterSizing() {
        assertShowsFamilyTitle(DiscoveryPage.CARTOONS)
    }

    private fun assertShowsFamilyTitle(page: DiscoveryPage) {
        val browser = launch(page)
        val card = card(recycler(browser), 2)
        card.requestFocus()
        layout(browser)
        assertEquals(200, card.findViewById<View>(R.id.card_artwork).width)
        assertEquals(2, card.findViewById<TextView>(R.id.card_title).maxLines)
        assertComplete(card.findViewById(R.id.card_title))
    }

    private fun assertDensity(browser: LibraryBrowserActivity, columns: Int, posterWidth: Int, expectedGap: IntRange) {
        val recycler = recycler(browser)
        val manager = recycler.layoutManager as GridLayoutManager
        assertEquals(columns, manager.spanCount)
        val first = card(recycler, 0)
        val next = card(recycler, 1)
        assertEquals(1840, recycler.width)
        assertEquals(posterWidth, first.width)
        assertEquals(posterWidth, first.findViewById<View>(R.id.card_artwork).width)
        assertTrue(next.left - first.right in expectedGap)
        val secondRow = card(recycler, columns)
        assertTrue("focus ring needs vertical clearance", secondRow.top - first.bottom >= 16)
        println("DENSITY columns=$columns poster=${first.width}x${first.findViewById<View>(R.id.card_artwork).height} cell=${next.left-first.left} gap=${next.left-first.right} rowGap=${secondRow.top-first.bottom} grid=${recycler.width}x${recycler.height} top=${recycler.top}")
    }

    private fun launch(page: DiscoveryPage = DiscoveryPage.MOVIES): LibraryBrowserActivity {
        val name = when (page) {
            DiscoveryPage.SHOWS -> "Shows"
            DiscoveryPage.ANIME -> "Anime"
            DiscoveryPage.CARTOONS -> "Cartoons"
            else -> "Movies"
        }
        val type = if (page == DiscoveryPage.MOVIES) "Movie" else "Series"
        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"root","Name":"$name","Type":"CollectionFolder"}]}"""))
        val titles = listOf("Dawn", "The Quiet Valley", "Northwind Chronicles", "North Harbor Friends", LONG_TITLE)
        val items = (0 until 20).joinToString(",") { index ->
            """{"Id":"movie-$index","Name":"${titles.getOrElse(index) { "Fictional Movie $index" }}","Type":"$type","OfficialRating":"PG"}"""
        }
        server.enqueue(MockResponse().setBody("""{"Items":[$items]}"""))
        val session = NativeSession("token", "server", "user", "User", server.url("/").toString().removeSuffix("/"))
        assertTrue(AuthSessionCoordinator.commitAuthenticated(AuthSessionCoordinator.beginAttempt(), session) {
            store.save(session, SessionOrigin.STORED)
        })
        val launcher = Robolectric.buildActivity(Activity::class.java).setup().get()
        LibraryBrowserActivity.start(launcher, requireNotNull(DiscoveryLibraryRoutePolicy.browseRequest(page)))
        controller = Robolectric.buildActivity(LibraryBrowserActivity::class.java, shadowOf(launcher).nextStartedActivity)
            .create().start().resume().visible()
        val browser = controller!!.get()
        repeat(100) {
            layout(browser)
            val recycler = descendants(browser.window.decorView).filterIsInstance<RecyclerView>().firstOrNull()
            if (recycler?.findViewHolderForAdapterPosition(4) != null) return browser
            Thread.sleep(20)
        }
        error("local fictional browser did not render")
    }

    private fun assertComplete(view: TextView) {
        val textLayout = requireNotNull(view.layout)
        assertTrue(textLayout.lineCount <= view.maxLines)
        assertEquals(view.text.length, textLayout.getLineEnd(textLayout.lineCount - 1))
        for (line in 0 until textLayout.lineCount) assertEquals(0, textLayout.getEllipsisCount(line))
        assertTrue(textLayout.height <= view.height - view.paddingTop - view.paddingBottom)
    }

    private fun geometry(card: View) = listOf(
        Rect(card.left, card.top, card.right, card.bottom),
        card.findViewById<View>(R.id.card_artwork).let { Rect(it.left, it.top, it.right, it.bottom) }
    )
    private fun layout(browser: Activity) {
        shadowOf(Looper.getMainLooper()).idle()
        browser.window.decorView.apply {
            measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1920, 1080)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun recycler(browser: Activity) = descendants(browser.window.decorView).filterIsInstance<RecyclerView>().first()
    private fun card(recycler: RecyclerView, position: Int) = requireNotNull(recycler.findViewHolderForAdapterPosition(position)).itemView
    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
    }
    private object PassthroughTokenCipher : SessionTokenCipher {
        override fun encrypt(token: ByteArray, additionalAuthenticatedData: ByteArray) = EncryptedSessionToken(token + ByteArray(16), ByteArray(12))
        override fun decrypt(encryptedToken: EncryptedSessionToken, additionalAuthenticatedData: ByteArray) = encryptedToken.ciphertext.copyOfRange(0, encryptedToken.ciphertext.size - 16)
    }
    companion object {
        const val LONG_TITLE = "The Extraordinary Adventures of the Northwind Explorers Across the Seven Forgotten Kingdoms Beyond the Last Harbor and the Silent Mountain"
    }
}
