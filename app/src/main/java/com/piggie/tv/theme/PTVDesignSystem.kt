package com.piggie.tv.theme

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.piggie.tv.R
import com.piggie.tv.ui.rendering.TvFocusIndicator
import com.piggie.tv.ui.rendering.TvRenderingProfile
import com.piggie.tv.ui.rendering.TvRenderingRuntime
import com.piggie.tv.util.dim
import com.piggie.tv.util.dimFloat
import com.piggie.tv.util.setTextSizeRes

object PTVColors {
    val cinemaInk = Color.parseColor("#050912")
    val cinemaNavy = Color.parseColor("#0B1020")
    val background = Color.parseColor("#0F041C")
    val backgroundStart = Color.parseColor("#1A0A2E")
    val backgroundEnd = Color.parseColor("#0F041C")
    val primary = Color.parseColor("#8E44AD")
    val primaryVariant = Color.parseColor("#9B59B6")
    val accent = Color.parseColor("#00F2FF") // Cyan focus
    val textPrimary = Color.parseColor("#FFFFFF")
    val textSecondary = Color.parseColor("#BBBBBB")
    val textMuted = Color.parseColor("#777777")
    val cardBackground = Color.parseColor("#221136")
    val buttonSecondary = Color.parseColor("#331A4D")
}

/** Glass 0 leaves the scene uncovered; the remaining tiers protect increasingly important UI. */
enum class PTVGlassTier { ZERO, LIGHT, STANDARD, ELEVATED, MODAL }

data class PTVMaterialSpec(
    val surfaceColor: Int,
    val borderColor: Int,
    val highlightColor: Int,
    val blurRadiusPx: Int,
    val shadowElevationPx: Float,
    val focusScale: Float
)

/**
 * Native materials are composed once at view creation. Fire TV selects a stronger static tint;
 * both profiles use zero live blur, zero elevation and the existing border-only focus geometry.
 */
object PTVMaterials {
    fun spec(
        context: Context,
        tier: PTVGlassTier,
        profile: TvRenderingProfile = TvRenderingRuntime.profile()
    ): PTVMaterialSpec {
        val colors = when (tier) {
            PTVGlassTier.ZERO -> listOf(
                R.color.ptv_glass_0_surface, R.color.ptv_glass_0_fallback,
                R.color.ptv_glass_0_border, R.color.ptv_glass_0_highlight
            )
            PTVGlassTier.LIGHT -> listOf(
                R.color.ptv_glass_1_surface, R.color.ptv_glass_1_fallback,
                R.color.ptv_glass_1_border, R.color.ptv_glass_1_highlight
            )
            PTVGlassTier.STANDARD -> listOf(
                R.color.ptv_glass_2_surface, R.color.ptv_glass_2_fallback,
                R.color.ptv_glass_2_border, R.color.ptv_glass_2_highlight
            )
            PTVGlassTier.ELEVATED -> listOf(
                R.color.ptv_glass_3_surface, R.color.ptv_glass_3_fallback,
                R.color.ptv_glass_3_border, R.color.ptv_glass_3_highlight
            )
            PTVGlassTier.MODAL -> listOf(
                R.color.ptv_glass_4_surface, R.color.ptv_glass_4_fallback,
                R.color.ptv_glass_4_border, R.color.ptv_glass_4_highlight
            )
        }
        val useStaticFallback = profile == TvRenderingProfile.FIRE_TV_PERFORMANCE
        return PTVMaterialSpec(
            surfaceColor = context.getColor(colors[if (useStaticFallback) 1 else 0]),
            borderColor = context.getColor(colors[2]),
            highlightColor = context.getColor(colors[3]),
            blurRadiusPx = 0,
            shadowElevationPx = 0f,
            focusScale = 1f
        )
    }

    fun drawable(
        context: Context,
        tier: PTVGlassTier,
        radiusPx: Float,
        profile: TvRenderingProfile = TvRenderingRuntime.profile()
    ): GradientDrawable {
        val material = spec(context, tier, profile)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(material.surfaceColor)
            cornerRadius = radiusPx
            if (tier != PTVGlassTier.ZERO) {
                setStroke(context.dim(R.dimen.ptv_material_border_width), material.borderColor)
            }
        }
    }
}

object PTVTypography {
    fun pageTitle(view: TextView) {
        view.setTextSizeRes(R.dimen.tv_text_size_page_title)
        view.setTextColor(PTVColors.textPrimary)
        view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun sectionTitle(view: TextView) {
        view.setTextSizeRes(R.dimen.tv_text_size_section_title)
        view.setTextColor(PTVColors.textPrimary)
        view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun body(view: TextView) {
        view.setTextSizeRes(R.dimen.tv_text_size_body)
        view.setTextColor(PTVColors.textSecondary)
        view.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    fun metadata(view: TextView) {
        view.setTextSizeRes(R.dimen.tv_text_size_metadata)
        view.setTextColor(PTVColors.textSecondary)
        view.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
}

object PTVShapes {
    fun applyFocusEffect(view: View, focused: Boolean) {
        if (view.scaleX != 1f) view.scaleX = 1f
        if (view.scaleY != 1f) view.scaleY = 1f
        if (view.elevation != 0f) view.elevation = 0f
        if (view.translationZ != 0f) view.translationZ = 0f
        TvFocusIndicator.onFocusChanged(view, focused)
    }

    internal fun mediaCardSurfaceResources(
        premiumMaterial: Boolean,
        iceCardEnabled: Boolean,
        flatRectangularCards: Boolean
    ): Pair<Int, Int> = when {
        iceCardEnabled && premiumMaterial ->
            R.drawable.tv_ice_card_surface_premium to R.drawable.tv_ice_card_border_premium
        iceCardEnabled -> R.drawable.tv_ice_card_surface to R.drawable.tv_ice_card_border
        flatRectangularCards -> 0 to 0
        premiumMaterial -> R.drawable.tv_media_card_surface_premium to 0
        else -> R.drawable.tv_media_card_surface to 0
    }

    fun applyMediaCardSurface(view: View, premiumMaterial: Boolean = false) {
        val features = TvRenderingRuntime.features()
        val (surface, border) = mediaCardSurfaceResources(
            premiumMaterial,
            features.iceCardEnabled,
            features.flatRectangularCards
        )
        if (surface == 0) view.background = null else view.setBackgroundResource(surface)
        view.foreground = border.takeIf { it != 0 }
            ?.let { AppCompatResources.getDrawable(view.context, it) }
    }
}
