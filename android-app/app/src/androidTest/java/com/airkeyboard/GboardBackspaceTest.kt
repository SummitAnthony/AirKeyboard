package com.airkeyboard

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.airkeyboard.ui.connection.ConnectionActivity
import com.airkeyboard.ui.keyboard.KeyboardActivity
import com.airkeyboard.ui.trackpad.TrackpadActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Types "the" and deletes it with the real soft keyboard's keys (Gboard), in live typing, then
 * presses Delete twice on the empty field. The PC should get t, h, e and 5 BACKSPACE (server
 * log / capture window). Regression: the field was squeezed to 0 px when the keyboard opened in
 * landscape, lost focus, and Gboard's Delete key events then reached no view.
 * `-e qr <PC LAN IP>` (required). Run with a capture window in front on the PC.
 */
@RunWith(AndroidJUnit4::class)
class GboardBackspaceTest {

    private val instr = InstrumentationRegistry.getInstrumentation()

    @Test
    fun deleteKeyReachesThePc() {
        val qr = qrArg()
        ActivityScenario.launch<ConnectionActivity>(
            Intent(instr.targetContext, ConnectionActivity::class.java).putExtra(ConnectionActivity.EXTRA_AUTO_DISCOVER, false)
        )
        QrSteps.scan(qr)
        QrSteps.waitConnected(15_000)
        QrSteps.waitForTrackpad()
        // performClick, as in SmokeTest: an injected tap during the trackpad's opening animation is lost
        val trackpad = waitFor("trackpad screen") { onMain { resumedTrackpad() } }
        onMain { trackpad.findViewById<View>(R.id.btnKeyboard).performClick() }
        waitFor("keyboard screen") { onMain { resumedKeyboard() } }
        onView(withId(R.id.switchLiveTyping)).perform(setChecked(true))
        onView(withId(R.id.etInput)).perform(click())

        instr.uiAutomation.serviceInfo = instr.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val delete = waitFor("the keyboard's Delete key") { keyBounds("Delete") ?: keyBounds("Backspace") }
        SystemClock.sleep(1000)
        assertTrue("Text field lost focus when the keyboard opened", onMain { field().isFocused })

        for (letter in listOf("t", "h", "e")) {
            tap(waitFor("key $letter") { keyBounds(letter) })
            SystemClock.sleep(400)
        }
        assertEquals("the", onMain { field().text.toString() })
        repeat(3) { tap(delete); SystemClock.sleep(600) }
        assertEquals("Delete didn't empty the field", "", onMain { field().text.toString() })
        // On the empty field the diff can't see Delete: LiveEditText / the key listener report it
        repeat(2) { tap(delete); SystemClock.sleep(600) }
        SystemClock.sleep(1000)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instr.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** Main thread only */
    private fun resumedKeyboard() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
        .filterIsInstance<KeyboardActivity>().firstOrNull()

    /** Main thread only */
    private fun resumedTrackpad() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
        .filterIsInstance<TrackpadActivity>().firstOrNull()

    /** Main thread only */
    private fun field(): EditText = resumedKeyboard()!!.findViewById(R.id.etInput)

    /** Bounds of the soft keyboard key labelled [name], or null while the keyboard isn't shown */
    private fun keyBounds(name: String): Rect? {
        val ime = instr.uiAutomation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        val node = ime.root?.let { find(it, name) } ?: return null
        return Rect().also { node.getBoundsInScreen(it) }
    }

    private fun find(node: AccessibilityNodeInfo, name: String): AccessibilityNodeInfo? {
        val label = (node.contentDescription ?: node.text)?.toString().orEmpty()
        if (label.equals(name, ignoreCase = true)) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { find(it, name) }?.let { return it }
        return null
    }

    /** A tap on the screen, so it reaches the keyboard's window, not just ours */
    private fun tap(r: Rect) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, r.exactCenterX(), r.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instr.uiAutomation.injectInputEvent(event, true)
            event.recycle()
            SystemClock.sleep(60)
        }
    }

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 8_000, get: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            get()?.let { return it }
            SystemClock.sleep(200)
        }
        throw AssertionError("Timed out waiting for $what")
    }
}
