package com.piggie.tv.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.piggie.tv.R
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.api.SessionOrigin
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.ui.rendering.TvRenderingRuntime
import com.piggie.tv.data.session.NativeSettings
import com.piggie.tv.diagnostics.DiagnosticsExperiment
import com.piggie.tv.diagnostics.PtvDiagnosticsManager
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val api by lazy { JellyfinNativeApi(this) }
    private val store by lazy { SecureSessionStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.piggie.tv.util.CrashReporter.init(this)
        TvRenderingRuntime.configureDebugExperiment(
            intent.getStringExtra(TvRenderingRuntime.DEBUG_EXPERIMENT_EXTRA)
        )
        if (com.piggie.tv.BuildConfig.DEBUG) {
            when (DiagnosticsExperiment.fromWireName(intent.getStringExtra(DiagnosticsExperiment.DEBUG_EXTRA))) {
                DiagnosticsExperiment.AUTO -> Unit
                DiagnosticsExperiment.DISABLED -> {
                    PtvDiagnosticsManager.setEnabled(this, false)
                    NativeSettings(this).diagnosticsOverlayEnabled = false
                }
                DiagnosticsExperiment.ENABLED_NO_OVERLAY -> {
                    PtvDiagnosticsManager.setEnabled(this, true)
                    NativeSettings(this).diagnosticsOverlayEnabled = false
                }
                DiagnosticsExperiment.FULL -> {
                    PtvDiagnosticsManager.setEnabled(this, true)
                    NativeSettings(this).diagnosticsOverlayEnabled = true
                }
            }
        }

        val saved = store.read()
        if (saved != null && saved.isComplete()) {
            startActivity(Intent(this, PtvHostActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        
        val serverInput = findViewById<EditText>(R.id.server_input)
        val userAction = findViewById<Button>(R.id.shell_launch_button)
        val quickConnectAction = findViewById<Button>(R.id.quick_connect_button)

        val lastServer = getSharedPreferences("ptv_auth", MODE_PRIVATE).getString("last_server", "")
        if (!lastServer.isNullOrBlank()) {
            serverInput.setText(lastServer)
        }

        userAction.setOnClickListener {
            val server = serverInput.text.toString().trim()
            val user = findViewById<EditText>(R.id.username_input).text.toString().trim()
            val pass = findViewById<EditText>(R.id.password_input).text.toString()
            
            if (server.isBlank() || user.isBlank()) {
                Toast.makeText(this, "Please enter server and username", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            getSharedPreferences("ptv_auth", MODE_PRIVATE).edit().putString("last_server", server).apply()

            thread {
                runCatching {
                    val normalized = com.piggie.tv.util.JellyfinServerUrl.normalize(server)
                    val session = api.authenticateWithPassword(normalized, user, pass)
                    store.save(session, SessionOrigin.PASSWORD)
                    runOnUiThread {
                        startActivity(Intent(this@MainActivity, PtvHostActivity::class.java))
                        finish()
                    }
                }.onFailure { error ->
                    runOnUiThread { Toast.makeText(this@MainActivity, "Login failed: ${error.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }

        quickConnectAction.setOnClickListener {
            val server = serverInput.text.toString().trim()
            if (server.isBlank()) {
                Toast.makeText(this, "Enter server URL first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            getSharedPreferences("ptv_auth", MODE_PRIVATE).edit().putString("last_server", server).apply()
            val normalized = com.piggie.tv.util.JellyfinServerUrl.normalize(server)
            startActivity(Intent(this, QuickConnectActivity::class.java).apply {
                putExtra("server", normalized)
            })
        }
    }
}
