package com.airkeyboard.ui.common

import android.app.Activity
import android.view.KeyEvent
import com.airkeyboard.network.ConnectionManager

/**
 * The tablet's volume buttons as a PC volume remote. Call from a control screen's onKeyDown:
 * `if (RemoteKeys.onKeyDown(this, keyCode)) return true`. Repeats while held, like on the PC.
 */
object RemoteKeys {
    fun onKeyDown(activity: Activity, keyCode: Int): Boolean {
        val key = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "VOLUME_UP"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "VOLUME_DOWN"
            else -> return false
        }
        val client = ConnectionManager.client
        if (client?.isConnectedStatus() != true || !AppPrefs.volumeKeysRemote(activity)) return false
        client.sendKeyPress(key)
        return true
    }
}
