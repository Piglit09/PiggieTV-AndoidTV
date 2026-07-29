package com.piggie.tv.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.ui.rendering.DetailsArchitecture
import com.piggie.tv.ui.rendering.TvRenderingRuntime
import com.piggie.tv.ui.player.DetailsNavigationPolicy
import com.piggie.tv.ui.player.MediaDetailsSeedStore

/**
 * Compatibility host for entry points outside [PtvHostActivity]. Both paths now render the same
 * fragment, eliminating the former activity/fragment details split.
 */
class MediaDetailsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        super.onCreate(savedInstanceState)
        window.setWindowAnimations(0)

        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: run {
            finish()
            return
        }
        val container = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(container)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(container.id, MediaDetailsFragment.newInstance(itemId), DETAILS_TAG)
                .commit()
        }
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        val details = supportFragmentManager.findFragmentByTag(DETAILS_TAG) as? MediaDetailsFragment
        if (details?.handleBackWithinDetails() == true) return
        super.onBackPressed()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_ITEM_ID = "extra_item_id"
        private const val DETAILS_TAG = "ptv-details-canonical"

        fun start(context: Context, item: MediaItem) {
            MediaDetailsSeedStore.put(item)
            val host = context as? PtvHostActivity
            if (
                DetailsNavigationPolicy.architecture(
                    TvRenderingRuntime.features(),
                    hostAvailable = host != null
                ) == DetailsArchitecture.IN_HOST_FRAGMENT
            ) {
                host!!.showDetails(item)
                return
            }
            start(context, item.id)
        }

        fun start(context: Context, itemId: String) {
            context.startActivity(Intent(context, MediaDetailsActivity::class.java).apply {
                putExtra(EXTRA_ITEM_ID, itemId)
            })
        }
    }
}
