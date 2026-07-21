package com.piggie.tv.ui.shared

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.ImageView
import coil.load
import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.theme.PTVColors

object BackdropManager {
    fun update(
        view: ImageView, 
        overlay: View?,
        session: NativeSession, 
        item: MediaItem?, 
        api: JellyfinNativeApi
    ) {
        if (item == null) {
            view.setImageDrawable(ColorDrawable(PTVColors.background))
            overlay?.visibility = View.GONE
            return
        }

        val url = api.backdropUrl(session, item, 1920)
        view.load(url) {
            crossfade(400)
            placeholder(view.drawable) // Smooth transition from previous
            error(ColorDrawable(PTVColors.background))
        }
        overlay?.visibility = View.VISIBLE
    }
}
