package com.piggie.tv.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import com.piggie.tv.R
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.theme.PTVShapes
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes

object PTVButtonFactory {
    fun createPrimary(context: Context, text: String, onClick: () -> Unit): Button =
        Button(context).apply {
            this.text = text
            setTextSizeRes(R.dimen.tv_nav_text_size)
            isAllCaps = false
            setTextColor(PTVColors.textPrimary)
            setBackgroundResource(R.drawable.tv_button_primary)
            setPadding(context.dim(R.dimen.tv_spacing_large), 0, context.dim(R.dimen.tv_spacing_large), 0)
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, focused -> PTVShapes.applyFocusEffect(v, focused) }
        }

    fun createSecondary(context: Context, text: String, onClick: () -> Unit): Button =
        Button(context).apply {
            this.text = text
            setTextSizeRes(R.dimen.tv_nav_text_size)
            isAllCaps = false
            setTextColor(PTVColors.textPrimary)
            setBackgroundResource(R.drawable.tv_button_secondary)
            setPadding(context.dim(R.dimen.tv_spacing_large), 0, context.dim(R.dimen.tv_spacing_large), 0)
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, focused -> PTVShapes.applyFocusEffect(v, focused) }
        }
}
