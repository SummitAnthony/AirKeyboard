package com.airkeyboard

import android.os.SystemClock
import android.util.Log
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airkeyboard.network.ConnectionManager
import com.airkeyboard.network.PasswordStore
import com.airkeyboard.network.AirKeyboardClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Stress run against a live Windows server: input bursts, drags, "Paste on PC" (small and ~140 KB),
 * long typed text, live typing, the T5 latency run, reconnect churn and QR reconnects. The PC side
 * recomputes the expected capture text
 * normCRLF(P1) + "\r\n" + normCRLF(P2) + T3 + T4 + T5 (over TCP) + T5 (over UDP),
 * so the strings below must not change. Optional `-e password <pw>` for a password server.
 */
@RunWith(AndroidJUnit4::class)
class StressTest {

    companion object {
        private const val TAG = "StressTest"

        const val P1 = "Paste test: Wörld 😀 漢字\nsecond line\n\tthird line"

        val P2: String = buildString {
            for (i in 0..1999) {
                append(String.format(Locale.ROOT, "%05d", i))
                append(" The quick brown fox jumps over the lazy dog 0123456789 äöü €\n")
            }
        }

        val T3: String = buildString {
            for (i in 0..99) {
                append("[").append(String.format(Locale.ROOT, "%03d", i)).append("]abcdefghijklmnopqrstuvwxyzÄÖÜ")
            }
        }

        val T4: String = buildString {
            for (i in 0..99) append(String.format(Locale.ROOT, "<%02d>liveÄö😀 ", i))
        }

        /** Sent one letter per frame, first over the TCP session, then again over UDP */
        val T5: String = buildString { for (i in 0..199) append('a' + i % 26) }

        /** Pause between T5 letters (about fast typing) */
        private const val T5_GAP_MS = 50L

        /**
         * One line per T5 send, "<tcp|udp> <letter> <currentTimeMillis>" (verify-stress.py --latency), logged
         * under this tag, so it can be read from a release build too (no run-as there):
         * adb logcat -v raw -s StressLatency:I > latency-send.txt, running during the test (docs/TESTING.md)
         */
        const val LATENCY_TAG = "StressLatency"
    }

    @Test
    fun stressSession() {
        // a. Connect via auto-discovery (password dialog if the PC has one), then tap to focus the capture window
        val password = passwordArg()
        if (password != null) PasswordStore.clear()
        connectViaDiscovery(password, remember = false)
        SystemClock.sleep(1500)
        onView(withId(R.id.trackpadView)).perform(click())
        SystemClock.sleep(700)

        // b. Rapid input: net-zero moves, then clicks
        val client = ConnectionManager.client!!
        repeat(1000) {
            client.sendMouseMove(3, 0)
            client.sendMouseMove(-3, 0)
        }
        repeat(50) { i ->
            if (i > 0) SystemClock.sleep(30)
            client.sendMouseClick(1)
        }
        SystemClock.sleep(2000)

        // b2. Drags (the text box is still empty, so nothing is selected): lifted, cancelled,
        // then one held still for 7 s, longer than the server's 5 s stuck-button timeout
        repeat(10) { i ->
            onView(withId(R.id.trackpadView)).perform(longPressDrag(if (i % 2 == 0) 80f else -80f, End.UP))
            SystemClock.sleep(200)
        }
        repeat(5) { i ->
            onView(withId(R.id.trackpadView)).perform(longPressDrag(if (i % 2 == 0) 80f else -80f, End.CANCEL))
            SystemClock.sleep(200)
        }
        onView(withId(R.id.trackpadView)).perform(longPressDrag(-80f, End.UP, holdMs = 7000))
        SystemClock.sleep(1000)
        // Armed, then lifted without moving: no click and no press (verify-stress counts them exactly)
        repeat(3) {
            onView(withId(R.id.trackpadView)).perform(longPressLift())
            SystemClock.sleep(200)
        }

        // c. Paste a short multi-line Unicode text, then Enter
        onView(withId(R.id.btnKeyboard)).perform(click())
        SystemClock.sleep(700)
        setClipboard(P1)
        onView(withId(R.id.btnPasteOnPc)).perform(click())
        SystemClock.sleep(2500)
        client.sendKeyPress("ENTER")
        SystemClock.sleep(1500)

        // d. Paste ~140 KB
        setClipboard(P2)
        onView(withId(R.id.btnPasteOnPc)).perform(click())
        SystemClock.sleep(8000)

        // e. Type long text through the keyboard screen in Send mode
        onView(withId(R.id.switchLiveTyping)).perform(setChecked(false))
        onView(withId(R.id.etInput)).perform(replaceText(T3))
        onView(withId(R.id.btnSend)).perform(click())
        SystemClock.sleep(6000)

        // e2. Live typing T4, one IME call per action, each edit its own frame
        onView(withId(R.id.switchLiveTyping)).perform(setChecked(true))
        onView(withId(R.id.etInput)).perform(noSoftInput())
        for (i in 0..99) {
            for (c in String.format(Locale.ROOT, "<%02d>", i)) input { commitText(c.toString(), 1) }
            for (word in listOf("l", "li", "liv", "live")) input { setComposingText(word, 1) }
            input { finishComposingText() }
            input { commitText("z", 1) }
            input { commitText("z", 1) }
            input { deleteSurroundingText(2, 0) }
            for (s in listOf("Ä", "ö", "😀", " ")) input { commitText(s, 1) }
        }
        SystemClock.sleep(15000)

        // e3. T5 latency: the same letters over the TCP session, then over UDP. The PC stamps each
        // arrival; verify-stress.py --latency pairs them with these send times
        val sends = StringBuilder()
        for ((path, reliable) in listOf("tcp" to true, "udp" to false)) {
            for (i in T5.indices) {
                sends.append(path).append(' ').append(T5[i]).append(' ').append(System.currentTimeMillis()).append('\n')
                client.sendKeyType(T5[i].toString(), reliable = reliable)
                SystemClock.sleep(T5_GAP_MS)
            }
        }
        // A few entries of 50 lines each, well under logcat's entry size limit
        sends.lines().filter { it.isNotEmpty() }.chunked(50).forEach { Log.i(LATENCY_TAG, it.joinToString("\n")) }
        Log.i(TAG, "T5 send times logged under $LATENCY_TAG")
        SystemClock.sleep(3000)

        // f. Back to the trackpad, then reconnect churn on the same host/port
        onView(withId(R.id.btnBack)).perform(click())
        SystemClock.sleep(700)
        // Wait for the landscape TrackpadActivity relaunch before the client goes null in churn
        onView(withId(R.id.trackpadView)).check(matches(isDisplayed()))
        // f0. A drag left running: the churn's first disconnect must make the server release it
        onView(withId(R.id.trackpadView)).perform(longPressDrag(80f, End.NONE))
        val host = client.host
        val port = client.port
        for (cycle in 1..15) {
            ConnectionManager.disconnect()
            val next = AirKeyboardClient(host, port)
            val result = runBlocking { next.connect() }
            assertTrue("Reconnect $cycle failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
            ConnectionManager.attach(next)
            SystemClock.sleep(300)
        }
        val last = ConnectionManager.client
        assertNotNull(last)
        assertTrue("Final client not connected", last!!.isConnectedStatus())
        last.sendMouseScroll(0)
        SystemClock.sleep(500)
        // The drag keepalive saw its client gone and ended the drag
        onView(withId(R.id.trackpadView)).check { view, e ->
            if (e != null) throw e
            assertFalse("Trackpad still dragging after the churn", view.isActivated)
        }
        onView(withId(R.id.trackpadView)).perform(cancelTouch())

        // g. Disconnect closes the session
        onView(withId(R.id.btnDisconnect)).perform(click())
        SystemClock.sleep(1000)
        assertNull(ConnectionManager.client)

        // h. A manual disconnect stays disconnected, then QR connect/disconnect cycles
        SystemClock.sleep(4000)
        assertNull(ConnectionManager.client)
        val payloads = listOf(host, "$host:$port")
        val connectTimes = mutableListOf<Long>()
        for (cycle in 0 until 10) {
            val start = SystemClock.uptimeMillis()
            QrSteps.scan(payloads[cycle % payloads.size])
            val qrClient = QrSteps.waitConnected()
            connectTimes += SystemClock.uptimeMillis() - start
            assertEquals(host, qrClient.host)
            QrSteps.waitForTrackpad()
            QrSteps.disconnectFromTrackpad()
        }
        assertNull(ConnectionManager.client)
        Log.i(TAG, "QR connect time: max ${connectTimes.max()} ms, average ${connectTimes.average().toLong()} ms")
    }

    private fun input(block: android.view.inputmethod.InputConnection.() -> Unit) {
        onView(withId(R.id.etInput)).perform(ime(block))
    }
}
