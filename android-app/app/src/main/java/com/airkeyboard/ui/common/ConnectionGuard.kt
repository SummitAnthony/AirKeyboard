package com.airkeyboard.ui.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.airkeyboard.network.ConnectionManager
import com.airkeyboard.ui.connection.ConnectionActivity

/**
 * The way back to the connection screen when the connection is gone: App uses it when a session
 * is lost, control screens when they find no live client in onResume. The task is cleared, so
 * the connection screen is the only screen left and Back never shows a dead control screen.
 */
object ConnectionGuard {
    /**
     * The connection screen as a new, cleared task. With [reconnectHost] it tries to reconnect to
     * that PC (see ConnectionActivity.EXTRA_RECONNECT_HOST).
     */
    fun connectionScreen(context: Context, reconnectHost: String?, reconnectPort: Int): Intent =
        Intent(context, ConnectionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (reconnectHost != null) {
                putExtra(ConnectionActivity.EXTRA_RECONNECT_HOST, reconnectHost)
                putExtra(ConnectionActivity.EXTRA_RECONNECT_PORT, reconnectPort)
            }
        }

    /**
     * Leave [activity] for the connection screen, showing [message] if given. A lost session's
     * client is still attached (ConnectionManager keeps it until a disconnect), so its PC is
     * reconnected to; after a manual disconnect there is none.
     */
    fun returnToConnection(activity: Activity, message: String?) {
        if (activity.isFinishing) return
        message?.let { activity.glassToast(it) }
        val lost = ConnectionManager.client
        activity.startActivity(connectionScreen(activity, lost?.host, lost?.port ?: 0))
        activity.finish()
    }
}
