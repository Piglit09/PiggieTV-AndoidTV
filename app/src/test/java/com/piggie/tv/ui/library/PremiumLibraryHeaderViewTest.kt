package com.piggie.tv.ui.library

import android.widget.Button
import com.piggie.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PremiumLibraryHeaderViewTest {
    @Test fun browseActionIsFocusableAndInvokesTheSelectedLibrary() {
        var opens = 0
        val header = PremiumLibraryHeaderView(
            RuntimeEnvironment.getApplication(),
            "Anime",
            onBrowse = { opens++ }
        )
        val action = header.findViewById<Button>(R.id.ptv_library_browse_all)

        assertTrue(action.isFocusable)
        action.performClick()
        assertEquals(1, opens)
        assertTrue(header.contentDescription.toString().contains("Anime"))
    }
}
