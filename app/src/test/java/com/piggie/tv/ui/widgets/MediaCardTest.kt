package com.piggie.tv.ui.widgets

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.ui.rendering.FocusGradientShaderCache
import com.piggie.tv.ui.rendering.MediaCardFocusBorderStyle
import com.piggie.tv.ui.rendering.TvFocusIndicator
import com.piggie.tv.ui.rendering.TvRenderingRuntime
import com.piggie.tv.util.dim
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBuild
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [30],
    qualifiers = "sw540dp-w960dp-h540dp-land-xhdpi"
)
class MediaCardTest {
    private lateinit var activity: Activity
    private lateinit var parent: FrameLayout

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        parent = FrameLayout(activity)
        activity.setContentView(parent)
    }

    @After
    fun tearDown() {
        activity.finish()
    }

    @Test
    fun compactBucketUsesProductionArtworkDimensions() {
        assertArtworkSize(MediaCardPresentation.POSTER, widthDp = 100, heightDp = 150)
        assertArtworkSize(MediaCardPresentation.LANDSCAPE, widthDp = 184, heightDp = 104)
        assertArtworkSize(MediaCardPresentation.SQUARE, widthDp = 112, heightDp = 112)
    }

    @Test
    fun factoryRegistersFullCardForFocusWithoutChangingArtworkGeometry() {
        val card = MediaCardFactory.createView(parent, MediaCardPresentation.POSTER)
        parent.addView(card)
        layoutParent()

        val artwork = card.findViewById<View>(R.id.card_artwork)
        val title = card.findViewById<TextView>(R.id.card_title)
        assertSame(card, card.getTag(R.id.focus_artwork_view))
        assertTrue(artwork.isAttachedToWindow)

        val cardLocation = IntArray(2)
        card.getLocationOnScreen(cardLocation)
        val expectedCardBounds = Rect(
            cardLocation[0],
            cardLocation[1],
            cardLocation[0] + card.width,
            cardLocation[1] + card.height
        )
        assertEquals(expectedCardBounds, TvFocusIndicator.artworkBoundsOnScreen(card))

        val titleLocation = IntArray(2)
        title.getLocationOnScreen(titleLocation)
        val artworkLocation = IntArray(2)
        artwork.getLocationOnScreen(artworkLocation)
        assertTrue(
            "The title must remain attached below the unchanged artwork bounds",
            titleLocation[1] >= artworkLocation[1] + artwork.height
        )
        assertEquals(1f, card.scaleX)
        assertEquals(1f, card.scaleY)
        assertEquals(0f, card.elevation)

        card.onFocusChangeListener.onFocusChange(card, true)
        assertEquals(1f, card.scaleX)
        assertEquals(1f, card.scaleY)
        assertEquals(0f, card.elevation)
        assertEquals(0f, card.translationX)
        assertEquals(0f, card.translationY)
        assertEquals(0f, card.translationZ)
        assertEquals(1f, card.alpha)
        assertNull(card.animation)
    }

    @Test
    fun fireTvPerformanceCardsClipRoundedArtworkWithoutGlossLayer() {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        try {
            ShadowBuild.setManufacturer("Amazon")
            ShadowBuild.setModel("AFTKM")
            com.piggie.tv.ui.rendering.TvRenderingRuntime.configureDebugExperiment(null)

            val card = MediaCardFactory.createView(parent, MediaCardPresentation.POSTER)
            val artwork = card.findViewById<FrameLayout>(R.id.card_artwork)
            val progress = card.findViewById<ProgressBar>(R.id.card_progress)

            assertTrue(artwork.clipToOutline)
            assertNull(artwork.foreground)
            assertNull("rounded clipping must not require an under-image alpha draw", artwork.background)
            assertNotNull("AFTKM must retain a static ice-card body", card.background)
            assertNotNull("AFTKM must retain a full-card static border", card.foreground)
            assertNotNull("AFTKM must retain static gradient progress", progress.progressDrawable)
            val surface = card.background as LayerDrawable
            val metadataFill = surface.getDrawable(0) as GradientDrawable
            assertEquals(1, surface.numberOfLayers)
            assertEquals(activity.dim(R.dimen.tv_card_metadata_surface_height), surface.getLayerHeight(0))
            assertTrue(Color.alpha(requireNotNull(metadataFill.color).defaultColor) in 89..122)
            assertEquals(
                activity.resources.getDimension(R.dimen.tv_media_artwork_corner_radius),
                (card.foreground as GradientDrawable).cornerRadius
            )
            assertEquals(0, card.paddingLeft)
            assertEquals(0, card.paddingRight)
            assertEquals(1f, card.scaleX)
            assertEquals(1f, card.scaleY)
            assertEquals(0f, card.elevation)
        } finally {
            com.piggie.tv.ui.rendering.TvRenderingRuntime.configureDebugExperiment(null)
            ShadowBuild.setManufacturer(manufacturer)
            ShadowBuild.setModel(model)
        }
    }

    @Test
    fun flatFireFallbackHasNoIceSurfaceAndKeepsProgressSlotInactive() {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        try {
            ShadowBuild.setManufacturer("Amazon")
            ShadowBuild.setModel("AFTKM")
            TvRenderingRuntime.configureDebugExperiment("flat_fire_fallback")

            val card = MediaCardFactory.createView(parent, MediaCardPresentation.POSTER)
            val progress = card.findViewById<ProgressBar>(R.id.card_progress)

            assertNull(card.background)
            assertNull(card.foreground)
            assertEquals(View.INVISIBLE, progress.visibility)
            assertEquals(0, card.paddingLeft)
            assertEquals(0, card.paddingRight)
        } finally {
            TvRenderingRuntime.configureDebugExperiment(null)
            ShadowBuild.setManufacturer(manufacturer)
            ShadowBuild.setModel(model)
        }
    }

    @Test
    fun progressSlotKeepsCardHeightStableAndReusesItsStaticGradient() {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        try {
            ShadowBuild.setManufacturer("Amazon")
            ShadowBuild.setModel("AFTKM")
            TvRenderingRuntime.configureDebugExperiment("safe_static_glass")

            val card = MediaCardFactory.createView(parent, MediaCardPresentation.POSTER)
            val holder = MediaCardHolder(card)
            val api = JellyfinNativeApi(activity)
            val session = NativeSession("token", "server", "user", "Codex", "https://example.test")
            val cachedProgressStyle = holder.progressStyle
            val common = mediaItem(
                id = "movie",
                title = "Same Movie",
                position = 500,
                runtime = 1_000,
                rating = 8f,
                certification = "PG"
            )

            MediaCardFactory.bindView(
                holder,
                common,
                MediaCardPresentation.POSTER,
                session,
                api
            )
            measureCard(card)
            val heightWithProgress = card.measuredHeight
            assertEquals(View.VISIBLE, holder.progress?.visibility)
            assertSame(cachedProgressStyle, holder.progress?.progressDrawable)
            assertEquals(0, holder.progress?.paddingTop)

            MediaCardFactory.bindView(
                holder,
                common.copy(playbackPositionTicks = 0),
                MediaCardPresentation.POSTER,
                session,
                api
            )
            measureCard(card)

            assertEquals(View.INVISIBLE, holder.progress?.visibility)
            assertEquals(heightWithProgress, card.measuredHeight)
            assertSame(cachedProgressStyle, holder.progress?.progressDrawable)
        } finally {
            TvRenderingRuntime.configureDebugExperiment(null)
            ShadowBuild.setManufacturer(manufacturer)
            ShadowBuild.setModel(model)
        }
    }

    @Test
    fun focusBorderUsesMeasuredGradientAndCachesShaderForEqualArtworkSize() {
        assertEquals(Color.parseColor("#9C4DFF"), MediaCardFocusBorderStyle.gradientStartColor)
        assertEquals(Color.parseColor("#FF38C7"), MediaCardFocusBorderStyle.gradientEndColor)
        assertEquals(9f * activity.resources.displayMetrics.density, activity.resources.getDimension(R.dimen.tv_media_artwork_corner_radius))

        val cache = FocusGradientShaderCache()
        val posterShader = cache.shaderFor(width = 100, height = 150)
        assertNotNull(posterShader)
        assertSame(posterShader, cache.shaderFor(width = 100, height = 150))
        assertSame(posterShader, cache.shaderFor(width = 100, height = 150))

        val landscapeShader = cache.shaderFor(width = 184, height = 104)
        assertNotNull(landscapeShader)
        assertNotSame(posterShader, landscapeShader)
        assertNull(cache.shaderFor(width = 0, height = 150))
    }

    @Test
    fun bindResetsEveryMutableMediaAndViewMoreStateWithoutReinflatingProgressStyle() {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        try {
            ShadowBuild.setManufacturer("Amazon")
            ShadowBuild.setModel("AFTKM")
            TvRenderingRuntime.configureDebugExperiment("safe_static_glass")

            val card = MediaCardFactory.createView(parent, MediaCardPresentation.POSTER)
            val holder = MediaCardHolder(card)
            val api = JellyfinNativeApi(activity)
            val session = NativeSession("token", "server", "user", "Codex", "https://example.test")
            val cachedSurface = holder.cardSurface
            val cachedForeground = holder.cardForeground
            val cachedArtworkSurface = holder.artworkSurface
            val cachedArtworkHighlight = holder.artworkHighlight
            val cachedProgressStyle = holder.progressStyle

            MediaCardFactory.bindView(
                holder,
                mediaItem(
                    id = "resume",
                    title = "Resume Me",
                    imageTag = null,
                    position = 500,
                    runtime = 1_000,
                    rating = 8.7f,
                    certification = "PG-13"
                ),
                MediaCardPresentation.POSTER,
                session,
                api
            )
            assertEquals(View.VISIBLE, holder.badge.visibility)
            assertEquals(View.VISIBLE, holder.progress?.visibility)
            assertEquals(View.VISIBLE, holder.rating.visibility)
            assertSame(cachedProgressStyle, holder.progress?.progressDrawable)

            // Simulate all mutable leakage risks before this holder is rebound as View More.
            card.background = ColorDrawable(Color.RED)
            card.foreground = ColorDrawable(Color.WHITE)
            card.alpha = 0.2f
            card.scaleX = 1.2f
            card.scaleY = 1.2f
            card.elevation = 12f
            card.translationX = 4f
            card.translationY = 5f
            card.translationZ = 6f
            card.isActivated = true
            card.isSelected = true
            card.isPressed = true
            holder.artwork.background = ColorDrawable(Color.CYAN)
            holder.artwork.foreground = ColorDrawable(Color.MAGENTA)
            holder.artwork.alpha = 0.3f
            holder.artwork.isActivated = true
            holder.artwork.isSelected = true
            holder.title.visibility = View.INVISIBLE
            holder.title.alpha = 0.2f
            holder.subtitle.visibility = View.GONE
            holder.subtitle.alpha = 0.2f
            holder.badge.alpha = 0.2f
            holder.rating.alpha = 0.2f
            holder.ratingStar.alpha = 0.2f
            holder.image.alpha = 0.4f
            holder.image.visibility = View.INVISIBLE
            holder.image.tag = "stale-item"
            holder.image.contentDescription = "stale artwork"
            holder.progress?.apply {
                progressDrawable = ColorDrawable(Color.GREEN)
                secondaryProgress = 700
                alpha = 0.2f
            }

            MediaCardFactory.bindView(
                holder,
                mediaItem(id = "more", title = "All Movies", type = "ViewMore"),
                MediaCardPresentation.POSTER,
                session,
                api
            )

            assertSame(cachedSurface, card.background)
            assertSame(cachedForeground, card.foreground)
            assertEquals(1f, card.alpha)
            assertEquals(1f, card.scaleX)
            assertEquals(1f, card.scaleY)
            assertEquals(0f, card.elevation)
            assertEquals(0f, card.translationX)
            assertEquals(0f, card.translationY)
            assertEquals(0f, card.translationZ)
            assertTrue(!card.isActivated)
            assertTrue(!card.isSelected)
            assertTrue(!card.isPressed)
            assertSame(cachedArtworkSurface, holder.artwork.background)
            assertSame(cachedArtworkHighlight, holder.artwork.foreground)
            assertEquals(1f, holder.artwork.alpha)
            assertTrue(!holder.artwork.isActivated)
            assertTrue(!holder.artwork.isSelected)
            assertEquals(View.VISIBLE, holder.title.visibility)
            assertEquals(1f, holder.title.alpha)
            assertEquals(View.VISIBLE, holder.subtitle.visibility)
            assertEquals(1f, holder.subtitle.alpha)
            assertEquals(View.GONE, holder.badge.visibility)
            assertEquals("", holder.badge.text.toString())
            assertEquals(1f, holder.badge.alpha)
            assertEquals(View.GONE, holder.rating.visibility)
            assertEquals("", holder.rating.text.toString())
            assertEquals(1f, holder.rating.alpha)
            assertEquals(View.GONE, holder.ratingStar.visibility)
            assertEquals(1f, holder.ratingStar.alpha)
            assertEquals(View.INVISIBLE, holder.progress?.visibility)
            assertEquals(0, holder.progress?.progress)
            assertEquals(0, holder.progress?.secondaryProgress)
            assertTrue(holder.progress?.isIndeterminate == false)
            assertSame(cachedProgressStyle, holder.progress?.progressDrawable)
            assertEquals(1f, holder.progress?.alpha)
            assertNull(holder.progress?.animation)
            assertEquals(ImageView.ScaleType.CENTER_INSIDE, holder.image.scaleType)
            assertEquals(1f, holder.image.alpha)
            assertEquals(View.VISIBLE, holder.image.visibility)
            assertEquals("more", holder.image.tag)
            assertEquals("View more", holder.image.contentDescription)
            assertEquals("View More", holder.title.text.toString())
            assertEquals("All Movies", holder.subtitle.text.toString())
            assertEquals("View More, All Movies", card.contentDescription.toString())

            // Rebind View More as plain media: its icon scale/background and all old metadata reset.
            MediaCardFactory.bindView(
                holder,
                mediaItem(id = "plain", title = "Plain Movie"),
                MediaCardPresentation.POSTER,
                session,
                api
            )
            assertEquals(ImageView.ScaleType.CENTER_CROP, holder.image.scaleType)
            assertNull(holder.image.background)
            assertSame(holder.placeholder, holder.image.drawable)
            assertEquals(View.VISIBLE, holder.image.visibility)
            assertEquals("plain", holder.image.tag)
            assertEquals("Plain Movie artwork", holder.image.contentDescription)
            assertEquals("Plain Movie", holder.title.text.toString())
            assertEquals("", holder.subtitle.text.toString())
            assertEquals(View.GONE, holder.badge.visibility)
            assertEquals(View.GONE, holder.rating.visibility)
            assertEquals(View.INVISIBLE, holder.progress?.visibility)
            assertEquals(0, holder.progress?.progress)
            assertSame(cachedProgressStyle, holder.progress?.progressDrawable)
            assertEquals("Plain Movie", card.contentDescription.toString())

            MediaCardFactory.bindView(
                holder,
                mediaItem(id = "watched", title = "Watched Movie", isPlayed = true),
                MediaCardPresentation.POSTER,
                session,
                api
            )
            assertEquals(View.VISIBLE, holder.badge.visibility)
            assertEquals("WATCHED", holder.badge.text.toString())
            assertEquals(View.INVISIBLE, holder.progress?.visibility)

            MediaCardFactory.recycleView(holder)
            assertEquals("", holder.title.text.toString())
            assertEquals("", holder.subtitle.text.toString())
            assertEquals(View.INVISIBLE, holder.progress?.visibility)
            assertEquals(0, holder.progress?.progress)
            assertSame(cachedProgressStyle, holder.progress?.progressDrawable)
            assertNull(card.contentDescription)
            assertNull(holder.image.contentDescription)
            assertNull(holder.image.tag)
            assertSame(holder.placeholder, holder.image.drawable)
            assertEquals(View.VISIBLE, holder.image.visibility)
            assertEquals(ImageView.ScaleType.CENTER_CROP, holder.image.scaleType)
            assertSame(cachedArtworkSurface, holder.artwork.background)
            assertSame(cachedArtworkHighlight, holder.artwork.foreground)
            assertTrue(!card.isPressed)
            assertSame(cachedForeground, card.foreground)
        } finally {
            TvRenderingRuntime.configureDebugExperiment(null)
            ShadowBuild.setManufacturer(manufacturer)
            ShadowBuild.setModel(model)
        }
    }

    private fun assertArtworkSize(
        presentation: MediaCardPresentation,
        widthDp: Int,
        heightDp: Int
    ) {
        val card = MediaCardFactory.createView(parent, presentation)
        val artwork = card.findViewById<View>(R.id.card_artwork)
        val density = activity.resources.displayMetrics.density

        assertEquals(widthDp, (artwork.layoutParams.width / density).roundToInt())
        assertEquals(heightDp, (artwork.layoutParams.height / density).roundToInt())
    }

    private fun layoutParent() {
        val density = activity.resources.displayMetrics.density
        val width = (960 * density).roundToInt()
        val height = (540 * density).roundToInt()
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        parent.layout(0, 0, width, height)
    }

    private fun mediaItem(
        id: String,
        title: String,
        type: String = "Movie",
        imageTag: String? = null,
        position: Long = 0,
        runtime: Long = 0,
        rating: Float? = null,
        certification: String? = null,
        isPlayed: Boolean = false
    ) = MediaItem(
        id = id,
        title = title,
        type = type,
        year = null,
        imageTag = imageTag,
        seriesName = null,
        episodeLabel = null,
        playbackPositionTicks = position,
        runtimeTicks = runtime,
        communityRating = rating,
        officialRating = certification,
        isPlayed = isPlayed
    )

    private fun measureCard(card: View) {
        val width = activity.resources.getDimensionPixelSize(R.dimen.tv_poster_width)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
    }
}
