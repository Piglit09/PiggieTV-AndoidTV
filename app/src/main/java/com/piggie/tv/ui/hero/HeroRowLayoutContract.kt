package com.piggie.tv.ui.hero

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView

/** Applies the one height contract shared by the discovery row wrapper and hero content. */
internal object HeroRowLayoutContract {
    fun bind(container: FrameLayout, hero: View, heightPx: Int) {
        (hero.parent as? ViewGroup)?.removeView(hero)
        container.minimumHeight = heightPx
        container.layoutParams = (container.layoutParams
            ?: RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)).apply {
            height = heightPx
        }
        container.addView(
            hero,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
        )
    }
}
