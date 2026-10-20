package com.airkeyboard.ui.keyboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.airkeyboard.R
import com.airkeyboard.databinding.ActivityKeyboardBinding
import com.airkeyboard.network.ConnectionManager
import com.airkeyboard.network.AirKeyboardClient
import com.airkeyboard.ui.common.RemoteKeys
import com.airkeyboard.ui.common.VoiceButton
import com.airkeyboard.ui.common.glassToast
import com.google.android.material.button.MaterialButton

/**
 * Keyboard input screen for typing text to the remote computer.
 * Live typing (default): every edit of the field reaches the PC right away, over the TCP session
 * when the server is ours. Send mode: the whole field goes out on Send.
 * Ctrl / Alt / Shift / Win are sticky: tap one, then a key (or a single typed character) to send a combo.
 * Below the shortcut bar, a panel of F-keys, numpad or navigation keys (see [KeyPanels]).
 */
class KeyboardActivity : AppCompatActivity() {
    companion object {
        /** The server drops clipboard transfers larger than 1 MiB */
        private const val MAX_PASTE_BYTES = 1_048_576

        private const val PREFS = "settings"
        private const val KEY_LIVE = "live_typing"

        /** Narrower windows (phones held upright) get the two-row app bar and icon-less action buttons */
        private const val NARROW_DP = 600
        /** The "Connected" label fits beside a long PC address from this window width */
        private const val CONNECTED_LABEL_DP = 720
    }

    private lateinit var binding: ActivityKeyboardBinding
    private val client get() = ConnectionManager.client
    private val modifiers = linkedSetOf<String>()

    private var live = true
    /** Exactly the text this field has typed on the PC since the last reset; the PC caret is after it */
    private var sent = ""
    private var diffPending = false
    /** Set while code changes the field, so the change is not sent */
    private var suppress = false
    private var notConnectedShown = false
    private var imeShown = false
    /** The action buttons' icons, to put back when the window is wide again (see fitWidth) */
    private lateinit var actionIcons: Map<MaterialButton, Drawable?>

    /** Hold Voice to dictate: each phrase is typed on the PC (live) or inserted into the field */
    private val voice = VoiceButton(this,
        onListening = { binding.btnVoice.setText(if (it) R.string.voice_listening else R.string.voice_input) },
        onPartial = { heard -> binding.etInput.hint = heard.ifEmpty { getString(R.string.type_text_here) } },
        onPhrase = { voiceText("$it ") })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKeyboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The field shrinks above the soft keyboard; the shortcut bar stays on screen. In landscape
        // that leaves too little height for every row, and the weighted field (minHeight doesn't
        // apply) collapses to 0 px, so the rows typing doesn't need go away (see updateBars).
        // Read from the root insets on each layout: the action bar's container consumes the
        // insets before an OnApplyWindowInsetsListener on our root would see the keyboard
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        binding.root.viewTreeObserver.addOnGlobalLayoutListener {
            val shown = ViewCompat.getRootWindowInsets(binding.root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (shown != imeShown) {
                imeShown = shown
                updateBars()
            }
        }

        val host = intent.getStringExtra("host") ?: ""
        val port = intent.getIntExtra("port", AirKeyboardClient.DEFAULT_PORT)

        binding.apply {
            tvConnectionStatus.text = "$host:$port"
            actionIcons = listOf(btnSend, btnPasteOnPc, btnCopyFromPc, btnVoice)
                .map { it as MaterialButton }.associateWith { it.icon }
            fitWidth(resources.configuration)

            setLive(getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LIVE, true))
            switchLiveTyping.isChecked = live
            switchLiveTyping.setOnCheckedChangeListener { _, isChecked ->
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LIVE, isChecked).apply()
                setLive(isChecked)
                // Switching mode in either direction drops the field without sending it
                resetField()
            }

            etInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    // Posted, so the several Editable changes of one IME call make one diff
                    if (live && !suppress && !diffPending) {
                        diffPending = true
                        etInput.post { applyDiff() }
                    }
                }
            })
            etInput.onEmptyBackspace = { emptyBackspace() }
            // For IMEs and hardware keyboards that send key events instead of deleteSurroundingText
            etInput.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN &&
                    live && etInput.text.isNullOrEmpty()
                ) {
                    emptyBackspace()
                    true
                } else {
                    false
                }
            }

            etInput.setOnEditorActionListener { _, actionId, event ->
                // Live: Enter inserts a line break, and the diff sends ENTER
                if (live) {
                    false
                } else if (actionId == EditorInfo.IME_ACTION_SEND ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    sendText()
                    true
                } else {
                    false
                }
            }

            btnSend.setOnClickListener { sendText() }
            btnPasteOnPc.setOnClickListener { pasteOnPc() }
            btnCopyFromPc.setOnClickListener { copyFromPc() }
            voice.attach(btnVoice)

            btnBackspace.setOnClickListener { backspace() }
            btnEnter.setOnClickListener { sendKey("ENTER") }
            btnTab.setOnClickListener { sendKey("TAB") }
            btnEscape.setOnClickListener { sendKey("ESCAPE") }

            btnCtrl.setOnClickListener { toggleModifier("CTRL", btnCtrl) }
            btnAlt.setOnClickListener { toggleModifier("ALT", btnAlt) }
            btnShift.setOnClickListener { toggleModifier("SHIFT", btnShift) }
            btnWin.setOnClickListener { toggleModifier("WIN", btnWin) }

            val panels = mapOf(
                R.id.btnTabFKeys to KeyPanels.F_KEYS,
                R.id.btnTabNumpad to KeyPanels.NUMPAD,
                R.id.btnTabNav to KeyPanels.NAV,
            )
            panelTabs.addOnButtonCheckedListener { group, _, _ ->
                // Unchecking the open tab closes the panel
                val rows = panels[group.checkedButtonId]
                if (rows == null) keyPanel.removeAllViews() else KeyPanels.build(keyPanel, rows) { sendKey(it) }
            }
            panelTabs.check(R.id.btnTabFKeys)

            btnBack.setOnClickListener { finish() }
        }
    }

    // Rotation doesn't recreate this screen (manifest configChanges), so no layout-land resources
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        fitWidth(newConfig)
    }

    /**
     * Fit the bars to the window width. Narrow (phones held upright): the status pill and Back
     * wrap to a second app bar row, and the action buttons drop their icons so their labels fit.
     * The app bar's "Connected" label is shown in landscape only, when there is room for it.
     */
    private fun fitWidth(config: Configuration) {
        val narrow = config.screenWidthDp < NARROW_DP
        binding.apply {
            tvConnectedLabel.visibility =
                if (config.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    config.screenWidthDp >= CONNECTED_LABEL_DP
                ) View.VISIBLE else View.GONE

            val row = if (narrow) appBarRow2 else appBarRow
            if (btnBack.parent !== row) {
                (statusPill.parent as ViewGroup).removeView(statusPill)
                (btnBack.parent as ViewGroup).removeView(btnBack)
                (statusPill.layoutParams as ViewGroup.MarginLayoutParams).marginStart =
                    if (narrow) 0 else resources.getDimensionPixelSize(R.dimen.kb_app_bar_gap)
                // Two rows: pill at the start, Back at the end; one row: title, switch, pill, Back
                row.addView(statusPill, if (narrow) 0 else row.childCount)
                row.addView(btnBack)
                appBarRow2.visibility = if (narrow) View.VISIBLE else View.GONE
            }

            actionIcons.forEach { (button, icon) -> button.icon = if (narrow) null else icon }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        RemoteKeys.onKeyDown(this, keyCode) || super.onKeyDown(keyCode, event)

    override fun onStart() {
        super.onStart()
        // The PC caret may have moved while this screen was away: start with an empty mirror
        if (live) resetField()
    }

    private fun setLive(on: Boolean) {
        live = on
        binding.etInput.live = on
        binding.btnSend.visibility = if (on) View.GONE else View.VISIBLE
        updateBars()
    }

    /** Soft keyboard open: no app bar or key panel, and no action row in live mode (no Send) */
    private fun updateBars() {
        binding.panelArea.visibility = if (imeShown) View.GONE else View.VISIBLE
        binding.actionRow.visibility = if (imeShown && live) View.GONE else View.VISIBLE
        binding.appBar.visibility = if (imeShown) View.GONE else View.VISIBLE
    }

    /** Send what changed in the field since the last call. The text is never logged. */
    private fun applyDiff() {
        diffPending = false
        val now = binding.etInput.text?.toString().orEmpty()
        if (now == sent) return
        val c = client
        if (c == null || !c.isConnectedStatus()) {
            if (!notConnectedShown) {
                notConnectedShown = true
                glassToast(R.string.not_connected)
            }
            // Edits made while disconnected are dropped
            resetField()
            return
        }
        notConnectedShown = false
        val plan = LiveTyping.plan(sent, now, modifiers.toList())
        // Live keystrokes go over the TCP session (reliable, in order)
        val reliable = live
        for (out in plan.out) {
            when (out) {
                is LiveTyping.KeyOut.Type -> c.sendKeyType(out.text, reliable)
                is LiveTyping.KeyOut.Press -> c.sendKeyPress(out.key, reliable)
            }
        }
        if (plan.usedModifiers) clearModifiers()
        if (plan.reset) resetField() else sent = now
    }

    /** Clear the field without sending it, and make the IME drop its cached word and composing state */
    private fun resetField() {
        suppress = true
        binding.etInput.text?.clear()
        suppress = false
        sent = ""
        getSystemService(InputMethodManager::class.java)?.restartInput(binding.etInput)
    }

    /** Backspace on the empty field: the diff can't see it, so send it directly */
    private fun emptyBackspace() {
        val c = client ?: return
        c.sendKeyPress((modifiers + "BACKSPACE").joinToString("+"), live)
        if (modifiers.isNotEmpty()) clearModifiers()
    }

    private fun backspace() {
        val text = binding.etInput.text
        if (!live || modifiers.isNotEmpty() || text == null) {
            sendKey("BACKSPACE")
        } else if (text.isEmpty()) {
            emptyBackspace()
        } else {
            // Not suppressed: deleting the last code point makes the diff send BACKSPACE
            text.delete(Character.offsetByCodePoints(text, text.length, -1), text.length)
        }
    }

    private fun sendText() {
        val text = binding.etInput.text.toString()
        if (text.isEmpty()) {
            glassToast("Please enter text")
            return
        }
        val c = client
        if (c == null || !c.isConnectedStatus()) {
            // Keep the text so it can be sent once the PC is back
            glassToast(R.string.not_connected)
            return
        }

        val combo = if (modifiers.isNotEmpty()) LiveTyping.comboKey(text) else null
        if (combo != null) {
            sendKey(combo)
        } else {
            c.sendKeyType(text)
        }
        binding.etInput.text?.clear()
    }

    /** Send the tablet clipboard's text to the PC clipboard and paste it there */
    private fun pasteOnPc() {
        val c = client
        if (c == null || !c.isConnectedStatus()) {
            glassToast(R.string.not_connected)
            return
        }
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        // Text items only: coerceToText would paste a URI/Intent string or read a whole file on the UI thread
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() ?: "" else ""
        when {
            text.isEmpty() ->
                glassToast(R.string.clipboard_empty)
            text.toByteArray(Charsets.UTF_8).size > MAX_PASTE_BYTES ->
                glassToast(R.string.paste_too_long)
            else -> {
                c.sendClipboardPaste(text)
                val chars = text.codePointCount(0, text.length)
                glassToast(getString(R.string.pasting_on_pc, chars))
            }
        }
    }

    /** Put the PC clipboard's text on the tablet clipboard */
    private fun copyFromPc() {
        val c = client
        if (c == null || !c.isConnectedStatus()) {
            glassToast(R.string.not_connected)
            return
        }
        c.requestPcClipboard { text ->
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (text.isNullOrEmpty()) {
                    glassToast(R.string.pc_clipboard_failed)
                    return@runOnUiThread
                }
                val clip = ClipData.newPlainText("PC clipboard", text)
                // Keeps it out of the clipboard preview: the PC clipboard may hold a password
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                val chars = text.codePointCount(0, text.length)
                glassToast(getString(R.string.copied_from_pc, chars))
            }
        }
    }

    private fun voiceText(text: String) {
        if (!live) {
            // Send mode: insert at the cursor, to be sent with the rest of the field
            val field = binding.etInput
            val start = minOf(field.selectionStart, field.selectionEnd).coerceAtLeast(0)
            val end = maxOf(field.selectionStart, field.selectionEnd).coerceAtLeast(0)
            field.text?.replace(start, end, text)
            return
        }
        val c = client
        if (c == null || !c.isConnectedStatus()) {
            glassToast(R.string.not_connected)
            return
        }
        c.sendKeyType(text, reliable = true)
        // The PC caret is now after the spoken text, past the field's mirror
        resetField()
    }

    private fun sendKey(key: String) {
        client?.sendKeyPress((modifiers + key).joinToString("+"), live)
        clearModifiers()
        // What the key does on the PC can't be mirrored in the field
        if (live) resetField()
    }

    private fun toggleModifier(name: String, button: Button) {
        if (!modifiers.remove(name)) modifiers.add(name)
        // Latched: bg_modifier's selected state (accent)
        button.isSelected = name in modifiers
    }

    private fun clearModifiers() {
        modifiers.clear()
        listOf(binding.btnCtrl, binding.btnAlt, binding.btnShift, binding.btnWin).forEach { it.isSelected = false }
    }
}
