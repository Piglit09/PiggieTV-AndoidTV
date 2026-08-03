package com.piggie.tv.ui.layout

import android.content.Context
import android.os.Build

/**
 * Canonical logical TV profiles. Raw display pixels must never select a profile.
 *
 * Fire TV Stick 4K Max reports a 1920x1080 app surface at 2.0 density, which is
 * a 960x540 logical canvas and therefore intentionally selects [TV_COMPACT].
 */
enum class TvLayoutProfile {
    TV_COMPACT,
    TV_STANDARD,
    TV_LARGE
}

data class TvLogicalViewport(
    val widthDp: Int,
    val heightDp: Int,
    val smallestWidthDp: Int,
    val manufacturer: String = "",
    val model: String = ""
)

object TvLayoutProfileResolver {
    const val CANONICAL_COMPACT_WIDTH_DP = 960
    const val CANONICAL_COMPACT_HEIGHT_DP = 540

    fun resolve(viewport: TvLogicalViewport): TvLayoutProfile = when {
        // AFTKM is explicitly kept compact unless its logical viewport is larger
        // than the canonical TV surface. This avoids treating its 4K output mode
        // as a 3840dp tablet canvas.
        viewport.manufacturer.equals("Amazon", ignoreCase = true) &&
            viewport.model.equals("AFTKM", ignoreCase = true) &&
            viewport.widthDp <= CANONICAL_COMPACT_WIDTH_DP &&
            viewport.heightDp <= CANONICAL_COMPACT_HEIGHT_DP -> TvLayoutProfile.TV_COMPACT

        viewport.widthDp <= 1_000 || viewport.heightDp <= 600 -> TvLayoutProfile.TV_COMPACT
        viewport.widthDp <= 1_440 || viewport.heightDp <= 810 -> TvLayoutProfile.TV_STANDARD
        else -> TvLayoutProfile.TV_LARGE
    }

    fun from(context: Context): Pair<TvLayoutProfile, TvLogicalViewport> {
        val configuration = context.resources.configuration
        val metrics = context.resources.displayMetrics
        val widthDp = configuration.screenWidthDp.takeIf { it > 0 }
            ?: (metrics.widthPixels / metrics.density).toInt()
        val heightDp = configuration.screenHeightDp.takeIf { it > 0 }
            ?: (metrics.heightPixels / metrics.density).toInt()
        val viewport = TvLogicalViewport(
            widthDp = widthDp,
            heightDp = heightDp,
            smallestWidthDp = configuration.smallestScreenWidthDp,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL
        )
        return resolve(viewport) to viewport
    }
}

/** Reviewable compact-profile contract mirrored by the compact dimension resources. */
object TvCompactTargets {
    const val CANVAS_WIDTH_DP = 960
    const val CANVAS_HEIGHT_DP = 540
    const val HORIZONTAL_MARGIN_DP = 40
    const val HERO_HEIGHT_DP = 215
    const val POSTER_WIDTH_DP = 100
    const val LANDSCAPE_WIDTH_DP = 184
    const val SQUARE_WIDTH_DP = 112
    const val PRIMARY_PLAYER_CONTROL_DP = 64
    const val SECONDARY_PLAYER_CONTROL_DP = 44
    const val DIALOG_WIDTH_DP = 360
}
