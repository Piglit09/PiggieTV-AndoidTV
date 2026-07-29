package com.piggie.tv.ui.reader

import android.content.Context
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.piggie.tv.R
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.theme.PTVTypography
import com.piggie.tv.util.dim

object ReaderErrorState {
    fun create(context: Context, message: String, onRetry: () -> Unit, onBack: () -> Unit): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(PTVColors.background)

            addView(TextView(context).apply {
                text = "Reading Error"
                PTVTypography.pageTitle(this)
            })

            addView(TextView(context).apply {
                text = message
                PTVTypography.body(this)
                gravity = Gravity.CENTER
                setPadding(context.dim(R.dimen.tv_screen_margin_horizontal), 20, context.dim(R.dimen.tv_screen_margin_horizontal), 0)
            })

            val retry = Button(context).apply {
                text = "Retry"
                setOnClickListener { onRetry() }
            }
            addView(retry, LinearLayout.LayoutParams(context.dim(R.dimen.tv_hero_button_width), -2).apply { topMargin = 40 })

            val back = Button(context).apply {
                text = "Go Back"
                setOnClickListener { onBack() }
            }
            addView(back, LinearLayout.LayoutParams(context.dim(R.dimen.tv_hero_button_width), -2).apply { topMargin = 20 })

            retry.post { retry.requestFocus() }
        }
    }
}
