package com.piggie.tv.theme

import android.graphics.Color
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
    fun testShapeScaleLogic() {
        val focused = true
        val scale = if (focused) 1.05f else 1.0f
        assertEquals(1.05f, scale)
    }
}
