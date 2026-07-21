package com.piggie.tv.ui.profile

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import coil.load
import com.piggie.tv.R
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.navigation.NativeRoute
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes
import com.piggie.tv.util.sp

class ProfileFragment : Fragment() {
    private val store by lazy { SecureSessionStore(requireContext()) }
    private lateinit var session: NativeSession

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        session = (activity as? PtvHostActivity)?.session ?: store.read()!!
        
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, context.dim(R.dimen.tv_spacing_large))
        }

        root.addView(label("Profile", sp(R.dimen.tv_text_size_page_title), R.color.tv_text_primary, true))
        
        val userBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, context.dim(R.dimen.tv_spacing_large), 0, 0)
        }
        val avatar = ImageView(context).apply {
            setBackgroundResource(R.drawable.tv_nav_button)
            setPadding(context.dim(R.dimen.tv_card_padding), context.dim(R.dimen.tv_card_padding), context.dim(R.dimen.tv_card_padding), context.dim(R.dimen.tv_card_padding))
            val url = session.serverUrl + "/Users/" + session.userId + "/Images/Primary"
            load(url) {
                placeholder(android.R.drawable.ic_menu_gallery)
                error(android.R.drawable.ic_menu_gallery)
            }
        }
        userBox.addView(avatar, LinearLayout.LayoutParams(context.dim(R.dimen.tv_square_width) / 2, context.dim(R.dimen.tv_square_height) / 2))
        
        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dim(R.dimen.tv_spacing_medium), 0, 0, 0)
        }
        info.addView(label(session.userName, sp(R.dimen.tv_text_size_section_title), R.color.tv_text_primary, true))
        info.addView(label(session.serverUrl, sp(R.dimen.tv_text_size_metadata), R.color.tv_text_secondary, margin = 6))
        userBox.addView(info)
        root.addView(userBox)

        root.addView(Button(context).apply {
            text = "Settings"
            isAllCaps = false
            setBackgroundResource(R.drawable.tv_button_secondary)
            setTextColor(context.getColor(R.color.tv_text_primary))
            setOnClickListener { (activity as? PtvHostActivity)?.showRoute(NativeRoute.SETTINGS) }
        }, LinearLayout.LayoutParams(context.dim(R.dimen.tv_hero_button_width), context.dim(R.dimen.tv_nav_button_height) + context.dim(R.dimen.tv_spacing_small)).apply { topMargin = context.dim(R.dimen.tv_spacing_large) })

        root.addView(Button(context).apply {
            text = "Sign Out"
            isAllCaps = false
            setBackgroundResource(R.drawable.tv_button_secondary)
            setTextColor(context.getColor(R.color.tv_text_primary))
            setOnClickListener { 
                store.clear()
                activity?.finish()
            }
        }, LinearLayout.LayoutParams(context.dim(R.dimen.tv_hero_button_width), context.dim(R.dimen.tv_nav_button_height) + context.dim(R.dimen.tv_spacing_small)).apply { topMargin = context.dim(R.dimen.tv_spacing_small) })

        return root
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false, margin: Int = 0): TextView =
        TextView(requireContext()).apply {
            text = value
            textSize = size
            setTextColor(requireContext().getColor(color))
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            if (margin > 0) layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = margin }
        }

    private fun sp(id: Int): Float = requireContext().resources.getDimension(id) / requireContext().resources.displayMetrics.scaledDensity
}
