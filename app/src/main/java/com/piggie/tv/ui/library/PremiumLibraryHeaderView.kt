package com.piggie.tv.ui.library

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.piggie.tv.R
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.theme.PTVGlassTier
import com.piggie.tv.theme.PTVMaterials
import com.piggie.tv.theme.PTVShapes
import com.piggie.tv.theme.PTVTypography
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes

/** Open library identity and a single route into the full, scoped catalog. */
class PremiumLibraryHeaderView(
    context: Context,
    libraryName: String,
    onBrowse: (() -> Unit)? = null,
    topClearance: Int = 0
) : LinearLayout(context) {
    private val subtitle = TextView(context).apply {
        text = "Browse your $libraryName library"
        PTVTypography.body(this)
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.BOTTOM
        contentDescription = "$libraryName library"
        setPadding(
            context.dim(R.dimen.tv_screen_margin_horizontal),
            topClearance + context.dim(R.dimen.tv_spacing_large),
            context.dim(R.dimen.tv_screen_margin_horizontal),
            context.dim(R.dimen.tv_spacing_medium)
        )
        addView(TextView(context).apply {
            text = libraryName
            PTVTypography.pageTitle(this)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(subtitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dim(R.dimen.tv_spacing_small)
        })
        if (onBrowse != null) addView(Button(context).apply {
            id = R.id.ptv_library_browse_all
            text = "Browse all"
            isAllCaps = false
            setTextSizeRes(R.dimen.tv_text_size_body)
            setTextColor(PTVColors.textPrimary)
            background = PTVMaterials.drawable(context, PTVGlassTier.LIGHT,
                context.dim(R.dimen.tv_spacing_medium).toFloat())
            onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
                PTVShapes.applyFocusEffect(view, focused)
            }
            setOnClickListener { onBrowse() }
        }, LayoutParams(
            context.dim(R.dimen.tv_hero_button_width),
            context.dim(R.dimen.tv_hero_button_height)
        ).apply { topMargin = context.dim(R.dimen.tv_spacing_medium) })
    }

    fun setShowingCount(count: Int?) {
        subtitle.text = if (count == null) "Browse the library" else "Showing $count titles"
    }
}
