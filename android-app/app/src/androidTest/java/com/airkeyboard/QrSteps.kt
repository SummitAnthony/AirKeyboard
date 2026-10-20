package com.airkeyboard

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.airkeyboard.network.ConnectionManager
import com.airkeyboard.network.AirKeyboardClient
import com.airkeyboard.ui.connection.ConnectionActivity

/** Steps for the QR pairing path, without a camera: scanned text goes straight to the activity */
object QrSteps {

    /** The resumed ConnectionActivity, or null if there is none right now */
    fun currentConnectionActivity(): ConnectionActivity? {
        var found: ConnectionActivity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ConnectionActivity>()
                .firstOrNull()
        }
        return found
    }

    /** The resumed ConnectionActivity, waiting up to [timeoutMs] for one */
    fun resumedConnectionActivity(timeoutMs: Long = 5_000): ConnectionActivity {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            currentConnectionActivity()?.let { return it }
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("No resumed ConnectionActivity")
            SystemClock.sleep(200)
        }
    }

    /**
     * Hands [text] to the connection screen as if the camera had scanned it. Right after a
     * disconnect the display may still be landscape while the screen is laid out in portrait;
     * a trackpad launched then sees the landscape display at once and passes [waitForTrackpad],
     * and the display turns to portrait and back under it. So first wait for the rotation.
     */
    fun scan(text: String) {
        val activity = resumedConnectionActivity()
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (true) {
            var outside: String? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                outside = outsideInput(activity.window.decorView, "Connection screen")
            }
            if (outside == null) break
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError(outside)
            SystemClock.sleep(100)
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.handleScannedText(text) }
    }

    /** The connected client, waiting up to [timeoutMs] for it */
    fun waitConnected(timeoutMs: Long = 10_000): AirKeyboardClient {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val client = ConnectionManager.client
            if (client != null && client.isConnectedStatus()) return client
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("Not connected within $timeoutMs ms")
            SystemClock.sleep(100)
        }
    }

    /**
     * Waits for the trackpad screen, which may still be relaunching in landscape. The activity is
     * laid out in landscape (Android 11 fixed-rotation transform) about 0.5 s before the display
     * itself rotates; until then injected taps at landscape coordinates miss every window and are
     * refused. So also wait until the whole window fits in input's display area.
     */
    fun waitForTrackpad(timeoutMs: Long = 10_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            try {
                onView(withId(R.id.trackpadView)).check(matches(isDisplayed()))
                onView(withId(R.id.trackpadView)).check { view, noView ->
                    if (view == null) throw noView!!
                    outsideInput(view.rootView, "Trackpad")?.let { throw AssertionError(it) }
                }
                return
            } catch (e: Throwable) {
                if (SystemClock.uptimeMillis() >= deadline) throw e
                SystemClock.sleep(200)
            }
        }
    }

    /** Why [root]'s window is not inside input's display area (still rotating), or null if it is */
    private fun outsideInput(root: View, name: String): String? {
        // An empty rect fits anywhere: a window not laid out yet proves nothing
        if (root.width == 0 || root.height == 0) return "$name window not laid out yet"
        val loc = IntArray(2).also { root.getLocationOnScreen(it) }
        val window = Rect(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height)
        val input = inputViewport()
        if (input != null && !input.contains(window)) {
            return "$name window $window is outside input's display $input (still rotating)"
        }
        return null
    }

    /** Display 0's logical frame as input sees it ('dumpsys input' viewport), or null if not found */
    private fun inputViewport(): Rect? {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys input")
        val text = ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        val m = Regex("""Viewport INTERNAL: displayId=0,.*?logicalFrame=\[(\d+), (\d+), (\d+), (\d+)]""").find(text)
            ?: return null
        val (l, t, r, b) = m.destructured
        return Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }

    /** Disconnect from the trackpad screen, then wait for the session to close and the connection screen */
    fun disconnectFromTrackpad() {
        // performClick: a touch right after Back can land on the previous screen while it is still closing
        onView(withId(R.id.btnDisconnect)).perform(clickWithoutTouch())
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (ConnectionManager.client != null) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("Client still set after Disconnect")
            SystemClock.sleep(100)
        }
        resumedConnectionActivity()
    }
}
