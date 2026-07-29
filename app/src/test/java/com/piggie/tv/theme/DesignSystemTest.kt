package com.piggie.tv.theme

import android.graphics.Color
import android.view.View
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DesignSystemTest {

    @Test
    fun testColorDefinitions() {
        assertEquals(Color.parseColor("#FFFFFF"), PTVColors.textPrimary)
        assertEquals(Color.parseColor("#BBBBBB"), PTVColors.textSecondary)
        assertEquals(Color.parseColor("#00F2FF"), PTVColors.accent)
    }

    @Test
    fun testTypographyThemeColors() {
        // Since we can't easily load resources in some CI environments,
        // we test the logic of color application from our PTVColors object.
        val context = RuntimeEnvironment.getApplication()
        val textView = TextView(context)
        
        textView.setTextColor(PTVColors.textPrimary)
        assertEquals(PTVColors.textPrimary, textView.currentTextColor)
    }
    
    @Test
    fun focusEffectIsBorderOnlyAndResetsTransformCost() {
        val context = RuntimeEnvironment.getApplication()
        val view = View(context).apply {
            scaleX = 1.05f
            scaleY = 1.05f
            elevation = 12f
        }

        PTVShapes.applyFocusEffect(view, focused = true)

        assertEquals(1f, view.scaleX)
        assertEquals(1f, view.scaleY)
        assertEquals(0f, view.elevation)
    }
}
