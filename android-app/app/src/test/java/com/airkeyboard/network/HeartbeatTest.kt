package com.airkeyboard.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The heartbeat's "PC gone" decision: only after a pong, 10 s after a ping with nothing heard since */
class HeartbeatTest {

    @Test
    fun notLostBeforeFirstPong() {
        val heartbeat = Heartbeat()
        heartbeat.onPingWritten(0, 0)
        assertFalse(heartbeat.isLost(60_000))
    }

    @Test
    fun lostWhenNoMessageTenSecondsAfterPing() {
        val heartbeat = Heartbeat()
        heartbeat.onMessage(0, isPong = true)
        heartbeat.onPingWritten(5_000, 5_000)
        assertFalse(heartbeat.isLost(10_000))
        assertFalse(heartbeat.isLost(14_999))
        assertTrue(heartbeat.isLost(15_001))
    }

    @Test
    fun anyMessageAfterPingCounts() {
        val heartbeat = Heartbeat()
        heartbeat.onMessage(0, isPong = true)
        heartbeat.onPingWritten(5_000, 5_000)
        heartbeat.onMessage(6_000, isPong = false)
        assertFalse(heartbeat.isLost(20_000))
    }

    @Test
    fun replyArrivingBeforeTheWriteIsRecordedCounts() {
        val heartbeat = Heartbeat()
        heartbeat.onMessage(0, isPong = true)
        // The reader handled the pong between the write starting and the sender recording it
        heartbeat.onMessage(5_001, isPong = true)
        heartbeat.onPingWritten(5_000, 5_002)
        assertFalse(heartbeat.isLost(10_000))
        assertFalse(heartbeat.isLost(20_000))
    }

    @Test
    fun longGapBetweenChecksRestartsTheWindow() {
        val heartbeat = Heartbeat()
        heartbeat.onMessage(0, isPong = true)
        assertFalse(heartbeat.isLost(4_000))
        heartbeat.onPingWritten(5_000, 5_000)
        // The tablet slept (or the sender was busy) for 25 s: the buffered reply may not be read yet
        assertFalse(heartbeat.isLost(30_000))
        assertFalse(heartbeat.isLost(35_000))
        assertTrue(heartbeat.isLost(40_001))
    }
}
