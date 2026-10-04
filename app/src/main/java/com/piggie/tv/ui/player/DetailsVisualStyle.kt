package com.piggie.tv.ui.player

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.piggie.tv.R
import com.piggie.tv.theme.PTVGlassTier
import com.piggie.tv.theme.PTVMaterials
import com.piggie.tv.util.dim
import com.piggie.tv.util.dimFloat
import com.piggie.tv.util.setTextSizeRes

/** Static Movie/Series presentation shared by both the in-host and standalone Details routes. */
internal object DetailsVisualStyle {
    fun applyIdentity(itemType: String?, title: TextView) {
        title.setTextSizeRes(
            if (itemType.isMovieOrSeries()) R.dimen.ptv_details_title_size
            else R.dimen.tv_text_size_hero_title
        )
    }

    fun applyScene(itemType: String?, scrim: View, poster: ImageView, overview: TextView) {
        val cinematic = itemType.isMovieOrSeries()
        scrim.setBackgroundResource(
            if (cinematic) R.drawable.tv_details_cinematic_scrim else R.drawable.hero_gradient_overlay
        )
        poster.foreground = if (cinematic) posterEdge(poster.context) else null
        overview.setTextSizeRes(
            if (cinematic) R.dimen.tv_text_size_hero_body else R.dimen.tv_text_size_body
        )
        overview.maxWidth = if (cinematic) {
            overview.context.dim(R.dimen.tv_details_overview_max_width)
        } else {
            Int.MAX_VALUE
        }
    }

    fun applyActions(itemType: String, row: LinearLayout, primaryAction: View?) {
        if (!itemType.isMovieOrSeries()) return
        for (index in 0 until row.childCount) {
            val action = row.getChildAt(index)
            action.background = actionSurface(action.context, action === primaryAction)
            action.stateListAnimator = null
            action.elevation = 0f
        }
    }

    private fun String?.isMovieOrSeries(): Boolean =
        this?.equals("Movie", ignoreCase = true) == true ||
            this?.equals("Series", ignoreCase = true) == true

    private fun posterEdge(context: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.TRANSPARENT)
        cornerRadius = context.dimFloat(R.dimen.ptv_radius_card)
        setStroke(
            context.dim(R.dimen.ptv_material_border_width),
            PTVMaterials.spec(context, PTVGlassTier.STANDARD).borderColor
        )
    }

    private fun actionSurface(context: Context, primary: Boolean): StateListDrawable {
        val radius = context.dimFloat(R.dimen.ptv_radius_control)
        val normal = PTVMaterials.drawable(
            context,
            if (primary) PTVGlassTier.STANDARD else PTVGlassTier.LIGHT,
            radius
        )
        val focused = PTVMaterials.drawable(context, PTVGlassTier.ELEVATED, radius).apply {
            setStroke(
                context.dim(R.dimen.ptv_focus_inner_border_width),
                context.getColor(R.color.ptv_focus_pale)
            )
        }
        val disabled = PTVMaterials.drawable(context, PTVGlassTier.LIGHT, radius).apply {
            alpha = 150
        }
        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), disabled)
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }
}
