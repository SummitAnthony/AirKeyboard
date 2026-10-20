package com.airkeyboard.ui.connection

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.zxing.integration.android.IntentIntegrator
import com.airkeyboard.R
import com.airkeyboard.databinding.ActivityConnectionBinding
import com.airkeyboard.databinding.DialogPasswordBinding
import com.airkeyboard.databinding.ItemRecentPcBinding
import com.airkeyboard.network.ConnectionManager
import com.airkeyboard.network.LockedOutException
import com.airkeyboard.network.PairingCode
import com.airkeyboard.network.PasswordRequiredException
import com.airkeyboard.network.ChunkBuilder
import com.airkeyboard.network.AirKeyboardClient
import com.airkeyboard.network.ServerNotAuthenticException
import com.airkeyboard.network.UnsupportedServerException
import com.airkeyboard.network.WrongPasswordException
import com.airkeyboard.network.discovery.DiscoveredServer
import com.airkeyboard.network.discovery.ServerDiscovery
import com.airkeyboard.ui.common.glassToast
import com.airkeyboard.ui.trackpad.TrackpadActivity
import kotlinx.coroutines.launch

/**
 * Connection setup screen allowing QR code scan or manual IP input
 */
class ConnectionActivity : AppCompatActivity() {
    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val TAG = "ConnectionActivity"
        private const val PREFS = "connection"
        private const val KEY_LAST_HOST = "last_host"

        /** Boolean extra, default true: false skips discovery on launch (manual Disconnect) */
        const val EXTRA_AUTO_DISCOVER = "autoDiscover"

        /**
         * String extra: the connection to this host was lost, reconnect to it (every 2 s for up to
         * 30 s, saved password only) instead of running discovery. Port in [EXTRA_RECONNECT_PORT].
         */
        const val EXTRA_RECONNECT_HOST = "reconnectHost"
        const val EXTRA_RECONNECT_PORT = "reconnectPort"
    }

    private lateinit var binding: ActivityConnectionBinding
    /** Owns the connect attempts, so one in progress survives a rotation */
    private lateinit var connector: ConnectViewModel
    private var passwordDialog: AlertDialog? = null
    /** Auto-discovery is running (in this Activity's scope; connect attempts are in [connector]) */
    private var discovering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connector = ViewModelProvider(this)[ConnectViewModel::class.java]
        setupUI()
        connector.busy.observe(this) { showProgress() }
        connector.reconnecting.observe(this) { showProgress() }
        connector.event.observe(this) { event ->
            if (event == null) return@observe
            connector.consumed()
            when (event) {
                is ConnectEvent.Success -> onConnected(event.client, event.host, event.port)
                is ConnectEvent.Failure -> onConnectFailed(event.error, event.host, event.port)
                is ConnectEvent.GaveUp -> showMessage(getString(R.string.reconnect_failed, event.host))
            }
        }

        if (savedInstanceState == null) {
            val reconnectHost = intent.getStringExtra(EXTRA_RECONNECT_HOST)
            if (reconnectHost != null) {
                connector.reconnect(reconnectHost, intent.getIntExtra(EXTRA_RECONNECT_PORT, AirKeyboardClient.DEFAULT_PORT))
            } else if (intent.getBooleanExtra(EXTRA_AUTO_DISCOVER, true)) {
                discoverServers()
            }
        }
    }

    override fun onDestroy() {
        passwordDialog?.dismiss()
        passwordDialog = null
        super.onDestroy()
    }

    private fun setupUI() {
        binding.apply {
            // QR Code scanning button
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                btnScanQR.visibility = View.GONE
            }
            // Every user action takes over from a reconnect in progress
            btnScanQR.setOnClickListener {
                connector.cancel()
                if (checkCameraPermission()) {
                    startQRCodeScanner()
                } else {
                    requestCameraPermission()
                }
            }

            // Manual IP input button
            btnConnectManual.setOnClickListener {
                val host = etHost.text.toString().trim()
                val port = etPort.text.toString().trim().toIntOrNull() ?: 1978

                if (host.isEmpty()) {
                    glassToast("Please enter a host")
                    return@setOnClickListener
                }

                connectToServer(host, port)
            }

            btnAutoDiscover.setOnClickListener {
                connector.cancel()
                discoverServers()
            }
        }
        showRecentPcs()
    }

    /** One row per remembered PC: tap connects like a manual connect, long-press forgets it */
    private fun showRecentPcs() {
        val recent = ConnectionHistory.load(this)
        binding.recentSection.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        binding.recentList.removeAllViews()
        val now = System.currentTimeMillis()
        for (pc in recent) {
            val row = ItemRecentPcBinding.inflate(layoutInflater, binding.recentList, true)
            row.tvName.text = pc.name
            val used = DateUtils.getRelativeTimeSpanString(pc.lastUsed, now, DateUtils.MINUTE_IN_MILLIS)
            row.tvDetail.text = getString(R.string.recent_pc_detail, pc.host, pc.port, used)
            row.root.setOnClickListener { connectToServer(pc.host, pc.port) }
            row.root.setOnLongClickListener {
                ConnectionHistory.remove(this, pc)
                showMessage(getString(R.string.recent_pc_removed, pc.name))
                showRecentPcs()
                true
            }
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startQRCodeScanner()
            } else {
                showMessage("Camera permission denied")
            }
        }
    }

    private fun startQRCodeScanner() {
        IntentIntegrator(this).apply {
            setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            setPrompt(getString(R.string.scan_qr_prompt))
            setOrientationLocked(false)
            initiateScan()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null && !result.contents.isNullOrEmpty()) {
            handleScannedText(result.contents)
        }
    }

    /** Connect to the PC a scanned QR code names; public so instrumentation can feed it text */
    @VisibleForTesting
    fun handleScannedText(text: String) {
        val target = PairingCode.parse(text)
        if (target == null) {
            // The QR may hold anything (personal data from an unrelated code): log its length only
            Log.w(TAG, "Ignored QR code that is not a pairing code (${text.length} chars)")
            showMessage(getString(R.string.qr_not_pairing_code))
            return
        }
        Log.d(TAG, "QR pairing code for ${target.host}:${target.port}")
        connectToServer(target.host, target.port)
    }

    private fun discoverServers() {
        discovering = true
        showProgress()
        lifecycleScope.launch {
            val servers = ServerDiscovery.discover()
            discovering = false
            showProgress()
            when {
                servers.isEmpty() -> showMessage("No servers found. Is the PC server running on this Wi-Fi?")
                servers.size == 1 && isKnownHost(servers[0].host) -> connectToServer(servers[0].host, servers[0].port)
                else -> chooseServer(servers)
            }
        }
    }

    /**
     * Any device on the Wi-Fi can answer discovery, so a lone reply connects on its own only if
     * it comes from the PC used last time (or nothing was used yet); otherwise the user picks
     */
    private fun isKnownHost(host: String): Boolean {
        val last = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_HOST, null)
        return last == null || last == host
    }

    private fun chooseServer(servers: List<DiscoveredServer>) {
        AlertDialog.Builder(this)
            .setTitle("Choose a computer")
            .setItems(servers.map { "${it.name}  (${it.host})" }.toTypedArray()) { _, which ->
                connectToServer(servers[which].host, servers[which].port)
            }
            .show()
    }

    /** Connect (cancelling a reconnect in progress); the result arrives in [onConnected] / [onConnectFailed] */
    private fun connectToServer(host: String, port: Int, password: CharArray? = null, remember: Boolean = false) {
        connector.connect(host, port, password, remember)
    }

    private fun onConnected(client: AirKeyboardClient, host: String, port: Int) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST_HOST, host).apply()
        val name = ChunkBuilder.serverName(client.serverInfo)
        ConnectionHistory.record(this, RecentPc(name, host, port, System.currentTimeMillis()))
        ConnectionManager.attach(client)
        showMessage("Connected to ${client.serverInfo.substringBefore(' ')}")
        navigateToTrackpad(host, port)
    }

    private fun onConnectFailed(e: Throwable?, host: String, port: Int) {
        when (e) {
            is PasswordRequiredException -> showPasswordDialog(host, port, e.serverName, e.wrong)
            is WrongPasswordException -> showPasswordDialog(host, port, e.serverName, wrong = true)
            is LockedOutException -> showMessage(getString(R.string.password_locked_out, e.seconds))
            is ServerNotAuthenticException -> showMessage(getString(R.string.server_not_authentic))
            is UnsupportedServerException -> showMessage(getString(R.string.unsupported_server))
            else -> showMessage("Failed to connect: ${e?.message}")
        }
    }

    /**
     * Ask for the PC's password. No socket is open meanwhile: Connect starts a fresh connection
     * (fresh challenge), Cancel just stays on this screen
     */
    private fun showPasswordDialog(host: String, port: Int, serverName: String, wrong: Boolean) {
        val dialogBinding = DialogPasswordBinding.inflate(layoutInflater)
        dialogBinding.tvPasswordTitle.text = getString(R.string.password_for, serverName)
        if (wrong) dialogBinding.tilPassword.error = getString(R.string.wrong_password)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.connect, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // Set here instead of in the builder so an empty field keeps the dialog open
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = dialogBinding.etPassword.text
                if (text.isNullOrEmpty()) return@setOnClickListener
                val password = CharArray(text.length).also { text.getChars(0, text.length, it, 0) }
                text.clear()
                val remember = dialogBinding.cbRemember.isChecked
                dialog.dismiss()
                connectToServer(host, port, password, remember)
            }
        }
        dialogBinding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else false
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialogBinding.etPassword.requestFocus()

        passwordDialog?.dismiss()
        passwordDialog = dialog
        dialog.show()
    }

    private fun navigateToTrackpad(host: String, port: Int) {
        val intent = Intent(this, TrackpadActivity::class.java).apply {
            putExtra("host", host)
            putExtra("port", port)
        }
        startActivity(intent)
        finish()
    }

    /**
     * Spinner and buttons for what is running: discovery or a connect attempt disables the
     * buttons; the reconnect loop shows "Reconnecting to <host>…" and leaves them usable (any of
     * them takes over)
     */
    private fun showProgress() {
        val busy = discovering || connector.busy.value == true
        val reconnectHost = connector.reconnecting.value
        binding.progressBar.visibility = if (busy || reconnectHost != null) View.VISIBLE else View.GONE
        binding.tvReconnecting.visibility = if (reconnectHost != null) View.VISIBLE else View.GONE
        if (reconnectHost != null) binding.tvReconnecting.text = getString(R.string.reconnecting_to, reconnectHost)
        binding.btnConnectManual.isEnabled = !busy
        binding.btnScanQR.isEnabled = !busy
        binding.btnAutoDiscover.isEnabled = !busy
        for (i in 0 until binding.recentList.childCount) binding.recentList.getChildAt(i).isEnabled = !busy
    }

    private fun showMessage(message: String) {
        glassToast(message)
    }
}
