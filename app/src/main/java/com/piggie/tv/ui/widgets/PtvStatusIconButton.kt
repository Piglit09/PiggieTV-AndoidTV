package com.piggie.tv.ui.widgets

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.appcompat.widget.AppCompatImageButton
import com.piggie.tv.R
import com.piggie.tv.util.dim

/**
 * Fixed, optically stable status action. The icon is centered by image bounds rather than by a
 * Button's compound-text layout, and selected state remains independent from focus state.
 */
class PtvStatusIconButton(
    context: Context,
    iconRes: Int,
    label: String,
    selected: Boolean,
    action: () -> Unit
) : AppCompatImageButton(context) {
    init {
        contentDescription = label
        setImageResource(iconRes)
        scaleType = ScaleType.CENTER_INSIDE
        cropToPadding = false
        background = context.getDrawable(R.drawable.tv_status_icon_button)
        imageTintList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_selected),
                intArrayOf()
            ),
            intArrayOf(context.getColor(R.color.tv_focus_ring), Color.WHITE)
        )
        isSelected = selected
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        minimumWidth = 0
        minimumHeight = 0
        val inset = context.dim(R.dimen.tv_spacing_medium)
        setPadding(inset, inset, inset, inset)
        setOnClickListener { action() }
    }
}
