package com.airkeyboard

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.airkeyboard.ui.connection.ConnectionActivity
import com.airkeyboard.ui.keyboard.KeyboardActivity
import com.airkeyboard.ui.trackpad.TrackpadActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Losing the PC, driven together with a script on the PC: each test connects, opens the keyboard
 * screen and logs "RECONNECT_READY <name>". The script then restarts the server ([serverRestart])
 * or suspends its process ([serverHangs]: TCP stays open, so only the heartbeat notices) and, for
 * the hang, resumes it after this test logs "LOST". The app must go to the connection screen,
 * reconnect to the same PC on its own and leave no screen of the old session behind.
 * Sends no input to the PC. `-e qr <PC LAN IP>` (required).
 */
@RunWith(AndroidJUnit4::class)
class ReconnectTest {

    private val instr = InstrumentationRegistry.getInstrumentation()

    @Test
    fun serverRestart() = loseAndReconnect("restart", lostWithinMs = 10_000)

    @Test
    fun serverHangs() = loseAndReconnect("hang", lostWithinMs = 30_000)

    private fun loseAndReconnect(name: String, lostWithinMs: Long) {
        val qr = qrArg()
        ActivityScenario.launch<ConnectionActivity>(
            Intent(instr.targetContext, ConnectionActivity::class.java).putExtra(ConnectionActivity.EXTRA_AUTO_DISCOVER, false)
        )
        QrSteps.scan(qr)
        val first = QrSteps.waitConnected(15_000)
        QrSteps.waitForTrackpad()
        val trackpad = waitResumed(TrackpadActivity::class.java, 5_000)
        instr.runOnMainSync { trackpad.findViewById<View>(R.id.btnKeyboard).performClick() }
        waitResumed(KeyboardActivity::class.java, 8_000)
        // The heartbeat counts only after the PC answered its first ping (sent on connect)
        SystemClock.sleep(1_000)

        Log.i(TAG, "RECONNECT_READY $name")
        val lostAt = SystemClock.uptimeMillis()
        QrSteps.resumedConnectionActivity(lostWithinMs)
        Log.i(TAG, "LOST $name after ${SystemClock.uptimeMillis() - lostAt} ms")

        // The reconnect loop tries for 30 s; the same PC, straight back to the trackpad
        val again = QrSteps.waitConnected(35_000)
        assertNotSame(first, again)
        assertEquals(first.host, again.host)
        waitResumed(TrackpadActivity::class.java, 10_000)
        Log.i(TAG, "RECONNECTED $name after ${SystemClock.uptimeMillis() - lostAt} ms")

        // The old session's screens are gone: one trackpad, no keyboard screen behind it
        waitFor("old screens to close", 5_000) {
            val alive = aliveActivities()
            alive.none { it is KeyboardActivity } && alive.count { it is TrackpadActivity } == 1
        }
    }

    private fun aliveActivities(): List<Activity> {
        var alive: List<Activity> = emptyList()
        instr.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            alive = listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)
                .flatMap { monitor.getActivitiesInStage(it) }
        }
        return alive
    }

    private fun <T : Activity> waitResumed(type: Class<T>, timeoutMs: Long): T {
        var found: T? = null
        waitFor("${type.simpleName} resumed", timeoutMs) {
            instr.runOnMainSync {
                found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance(type).firstOrNull()
            }
            found != null
        }
        return found!!
    }

    private fun waitFor(what: String, timeoutMs: Long, ok: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!ok()) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("Timed out waiting for $what (alive: ${aliveActivities()})")
            SystemClock.sleep(200)
        }
    }

    private companion object {
        const val TAG = "ReconnectTest"
    }
}
