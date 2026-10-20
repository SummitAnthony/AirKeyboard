package com.airkeyboard.ui.splash

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.airkeyboard.R
import com.airkeyboard.ui.connection.ConnectionActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Splash screen that shows briefly on app launch
 */
class SplashActivity : AppCompatActivity() {
    companion object {
        private const val SPLASH_DURATION = 2000L // 2 seconds
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Tied to this instance: a rotation cancels it, so only the recreated splash moves on
        lifecycleScope.launch {
            delay(SPLASH_DURATION)
            val intent = Intent(this@SplashActivity, ConnectionActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}
