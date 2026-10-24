package com.piggie.tv.ui.home

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.piggie.tv.ui.hero.HeroRowLayoutContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class HomeHeroLayoutContractTest {
    @Test
    fun resizingAttachedHeaderKeepsSameHeroAndChangesAllHeightConstraints() {
        val context = RuntimeEnvironment.getApplication()
        val wrapper = FrameLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(1_920, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val hero = View(context).apply { minimumHeight = 700 }
        HeroRowLayoutContract.bind(wrapper, hero, 700)

        HeroRowLayoutContract.resize(wrapper, hero, 300)

        assertSame(hero, wrapper.getChildAt(0))
        assertEquals(1, wrapper.childCount)
        assertEquals(300, wrapper.minimumHeight)
        assertEquals(300, wrapper.layoutParams.height)
        assertEquals(300, hero.minimumHeight)
        assertEquals(300, hero.layoutParams.height)
    }
}
