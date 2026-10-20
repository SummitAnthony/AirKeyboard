package com.airkeyboard.ui.remotes

import android.app.Activity
import android.content.Intent
import android.graphics.Outline
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.airkeyboard.R
import com.airkeyboard.databinding.ActivityRemotesBinding
import com.airkeyboard.databinding.DialogRemotesPowerBinding
import com.airkeyboard.databinding.ItemRemotesPowerBinding
import com.airkeyboard.network.ConnectionManager
import com.airkeyboard.network.AirKeyboardClient
import com.airkeyboard.ui.airmouse.AirMouseActivity
import com.airkeyboard.ui.apps.AppsActivity
import com.airkeyboard.ui.bookmarks.BookmarksActivity
import com.airkeyboard.ui.common.RemoteKeys
import com.airkeyboard.ui.common.glassToast
import com.airkeyboard.ui.presentation.PresentationActivity
import kotlinx.coroutines.launch

/**
 * Remotes hub: tiles for the power panel, presentation, app switcher, bookmarks and air mouse,
 * then the quick-command list
 */
class RemotesActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "RemotesActivity"

        /** Quick-command rows are at least this wide; as many columns as fit */
        private const val MIN_ROW_WIDTH_DP = 260

        /** Power panel sheet width (Glass spec) */
        private const val POWER_SHEET_WIDTH_DP = 340
    }

    /** Power panel entries; [command] goes to [AirKeyboardClient.sendSystem] */
    private enum class Power(@StringRes val label: Int, @StringRes val confirm: Int, val command: String) {
        SHUTDOWN(R.string.power_shutdown, R.string.confirm_shutdown, "pwr shutdown"),
        RESTART(R.string.power_restart, R.string.confirm_restart, "pwr restart"),
        SLEEP(R.string.power_sleep, R.string.confirm_sleep, "pwr sleep"),
        LOGOFF(R.string.power_logoff, R.string.confirm_logoff, "pwr logoff"),
        LOCK(R.string.power_lock, R.string.confirm_lock, "pwr lock"),
    }

    private lateinit var binding: ActivityRemotesBinding
    private var host: String = ""
    private var port: Int = AirKeyboardClient.DEFAULT_PORT

    private val remoteAdapter by lazy { RemoteAdapter<RemoteCommand>(title = { it.name }, onExecute = ::executeCommand) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemotesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        host = intent.getStringExtra("host") ?: "127.0.0.1"
        port = intent.getIntExtra("port", AirKeyboardClient.DEFAULT_PORT)

        setupUI()
        loadRemotes()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (RemoteKeys.onKeyDown(this, keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun setupUI() {
        binding.apply {
            tvConnectionStatus.text = "$host:$port"

            btnPower.setOnClickListener { showPowerPanel() }
            btnPresentation.setOnClickListener { navigateTo(PresentationActivity::class.java) }
            btnApps.setOnClickListener { navigateTo(AppsActivity::class.java) }
            btnBookmarks.setOnClickListener { navigateTo(BookmarksActivity::class.java) }
            btnAirMouse.setOnClickListener { navigateTo(AirMouseActivity::class.java) }

            rvRemotes.apply {
                // Screen width less the 20dp side padding of the screen
                val widthDp = resources.configuration.screenWidthDp - 40
                layoutManager = GridLayoutManager(this@RemotesActivity, maxOf(1, widthDp / MIN_ROW_WIDTH_DP))
                adapter = remoteAdapter
            }

            btnBack.setOnClickListener { finish() }
        }
    }

    private fun navigateTo(activity: Class<out Activity>) {
        startActivity(Intent(this, activity).apply {
            putExtra("host", host)
            putExtra("port", port)
        })
    }

    private fun showPowerPanel() {
        val panel = DialogRemotesPowerBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this).setView(panel.root).create()
        Power.values().forEach { power ->
            ItemRemotesPowerBinding.inflate(layoutInflater, panel.powerOptions, true).tvPowerOption.apply {
                setText(power.label)
                if (power == Power.SHUTDOWN) setTextColor(ContextCompat.getColor(context, R.color.destructive))
                setOnClickListener {
                    dialog.dismiss()
                    confirmPower(power)
                }
            }
        }
        panel.btnPowerCancel.setOnClickListener { dialog.dismiss() }

        // Pressed rows stay inside the sheet's rounded corners
        val radius = resources.getDimension(R.dimen.radius_dialog)
        panel.root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        panel.root.clipToOutline = true

        dialog.show()
        // The sheet background has a 16dp inset on each side; no wider than the screen (small phones)
        val width = minOf(((POWER_SHEET_WIDTH_DP + 32) * resources.displayMetrics.density).toInt(), resources.displayMetrics.widthPixels)
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun confirmPower(power: Power) {
        AlertDialog.Builder(this)
            .setMessage(power.confirm)
            .setPositiveButton(power.label) { _, _ ->
                val c = ConnectionManager.client
                if (c?.isConnectedStatus() != true) toast(R.string.not_connected) else c.sendSystem(power.command)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun loadRemotes() {
        // Sample remotes - in a real app, these would be stored in a database
        val remotes = listOf(
            RemoteCommand("Play/Pause", "MEDIA_PLAY_PAUSE"),
            RemoteCommand("Next Track", "MEDIA_NEXT"),
            RemoteCommand("Previous Track", "MEDIA_PREVIOUS"),
            RemoteCommand("Volume Up", "VOLUME_UP"),
            RemoteCommand("Volume Down", "VOLUME_DOWN"),
            RemoteCommand("Mute", "VOLUME_MUTE"),
            RemoteCommand("Browser Back", "BROWSER_BACK"),
            RemoteCommand("Browser Forward", "BROWSER_FORWARD"),
            RemoteCommand("Refresh", "F5"),
            RemoteCommand("Fullscreen (F11)", "F11"),
            RemoteCommand("Windows Key", "WIN"),
            RemoteCommand("Show Desktop", "WIN+D"),
            RemoteCommand("Alt+Tab", "ALT+TAB"),
            RemoteCommand("Close Window", "ALT+F4"),
            RemoteCommand("Copy", "CTRL+C"),
            RemoteCommand("Paste", "CTRL+V"),
            RemoteCommand("Undo", "CTRL+Z"),
        )
        remoteAdapter.submitList(remotes)
    }

    private fun executeCommand(command: RemoteCommand) {
        lifecycleScope.launch {
            try {
                val c = ConnectionManager.client
                if (c?.isConnectedStatus() != true) {
                    toast(R.string.not_connected)
                    return@launch
                }
                c.sendKeyPress(command.command)
                glassToast("Sent: ${command.name}")
            } catch (e: Exception) {
                glassToast("Error sending command: ${e.message}")
            }
        }
    }
}

data class RemoteCommand(
    val name: String,
    val command: String
)

private fun Activity.toast(@StringRes text: Int) = glassToast(text)

/**
 * Sends a PC system command ([AirKeyboardClient.sendSystem]) for the bookmarks and app switcher.
 * When disconnected this says so and returns false. Also false when the command was not queued
 * (too long for one frame, or the connection just dropped).
 */
fun Activity.sendPcSystem(command: String): Boolean {
    val client = ConnectionManager.client
    if (client?.isConnectedStatus() != true) {
        toast(R.string.not_connected)
        return false
    }
    return client.sendSystem(command)
}
