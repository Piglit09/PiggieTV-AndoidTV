package com.piggie.tv.core

import android.content.ComponentName
import android.content.pm.PackageManager
import com.piggie.tv.ui.reader.ReaderActivity
import com.piggietv.core.PtvClientCapability
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PtvCorePlatformAdapterTest {
    @Test
    fun androidTvDoesNotAdvertiseReaderCapabilities() {
        val capabilities = PtvCorePlatformAdapter.capabilityReport().capabilities

        assertFalse(capabilities.getValue(PtvClientCapability.READER.wireName))
        assertFalse(capabilities.getValue(PtvClientCapability.COMIC_PAGING.wireName))
    }

    @Test
    fun readerActivityRemainsDisabledInAndroidTvManifest() {
        val context = RuntimeEnvironment.getApplication()
        val component = ComponentName(context, ReaderActivity::class.java)

        val activityInfo = context.packageManager.getActivityInfo(
            component,
            PackageManager.MATCH_DISABLED_COMPONENTS
        )

        assertFalse(activityInfo.enabled)
    }
}
