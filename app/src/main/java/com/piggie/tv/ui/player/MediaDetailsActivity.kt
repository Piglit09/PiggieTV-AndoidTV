package com.piggie.tv.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.piggie.tv.R
import com.piggie.tv.auth.AuthLogout
import com.piggie.tv.auth.AuthSessionCoordinator
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.ui.rendering.DetailsArchitecture
import com.piggie.tv.ui.rendering.TvRenderingRuntime
import com.piggie.tv.ui.player.DetailsNavigationPolicy
import com.piggie.tv.ui.player.MediaDetailsSeedStore

/**
 * Compatibility host for entry points outside [PtvHostActivity]. Both paths now render the same
 * fragment, eliminating the former activity/fragment details split.
 */
class MediaDetailsActivity : AppCompatActivity() {
    private val store by lazy { SecureSessionStore(this) }
    private var activeSession: NativeSession? = null
    val session: NativeSession
        get() = requireNotNull(activeSession) { "An authenticated session is required" }

    override fun onCreate(savedInstanceState: Bundle?) {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        activeSession = AuthSessionCoordinator.validatedSession(store)
        if (activeSession == null) {
            // Do not restore a saved details Fragment that would synchronously request a session.
            super.onCreate(null)
            startActivity(AuthLogout.loginIntent(this))
            finish()
            return
        }
        super.onCreate(savedInstanceState)
        window.setWindowAnimations(0)

        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: run {
            finish()
            return
        }
        // A stable resource ID lets FragmentManager reattach the restored canonical fragment
        // after configuration recreation.
        val container = FrameLayout(this).apply { id = R.id.ptv_details_container }
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

    override fun onDestroy() {
        activeSession = null
        super.onDestroy()
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
