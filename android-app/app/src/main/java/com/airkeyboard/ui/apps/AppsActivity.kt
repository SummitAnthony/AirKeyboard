package com.airkeyboard.ui.apps

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.airkeyboard.R
import com.airkeyboard.databinding.ActivityAppsBinding
import com.airkeyboard.network.ConnectionManager
import com.airkeyboard.network.PcWindow
import com.airkeyboard.network.AirKeyboardClient
import com.airkeyboard.ui.common.RemoteKeys
import com.airkeyboard.ui.common.glassToast
import com.airkeyboard.ui.remotes.RemoteAdapter
import com.airkeyboard.ui.remotes.sendPcSystem

/**
 * App switcher: the PC's open windows (front-most first). Tap brings one to the front, the close
 * icon closes it.
 */
class AppsActivity : AppCompatActivity() {
    companion object {
        /** Time for the PC to close a window before the list is asked for again */
        private const val CLOSE_REFRESH_DELAY_MS = 500L
    }

    private lateinit var binding: ActivityAppsBinding
    private var host: String = ""
    private var port: Int = AirKeyboardClient.DEFAULT_PORT

    private val windowAdapter by lazy {
        RemoteAdapter<PcWindow>(
            title = { it.title },
            onClose = ::closeWindow,
            look = RemoteAdapter.Look.WINDOW,
            onExecute = { sendPcSystem("win focus ${it.id}") }
        )
    }

    private val refreshRunnable = Runnable { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        host = intent.getStringExtra("host") ?: ""
        port = intent.getIntExtra("port", AirKeyboardClient.DEFAULT_PORT)

        binding.apply {
            tvConnectionStatus.text = "$host:$port"

            // Pressed rows stay inside the card's rounded corners
            cardWindows.clipToOutline = true
            rvWindows.layoutManager = LinearLayoutManager(this@AppsActivity)
            rvWindows.adapter = windowAdapter

            btnAltTab.setOnClickListener { sendKey("ALT+TAB") }
            btnTaskView.setOnClickListener { sendKey("WIN+TAB") }
            btnShowDesktop.setOnClickListener { sendKey("WIN+D") }
            btnFullscreen.setOnClickListener { sendKey("F11") }

            btnRefresh.setOnClickListener { refresh() }
            btnBack.setOnClickListener { finish() }
        }
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.root.removeCallbacks(refreshRunnable)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (RemoteKeys.onKeyDown(this, keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun refresh() {
        val client = ConnectionManager.client
        when {
            client?.isConnectedStatus() != true -> showState(R.string.not_connected)
            else -> {
                showState(R.string.windows_loading)
                binding.btnRefresh.isEnabled = false
                client.requestWindows { windows ->
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        binding.btnRefresh.isEnabled = true
                        when {
                            windows == null -> showState(R.string.windows_failed)
                            windows.isEmpty() -> showState(R.string.windows_empty)
                            else -> showWindows(windows)
                        }
                    }
                }
            }
        }
    }

    /** A message in place of the list */
    private fun showState(@StringRes text: Int) {
        windowAdapter.submitList(emptyList())
        binding.tvState.setText(text)
        binding.tvState.visibility = View.VISIBLE
    }

    private fun showWindows(windows: List<PcWindow>) {
        binding.tvState.visibility = View.GONE
        windowAdapter.submitList(windows)
    }

    private fun closeWindow(window: PcWindow) {
        if (!sendPcSystem("win close ${window.id}")) return
        binding.root.removeCallbacks(refreshRunnable)
        binding.root.postDelayed(refreshRunnable, CLOSE_REFRESH_DELAY_MS)
    }

    private fun sendKey(key: String) {
        val client = ConnectionManager.client
        if (client?.isConnectedStatus() != true) {
            glassToast(R.string.not_connected)
            return
        }
        client.sendKeyPress(key)
    }
}
