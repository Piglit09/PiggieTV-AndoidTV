package com.piggie.tv.ui.widgets

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import com.piggie.tv.R
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.theme.PTVTypography
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes
import java.lang.ref.WeakReference

class PtvSelectionDialog(
    context: Context,
    private val titleText: String,
    private val options: List<String>,
    private val selectedIndex: Int = -1,
    private val defaultIndex: Int = -1,
    restoreFocusTo: View? = null,
    private val onSelected: (Int) -> Unit
) : AppCompatDialog(context) {
    private val focusTarget = WeakReference(
        restoreFocusTo ?: (context as? Activity)?.currentFocus
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val root = FrameLayout(context).apply {
            setBackgroundColor(0xDD000000.toInt()) // Darker overlay
            isFocusable = false
        }

        val dialog = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Use a dark glass look with purple accent border if drawable exists, or just color
            setBackgroundColor(0xFF1A0A33.toInt()) // PTV Deep Purple Glass
            setPadding(context.dim(R.dimen.tv_spacing_large), context.dim(R.dimen.tv_spacing_large), context.dim(R.dimen.tv_spacing_large), context.dim(R.dimen.tv_spacing_large))
            layoutParams = FrameLayout.LayoutParams(context.dim(R.dimen.tv_dialog_width), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            isFocusable = false
        }
        root.addView(dialog)

        dialog.addView(TextView(context).apply {
            text = titleText
            PTVTypography.pageTitle(this)
            setPadding(0, 0, 0, context.dim(R.dimen.tv_spacing_medium))
        })

        val scroll = ScrollView(context).apply {
            isSmoothScrollingEnabled = false
            layoutParams = LinearLayout.LayoutParams(-1, context.dim(R.dimen.tv_hero_height) / 2)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = true
        }
        val optionsList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(optionsList)
        dialog.addView(scroll)

        if (options.isEmpty()) {
            optionsList.addView(TextView(context).apply {
                text = "No options are available."
                setTextColor(PTVColors.textSecondary)
                setTextSizeRes(R.dimen.tv_text_size_body)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, context.dim(R.dimen.tv_dialog_row_height)))
        }

        options.forEachIndexed { index, option ->
            val btn = Button(context).apply {
                text = indicator(index) + option + if (index == defaultIndex && index != selectedIndex) "  (Default)" else ""
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(PTVColors.textPrimary)
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(context.dim(R.dimen.tv_spacing_medium), 0, context.dim(R.dimen.tv_spacing_medium), 0)
                setOnClickListener {
                    onSelected(index)
                    dismiss()
                }
                setOnFocusChangeListener { v, focused ->
                    if (focused) {
                        v.setBackgroundColor(PTVColors.accent) // Cyan focus
                        (v as Button).setTextColor(Color.BLACK)
                    } else {
                        v.setBackgroundColor(if (index == selectedIndex) 0x5539D5E6 else Color.TRANSPARENT)
                        (v as Button).setTextColor(PTVColors.textPrimary)
                    }
                }
            }
            optionsList.addView(btn, LinearLayout.LayoutParams(-1, context.dim(R.dimen.tv_dialog_row_height)).apply { topMargin = context.dim(R.dimen.tv_spacing_small) })
            if (index == selectedIndex.coerceAtLeast(0)) btn.post { btn.requestFocus() }
        }

        setContentView(root, ViewGroup.LayoutParams(-1, -1))
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun dismiss() {
        super.dismiss()
        focusTarget.get()?.let { target ->
            target.post {
                if (target.isAttachedToWindow && target.visibility == View.VISIBLE && target.isEnabled) {
                    target.requestFocus()
                }
            }
        }
    }

    private fun indicator(index: Int): String = when (index) {
        selectedIndex -> "✓  "
        else -> "○  "
    }
}
