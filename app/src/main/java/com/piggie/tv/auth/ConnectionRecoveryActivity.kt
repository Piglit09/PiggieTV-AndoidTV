package com.piggie.tv.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.piggie.tv.R
import com.piggie.tv.data.session.SecureSessionStore

class ConnectionRecoveryActivity : AppCompatActivity() {
    private val store by lazy { SecureSessionStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val category = intent.getStringExtra(EXTRA_CATEGORY)
            ?.let { value -> AuthFailureCategory.entries.firstOrNull { it.name == value } }
            ?: AuthFailureCategory.NETWORK_UNAVAILABLE
        val copy = recoveryCopy(category)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.tv_app_background)
            setPadding(80, 80, 80, 80)
        }

        root.addView(TextView(this).apply {
            text = copy.first
            textSize = 32f
            setTextColor(getColor(R.color.tv_text_primary))
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = copy.second
            textSize = 18f
            setTextColor(getColor(R.color.tv_text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 80)
        })

        val retry = Button(this).apply {
            text = "Retry"
            setOnClickListener {
                startActivity(Intent(this@ConnectionRecoveryActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                })
                finish()
            }
        }
        root.addView(retry, LinearLayout.LayoutParams(400, 80))

        val signOut = Button(this).apply {
            text = "Sign Out"
            setOnClickListener {
                val cleared = AuthLogout.clearLocalAuthentication(store)
                AuthLogout.returnToLogin(this@ConnectionRecoveryActivity, storageClearFailed = !cleared)
            }
        }
        root.addView(signOut, LinearLayout.LayoutParams(400, 80).apply { topMargin = 20 })

        setContentView(root)
    }

    private fun recoveryCopy(category: AuthFailureCategory): Pair<String, String> = when (category) {
        AuthFailureCategory.SECURE_STORAGE_FAILURE ->
            "Sign-In Storage Unavailable" to
                "PiggieTV could not safely read or update the saved sign-in. Your credentials were not erased. Retry or sign out."

        AuthFailureCategory.SERVER_UNAVAILABLE ->
            "Server Temporarily Unavailable" to
                "Your sign-in is still saved. Retry when the Jellyfin server is available."

        else ->
            "Connection Lost" to
                "Your sign-in is still saved. Check the network and Jellyfin server, then retry."
    }

    companion object {
        private const val EXTRA_CATEGORY = "ptv_auth_failure_category"

        fun intent(context: Context, category: AuthFailureCategory): Intent =
            Intent(context, ConnectionRecoveryActivity::class.java).apply {
                putExtra(EXTRA_CATEGORY, category.name)
            }
    }
}
