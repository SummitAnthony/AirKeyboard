package com.airkeyboard.ui.presentation

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.airkeyboard.R
import com.airkeyboard.databinding.ActivityPresentationBinding
import com.airkeyboard.network.ConnectionManager
import com.airkeyboard.network.AirKeyboardClient
import com.airkeyboard.ui.common.glassToast

/**
 * Slideshow clicker (PowerPoint, Google Slides, Keynote-style shortcuts).
 * The tablet's volume buttons are the clicker here: volume down = next, volume up = previous.
 */
class PresentationActivity : AppCompatActivity() {
    companion object {
        private const val STATE_TIMER_BASE = "timer_base"
    }

    private lateinit var binding: ActivityPresentationBinding
    private var host: String = ""
    private var port: Int = AirKeyboardClient.DEFAULT_PORT
    private var timerRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPresentationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        host = intent.getStringExtra("host") ?: ""
        port = intent.getIntExtra("port", AirKeyboardClient.DEFAULT_PORT)

        binding.apply {
            tvConnectionStatus.text = "$host:$port"

            btnPrevious.setOnClickListener { send("LEFT") }
            btnNext.setOnClickListener { send("RIGHT") }
            btnStart.setOnClickListener { if (send("F5")) startTimer() }
            btnStartCurrent.setOnClickListener { if (send("SHIFT+F5")) startTimer() }
            btnEnd.setOnClickListener { if (send("ESCAPE")) stopTimer() }
            btnBlack.setOnClickListener { send("B") }
            btnWhite.setOnClickListener { send("W") }

            btnBack.setOnClickListener { finish() }
        }

        // Keep the elapsed time across rotation
        savedInstanceState?.getLong(STATE_TIMER_BASE, 0L)?.takeIf { it > 0L }?.let { startTimer(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (timerRunning) outState.putLong(STATE_TIMER_BASE, binding.chronoElapsed.base)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val key = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> "RIGHT"
            KeyEvent.KEYCODE_VOLUME_UP -> "LEFT"
            else -> return super.onKeyDown(keyCode, event)
        }
        // One slide per press: holding the button doesn't race through the deck
        if ((event?.repeatCount ?: 0) == 0) send(key)
        return true
    }

    private fun startTimer(base: Long = SystemClock.elapsedRealtime()) {
        binding.chronoElapsed.base = base
        binding.chronoElapsed.start()
        timerRunning = true
    }

    private fun stopTimer() {
        binding.chronoElapsed.stop()
        timerRunning = false
    }

    private fun send(key: String): Boolean {
        val client = ConnectionManager.client
        if (client?.isConnectedStatus() != true) {
            glassToast(R.string.not_connected)
            return false
        }
        client.sendKeyPress(key)
        return true
    }
}
