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
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val api by lazy { JellyfinNativeApi(this) }
    private val store by lazy { SecureSessionStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
        val serverDisplay = findViewById<TextView>(R.id.native_login_server)

        userAction.setOnClickListener {
            val server = serverInput.text.toString()
            val user = findViewById<EditText>(R.id.username_input).text.toString()
            val pass = findViewById<EditText>(R.id.password_input).text.toString()
            
            if (server.isBlank() || user.isBlank()) {
                Toast.makeText(this, "Please enter server and username", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            thread {
                runCatching {
                    val session = api.authenticateWithPassword(server, user, pass)
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
            val server = serverInput.text.toString()
            if (server.isBlank()) {
                Toast.makeText(this, "Enter server URL first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, QuickConnectActivity::class.java).apply {
                putExtra("server", server)
            })
        }
    }
}
