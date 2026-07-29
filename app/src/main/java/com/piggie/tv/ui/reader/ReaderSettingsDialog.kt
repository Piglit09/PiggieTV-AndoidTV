package com.piggie.tv.ui.reader

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import com.piggie.tv.R
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.theme.PTVTypography
import com.piggie.tv.util.dim

class ReaderSettingsDialog(
    context: Context,
    private val isRtl: Boolean,
    private val onRtlChanged: (Boolean) -> Unit,
    private val onMarkRead: () -> Unit,
    private val onRestart: () -> Unit
) : AppCompatDialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val root = FrameLayout(context).apply {
            setBackgroundColor(0xAA000000.toInt())
        }

        val dialog = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A0A33.toInt()) // Deep Purple Glass
            setPadding(context.dim(R.dimen.tv_spacing_large), context.dim(R.dimen.tv_spacing_large), context.dim(R.dimen.tv_spacing_large), context.dim(R.dimen.tv_spacing_large))
            layoutParams = FrameLayout.LayoutParams(context.dim(R.dimen.login_card_width), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        root.addView(dialog)

        dialog.addView(TextView(context).apply {
            text = "Reader Settings"
            PTVTypography.pageTitle(this)
            setPadding(0, 0, 0, context.dim(R.dimen.tv_spacing_medium))
        })

        addOption(dialog, "Reading Direction: ${if (isRtl) "RTL (Manga)" else "LTR"}") {
            onRtlChanged(!isRtl)
            dismiss()
        }

        addOption(dialog, "Restart from Beginning") {
            onRestart()
            dismiss()
        }

        addOption(dialog, "Mark as Read") {
            onMarkRead()
            dismiss()
        }

        addOption(dialog, "Close Settings") {
            dismiss()
        }

        setContentView(root, ViewGroup.LayoutParams(-1, -1))
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun addOption(parent: LinearLayout, title: String, action: () -> Unit) {
        val btn = Button(context).apply {
            text = title
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { action() }
            setOnFocusChangeListener { v, focused ->
                if (focused) {
                    v.setBackgroundColor(PTVColors.accent) // Cyan focus
                    (v as Button).setTextColor(Color.BLACK)
                } else {
                    v.setBackgroundColor(Color.TRANSPARENT)
                    (v as Button).setTextColor(Color.WHITE)
                }
            }
        }
        parent.addView(btn, LinearLayout.LayoutParams(-1, context.dim(R.dimen.tv_nav_button_height) + 20).apply { topMargin = 8 })
    }
}
