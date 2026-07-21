package com.piggie.tv.auth

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.piggie.tv.R
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.api.SessionOrigin
import com.piggie.tv.data.session.SecureSessionStore
import kotlin.concurrent.thread

class QuickConnectActivity : AppCompatActivity() {
    private val api by lazy { JellyfinNativeApi(this) }
    private val store by lazy { SecureSessionStore(this) }
    private var polling = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_connect)
        
        val server = intent.getStringExtra("server") ?: run { finish(); return }
        val codeView = findViewById<TextView>(R.id.quick_connect_code)
        
        thread {
            runCatching {
                val ticket = api.initiateQuickConnect(server)
                runOnUiThread { codeView.text = ticket.code }
                
                while (polling) {
                    Thread.sleep(5000)
                    runCatching {
                        val session = api.authenticateWithQuickConnect(server, ticket.secret)
                        store.save(session, SessionOrigin.QUICK_CONNECT)
                        runOnUiThread {
                            startActivity(Intent(this@QuickConnectActivity, PtvHostActivity::class.java))
                            finishAffinity()
                        }
                        polling = false
                    }
                }
            }.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(this@QuickConnectActivity, "Quick Connect failed: ${error.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        polling = false
    }
}
