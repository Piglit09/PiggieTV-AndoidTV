package com.piggie.tv.core

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.piggie.tv.R
import com.piggie.tv.auth.MainActivity
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.playback.MusicPlaybackManager
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.navigation.NativePtvShell
import com.piggie.tv.navigation.NativeRoute
import com.piggie.tv.navigation.NativeRouteNavigator
import com.piggie.tv.ui.home.HomeFragment
import com.piggie.tv.ui.movies.MoviesFragment
import com.piggie.tv.ui.music.MusicFragment
import com.piggie.tv.ui.profile.ProfileFragment
import com.piggie.tv.ui.reading.ReadingFragment
import com.piggie.tv.ui.reading.PlaceholderFragment
import com.piggie.tv.ui.search.SearchFragment
import com.piggie.tv.ui.settings.SettingsFragment
import com.piggie.tv.ui.shows.ShowsFragment

class PtvHostActivity : AppCompatActivity() {
    private val store by lazy { SecureSessionStore(this) }
    lateinit var session: NativeSession
    private lateinit var contentFrame: FrameLayout
    private var navigation = emptyMap<NativeRoute, Button>()
    private var currentRoute = NativeRoute.HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = store.read() ?: run {
            returnToLogin()
            return
        }
        
        MusicPlaybackManager.init(this)
        
        val shell = NativePtvShell.create(this, currentRoute, ::showRoute)
        contentFrame = shell.content
        navigation = shell.navigation
        
        if (savedInstanceState == null) {
            showRoute(NativeRoute.HOME)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_2,
            KeyEvent.KEYCODE_BUTTON_C,
            KeyEvent.KEYCODE_MEDIA_CLOSE -> {
                handleBack()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
    }

    private fun handleBack(): Boolean {
        val target = NativeRouteNavigator.backTarget(currentRoute) ?: return false
        showRoute(target)
        navigation[target]?.requestFocus()
        return true
    }

    fun showRoute(target: NativeRoute) {
        if (currentRoute == target && supportFragmentManager.findFragmentById(contentFrame.id) != null) {
            navigation[target]?.requestFocus()
            return
        }
        
        currentRoute = target
        navigation.forEach { (route, button) -> button.isSelected = route == target }
        
        val fragment: Fragment = when (target) {
            NativeRoute.HOME -> HomeFragment()
            NativeRoute.MOVIES -> MoviesFragment()
            NativeRoute.SHOWS -> ShowsFragment()
            NativeRoute.MUSIC -> MusicFragment()
            NativeRoute.SEARCH -> SearchFragment()
            NativeRoute.SETTINGS -> SettingsFragment()
            NativeRoute.PROFILE -> ProfileFragment()
            NativeRoute.READING -> ReadingFragment()
        }

        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(contentFrame.id, fragment)
            .commit()

        if (currentFocus == null) navigation[target]?.requestFocus()
    }

    private fun returnToLogin() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        })
        finish()
    }
}
