package com.piggie.tv.ui.rendering

import android.content.Context
import android.os.Build
import com.piggie.tv.BuildConfig

/**
 * Rendering capability is intentionally independent from logical layout size.
 * A 960x540 logical canvas can still be backed by constrained or capable hardware.
 */
enum class TvRenderingProfile {
    RICH,
    FIRE_TV_PERFORMANCE
}

data class TvRenderingDevice(
    val manufacturer: String,
    val model: String
)

enum class FocusIndicatorStrategy {
    CURRENT_CARD_WASH,
    PER_CARD_BORDER,
    ITEM_DECORATION,
    SINGLE_OVERLAY
}

enum class RecyclerPrefetchMode {
    ENABLED,
    REDUCED,
    DISABLED
}

enum class DetailsArchitecture {
    ACTIVITY,
    IN_HOST_FRAGMENT
}

enum class DetailsBackdropMode {
    IMMEDIATE,
    DELAYED,
    NONE
}

/**
 * Debug-only experiment names used by the physical A-I matrix. AUTO is the
 * production policy selected from the device profile.
 */
enum class RenderingExperiment(val wireName: String) {
    AUTO("auto"),
    A_CURRENT("a_current"),
    B_NO_DYNAMIC_BACKDROP("b_no_dynamic_backdrop"),
    C_FLAT_RECTANGULAR_CARDS("c_flat_rectangular_cards"),
    D_SINGLE_FOCUS_OVERLAY("d_single_focus_overlay"),
    D_ITEM_DECORATION("d_item_decoration"),
    E_OPAQUE_BACKGROUNDS("e_opaque_backgrounds"),
    F_SIX_VISIBLE_POSTERS("f_six_visible_posters"),
    G_COMBINED_LOW_COST("g_combined_low_cost"),
    G_COMBINED_PREFETCH_ENABLED("g_combined_prefetch_enabled"),
    G_COMBINED_PREFETCH_DISABLED("g_combined_prefetch_disabled"),
    G_COMBINED_SIX_POSTERS("g_combined_six_posters"),
    H_IN_HOST_DETAILS("h_in_host_details"),
    I_PROGRESSIVE_DETAILS("i_progressive_details"),
    SPAN_9("span_9"),
    SPAN_8("span_8"),
    SPAN_7("span_7"),
    SPAN_6("span_6"),
    PREFETCH_ENABLED("prefetch_enabled"),
    PREFETCH_REDUCED("prefetch_reduced"),
    PREFETCH_DISABLED("prefetch_disabled");

    companion object {
        fun fromWireName(value: String?): RenderingExperiment =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: AUTO
    }
}

data class RendererFeatures(
    val flatRectangularCards: Boolean,
    val cardClipping: Boolean,
    val shadowsAndElevation: Boolean,
    val gradients: Boolean,
    val alphaOverlays: Boolean,
    val dynamicBrowsingBackdrops: Boolean,
    val focusIndicator: FocusIndicatorStrategy,
    val imageEffects: Boolean,
    val transitions: Boolean,
    val opaqueRoots: Boolean,
    val posterSpanCount: Int,
    val prefetchMode: RecyclerPrefetchMode,
    val rgb565Posters: Boolean,
    val detailsArchitecture: DetailsArchitecture,
    val progressiveDetails: Boolean,
    val detailsBackdrop: DetailsBackdropMode
)

object TvRenderingProfileResolver {
    fun resolve(device: TvRenderingDevice): TvRenderingProfile =
        if (
            device.manufacturer.equals("Amazon", ignoreCase = true) &&
            device.model.equals("AFTKM", ignoreCase = true)
        ) {
            TvRenderingProfile.FIRE_TV_PERFORMANCE
        } else {
            TvRenderingProfile.RICH
        }
}

object RendererFeaturePolicy {
    val rich = RendererFeatures(
        flatRectangularCards = false,
        cardClipping = false,
        shadowsAndElevation = false,
        gradients = true,
        alphaOverlays = true,
        dynamicBrowsingBackdrops = true,
        focusIndicator = FocusIndicatorStrategy.CURRENT_CARD_WASH,
        imageEffects = true,
        transitions = false,
        opaqueRoots = false,
        posterSpanCount = 7,
        prefetchMode = RecyclerPrefetchMode.ENABLED,
        rgb565Posters = false,
        detailsArchitecture = DetailsArchitecture.ACTIVITY,
        progressiveDetails = false,
        detailsBackdrop = DetailsBackdropMode.IMMEDIATE
    )

    val fireTvPerformance = rich.copy(
        flatRectangularCards = true,
        gradients = false,
        alphaOverlays = false,
        dynamicBrowsingBackdrops = false,
        focusIndicator = FocusIndicatorStrategy.SINGLE_OVERLAY,
        imageEffects = false,
        opaqueRoots = true,
        posterSpanCount = 6,
        prefetchMode = RecyclerPrefetchMode.REDUCED,
        rgb565Posters = true,
        detailsArchitecture = DetailsArchitecture.IN_HOST_FRAGMENT,
        progressiveDetails = true,
        detailsBackdrop = DetailsBackdropMode.DELAYED
    )

    fun forProfile(profile: TvRenderingProfile): RendererFeatures = when (profile) {
        TvRenderingProfile.RICH -> rich
        TvRenderingProfile.FIRE_TV_PERFORMANCE -> fireTvPerformance
    }

    fun forExperiment(
        profile: TvRenderingProfile,
        experiment: RenderingExperiment
    ): RendererFeatures {
        val current = rich
        return when (experiment) {
            RenderingExperiment.AUTO -> forProfile(profile)
            RenderingExperiment.A_CURRENT -> current
            RenderingExperiment.B_NO_DYNAMIC_BACKDROP -> current.copy(dynamicBrowsingBackdrops = false)
            RenderingExperiment.C_FLAT_RECTANGULAR_CARDS -> current.copy(
                flatRectangularCards = true,
                focusIndicator = FocusIndicatorStrategy.PER_CARD_BORDER
            )
            RenderingExperiment.D_SINGLE_FOCUS_OVERLAY -> current.copy(
                focusIndicator = FocusIndicatorStrategy.SINGLE_OVERLAY
            )
            RenderingExperiment.D_ITEM_DECORATION -> current.copy(
                focusIndicator = FocusIndicatorStrategy.ITEM_DECORATION
            )
            RenderingExperiment.E_OPAQUE_BACKGROUNDS -> current.copy(
                gradients = false,
                alphaOverlays = false,
                opaqueRoots = true
            )
            RenderingExperiment.F_SIX_VISIBLE_POSTERS -> current.copy(posterSpanCount = 6)
            RenderingExperiment.G_COMBINED_LOW_COST -> fireTvPerformance
            RenderingExperiment.G_COMBINED_PREFETCH_ENABLED -> fireTvPerformance.copy(
                prefetchMode = RecyclerPrefetchMode.ENABLED
            )
            RenderingExperiment.G_COMBINED_PREFETCH_DISABLED -> fireTvPerformance.copy(
                prefetchMode = RecyclerPrefetchMode.DISABLED
            )
            RenderingExperiment.G_COMBINED_SIX_POSTERS -> fireTvPerformance.copy(
                posterSpanCount = 6
            )
            RenderingExperiment.H_IN_HOST_DETAILS -> current.copy(
                detailsArchitecture = DetailsArchitecture.IN_HOST_FRAGMENT,
                progressiveDetails = false,
                detailsBackdrop = DetailsBackdropMode.IMMEDIATE
            )
            RenderingExperiment.I_PROGRESSIVE_DETAILS -> current.copy(
                detailsArchitecture = DetailsArchitecture.IN_HOST_FRAGMENT,
                progressiveDetails = true,
                detailsBackdrop = DetailsBackdropMode.DELAYED
            )
            RenderingExperiment.SPAN_9 -> current.copy(posterSpanCount = 9)
            RenderingExperiment.SPAN_8 -> current.copy(posterSpanCount = 8)
            RenderingExperiment.SPAN_7 -> current.copy(posterSpanCount = 7)
            RenderingExperiment.SPAN_6 -> current.copy(posterSpanCount = 6)
            RenderingExperiment.PREFETCH_ENABLED -> current.copy(prefetchMode = RecyclerPrefetchMode.ENABLED)
            RenderingExperiment.PREFETCH_REDUCED -> current.copy(prefetchMode = RecyclerPrefetchMode.REDUCED)
            RenderingExperiment.PREFETCH_DISABLED -> current.copy(prefetchMode = RecyclerPrefetchMode.DISABLED)
        }
    }
}

object TvRenderingRuntime {
    const val DEBUG_EXPERIMENT_EXTRA = "ptv_rendering_experiment"

    @Volatile
    private var debugExperiment = RenderingExperiment.AUTO

    fun configureDebugExperiment(value: String?) {
        debugExperiment = if (BuildConfig.DEBUG) {
            RenderingExperiment.fromWireName(value)
        } else {
            RenderingExperiment.AUTO
        }
    }

    fun profile(): TvRenderingProfile = TvRenderingProfileResolver.resolve(
        TvRenderingDevice(Build.MANUFACTURER, Build.MODEL)
    )

    fun features(): RendererFeatures =
        RendererFeaturePolicy.forExperiment(profile(), debugExperiment)

    fun experiment(): RenderingExperiment = debugExperiment

    fun initialize(@Suppress("UNUSED_PARAMETER") context: Context) {
        // Forces no I/O. Kept as an explicit application initialization seam.
        profile()
    }
}
