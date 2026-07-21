package com.piggie.tv.ui.reading

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.piggie.tv.R
import com.piggie.tv.util.dim
import com.piggie.tv.util.sp

class PlaceholderFragment : Fragment() {
    companion object {
        private const val TITLE = "title"
        private const val BODY = "body"
        fun create(title: String, body: String) = PlaceholderFragment().apply {
            arguments = Bundle().apply {
                putString(TITLE, title)
                putString(BODY, body)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, context.dim(R.dimen.tv_spacing_large))
            addView(label(arguments?.getString(TITLE) ?: "Untitled", context.sp(R.dimen.tv_text_size_page_title), R.color.tv_text_primary, true))
            addView(label(arguments?.getString(BODY) ?: "", context.sp(R.dimen.tv_text_size_body), R.color.tv_text_secondary, margin = 16))
        }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false, margin: Int = 0): TextView =
        TextView(requireContext()).apply {
            text = value
            textSize = size
            setTextColor(requireContext().getColor(color))
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            if (margin > 0) layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = margin }
        }
}
