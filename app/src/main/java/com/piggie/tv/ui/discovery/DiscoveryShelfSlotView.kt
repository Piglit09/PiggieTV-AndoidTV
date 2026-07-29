package com.piggie.tv.ui.discovery

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.piggie.tv.R
import com.piggie.tv.data.discovery.ShelfDefinition
import com.piggie.tv.data.discovery.ShelfStatus
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes

/** A stable manifest slot. Failed shelves remain visible and retryable instead of disappearing. */
class DiscoveryShelfSlotView(
    context: Context,
    private val definition: ShelfDefinition
) : LinearLayout(context) {

    init {
        setTag(R.id.discovery_shelf_id, definition.id)
        orientation = VERTICAL
        showLoading()
    }

    fun showLoading(title: String = definition.title) {
        clearSlot()
        addTitle(title)
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    context.dim(R.dimen.tv_screen_margin_horizontal),
                    context.dim(R.dimen.tv_spacing_small),
                    0,
                    context.dim(R.dimen.tv_spacing_medium)
                )
                addView(
                    ProgressBar(context).apply { isIndeterminate = true },
                    LayoutParams(
                        context.dim(R.dimen.tv_nav_button_height),
                        context.dim(R.dimen.tv_nav_button_height)
                    )
                )
                addView(
                    statusLabel("Loading…"),
                    LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = context.dim(R.dimen.tv_spacing_medium)
                    }
                )
            },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dim(R.dimen.tv_nav_button_height) + context.dim(R.dimen.tv_spacing_large))
        )
    }

    fun showFailure(
        title: String,
        status: ShelfStatus,
        message: String?,
        onRetry: () -> Unit
    ) {
        clearSlot()
        addTitle(title)
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    context.dim(R.dimen.tv_screen_margin_horizontal),
                    0,
                    context.dim(R.dimen.tv_screen_margin_horizontal),
                    context.dim(R.dimen.tv_spacing_medium)
                )
                addView(
                    statusLabel("${status.name.replace('_', ' ')} · ${message.orEmpty()}"),
                    LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(
                    Button(context).apply {
                        text = "Retry"
                        isAllCaps = false
                        setTextSizeRes(R.dimen.tv_text_size_body)
                        setTextColor(PTVColors.textPrimary)
                        setBackgroundResource(R.drawable.tv_button_secondary)
                        setOnClickListener { onRetry() }
                    },
                    LayoutParams(
                        context.dim(R.dimen.tv_hero_button_width),
                        context.dim(R.dimen.tv_hero_button_height)
                    )
                )
            },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dim(R.dimen.tv_hero_button_height) + context.dim(R.dimen.tv_spacing_medium))
        )
    }

    fun showEmpty(
        title: String,
        message: String?,
        onRetry: () -> Unit
    ) {
        clearSlot()
        addTitle(title)
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    context.dim(R.dimen.tv_screen_margin_horizontal),
                    0,
                    context.dim(R.dimen.tv_screen_margin_horizontal),
                    context.dim(R.dimen.tv_spacing_medium)
                )
                addView(
                    statusLabel(message ?: "Nothing resumable is available right now."),
                    LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(
                    Button(context).apply {
                        text = "Retry"
                        isAllCaps = false
                        setTextSizeRes(R.dimen.tv_text_size_body)
                        setTextColor(PTVColors.textPrimary)
                        setBackgroundResource(R.drawable.tv_button_secondary)
                        setOnClickListener { onRetry() }
                    },
                    LayoutParams(
                        context.dim(R.dimen.tv_hero_button_width),
                        context.dim(R.dimen.tv_hero_button_height)
                    )
                )
            },
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dim(R.dimen.tv_hero_button_height) + context.dim(R.dimen.tv_spacing_medium)
            )
        )
    }

    fun showContent(view: View) {
        clearSlot()
        addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun release() {
        clearSlot()
    }

    private fun clearSlot() {
        fun releaseTree(view: View) {
            if (view is androidx.recyclerview.widget.RecyclerView) {
                view.adapter = null
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    releaseTree(view.getChildAt(index))
                }
            }
        }
        for (index in 0 until childCount) {
            releaseTree(getChildAt(index))
        }
        removeAllViews()
    }

    private fun addTitle(value: String) {
        addView(
            TextView(context).apply {
                text = value
                setTextSizeRes(R.dimen.tv_text_size_section_title)
                setTextColor(PTVColors.textPrimary)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(
                    context.dim(R.dimen.tv_screen_margin_horizontal),
                    context.dim(R.dimen.tv_spacing_medium),
                    0,
                    context.dim(R.dimen.tv_spacing_small)
                )
            },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }

    private fun statusLabel(value: String) = TextView(context).apply {
        text = value
        setTextSizeRes(R.dimen.tv_text_size_body)
        setTextColor(PTVColors.textSecondary)
        maxLines = 2
    }
}
