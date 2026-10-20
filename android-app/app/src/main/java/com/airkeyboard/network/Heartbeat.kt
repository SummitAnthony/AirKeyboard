package com.airkeyboard.network

/**
 * Decides when our PC has gone silent (see the "sys png" heartbeat in [AirKeyboardClient]).
 *
 * The PC counts as gone when a ping was written more than [TIMEOUT_MS] ago and no server message
 * of any kind has arrived since that write began. Only enforced once a "png" reply has arrived in
 * this session, so an older AirKeyboard.exe that never answers pings is never dropped. Times
 * are in ms (currentTimeMillis); called from the sender and reader threads.
 */
class Heartbeat {
    companion object {
        const val TIMEOUT_MS = 10_000L
        /** Checks further apart than this mean the tablet slept or the sender was busy (a long paste) */
        const val PAUSE_MS = 10_000L
    }

    /** A pong arrived this session: the server answers pings */
    private var answersPings = false
    /** When the oldest ping not followed by any message was written, -1 if none */
    private var unansweredSince = -1L
    private var lastMessageAt = -1L
    private var lastCheckAt = -1L

    /**
     * A ping's write began at [startedMs] and finished at [writtenMs]. A reply the reader handled
     * in between (before this call) already answers it.
     */
    @Synchronized
    fun onPingWritten(startedMs: Long, writtenMs: Long) {
        if (lastMessageAt >= startedMs) return
        // Keep the oldest: later pings must not push the deadline back
        if (unansweredSince < 0) unansweredSince = writtenMs
    }

    @Synchronized
    fun onMessage(nowMs: Long, isPong: Boolean) {
        if (isPong) answersPings = true
        lastMessageAt = nowMs
        unansweredSince = -1L
    }

    /**
     * Call once per heartbeat tick. After a gap longer than [PAUSE_MS] since the last check, a
     * reply may still be waiting to be read, so the window restarts instead of reporting a loss.
     */
    @Synchronized
    fun isLost(nowMs: Long): Boolean {
        val paused = lastCheckAt >= 0 && nowMs - lastCheckAt > PAUSE_MS
        lastCheckAt = nowMs
        if (paused) {
            if (unansweredSince >= 0) unansweredSince = nowMs
            return false
        }
        return answersPings && unansweredSince >= 0 && nowMs - unansweredSince > TIMEOUT_MS
    }
}
