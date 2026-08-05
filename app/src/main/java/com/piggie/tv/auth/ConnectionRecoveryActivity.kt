package com.piggie.tv.auth

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.piggie.tv.R
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.data.discovery.DiscoveryManager
import com.piggie.tv.data.playback.MusicPlaybackManager
import com.piggie.tv.ui.player.MediaDetailsSeedStore

class ConnectionRecoveryActivity : AppCompatActivity() {
    private val store by lazy { SecureSessionStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.tv_app_background)
            setPadding(80, 80, 80, 80)
        }

        root.addView(TextView(this).apply {
            text = "Connection Lost"
            textSize = 32f
            setTextColor(getColor(R.color.tv_text_primary))
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "We couldn't connect to your Jellyfin server. Please check your network or server status."
            textSize = 18f
            setTextColor(getColor(R.color.tv_text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 80)
        })

        val retry = Button(this).apply {
            text = "Retry"
            setOnClickListener {
                // Return to MainActivity which will try to restore session
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
                MusicPlaybackManager.shutdown()
                store.clear()
                DiscoveryManager.clearForLogout()
                MediaDetailsSeedStore.clear()
                startActivity(Intent(this@ConnectionRecoveryActivity, MainActivity::class.java))
                finishAffinity()
            }
        }
        root.addView(signOut, LinearLayout.LayoutParams(400, 80).apply { topMargin = 20 })

        setContentView(root)
    }
}
