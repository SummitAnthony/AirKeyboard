package com.airkeyboard.network

/**
 * Holds the single live connection shared by all screens.
 * ConnectionActivity sets it after a successful handshake; the control screens only use it.
 *
 * A connection the server ends (or that the heartbeat drops) stays here, no longer connected,
 * so the connection screen knows which PC to reconnect to; [disconnect] clears it.
 */
object ConnectionManager {
    @Volatile
    var client: AirKeyboardClient? = null
        private set

    /**
     * Runs on a background thread when the attached client loses its connection (not after
     * [disconnect] or a replacing [attach]). Set once by App, which routes to the connection screen.
     */
    @Volatile
    var onConnectionLost: ((host: String, port: Int) -> Unit)? = null

    fun attach(connected: AirKeyboardClient) {
        client?.let {
            // Replaced: the old session ends quietly, so no screen reacts to it
            it.onDisconnected = null
            it.close()
        }
        client = connected
        connected.onDisconnected = {
            if (client === connected) onConnectionLost?.invoke(connected.host, connected.port)
        }
    }

    fun disconnect() {
        client?.let {
            it.onDisconnected = null
            it.close()
        }
        client = null
    }
}
