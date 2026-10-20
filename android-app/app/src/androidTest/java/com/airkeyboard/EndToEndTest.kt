package com.airkeyboard

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.slider.Slider
import com.airkeyboard.network.ConnectionManager
import com.airkeyboard.network.PasswordStore
import com.airkeyboard.ui.connection.ConnectionActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real app against a running Windows server on the LAN.
 * The PC side records what actually arrived (see windows-server/e2e-capture.ps1 and verify-e2e.py);
 * this test only checks the app-side state and paces the gestures.
 * Optional instrumentation args: `-e password <pw>` for a password server, `-e qr <payload>`
 * for the server's own QR payload (AirKeyboard.exe --print-pairing).
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {

    @Test
    fun fullSession() {
        // Auto-discovery connects on its own when exactly one server answers
        val connected = connectViaDiscovery(passwordArg(), remember = false)
        val host = connected.host
        val port = connected.port
        SystemClock.sleep(1500)

        // Trackpad: tap focuses the capture window, then move right, click
        onView(withId(R.id.trackpadView)).perform(click())
        pause()
        onView(withId(R.id.trackpadView)).perform(swipeBy(150f))
        onView(withId(R.id.btnLeftClick)).perform(click())
        pause()
        // Drags: hold still, then move; one lifted, one cancelled (the text box is empty, so nothing is selected)
        onView(withId(R.id.trackpadView)).perform(longPressDrag(120f, End.UP))
        pause()
        onView(withId(R.id.trackpadView)).perform(longPressDrag(-120f, End.CANCEL))
        pause()
        // Armed, then lifted without moving: no click and no press (verify-e2e counts clicks exactly)
        onView(withId(R.id.trackpadView)).perform(longPressLift())
        pause()
        onView(withId(R.id.trackpadView)).perform(twoFinger(0f, 0))     // right click
        pause()
        onView(withId(R.id.trackpadView)).perform(twoFinger(200f, 10))  // scroll
        pause()
        onView(withId(R.id.btnMiddleClick)).perform(click())
        onView(withId(R.id.btnScrollUp)).perform(click())
        onView(withId(R.id.btnScrollDown)).perform(click())
        pause()

        // Settings: double the sensitivity, then a left swipe of the same length should travel further
        onView(withId(R.id.btnSettings)).perform(click())
        pause()
        onView(withId(R.id.sliderSensitivity)).perform(setSlider(200f))
        onView(withId(R.id.btnBack)).perform(click())
        pause()
        pause()
        onView(withId(R.id.trackpadView)).perform(swipeBy(-150f))
        onView(withId(R.id.btnLeftClick)).perform(click())
        pause()

        // A drag left running: leaving the screen (onPause) must release the button
        onView(withId(R.id.trackpadView)).perform(longPressDrag(60f, End.NONE))

        // Keyboard, Send mode: text, Enter, Backspace, Tab, Escape
        onView(withId(R.id.btnKeyboard)).perform(click())
        pause()
        onView(withId(R.id.switchLiveTyping)).perform(setChecked(false))
        onView(withId(R.id.etInput)).perform(replaceText("Hello Wörld 123"))
        onView(withId(R.id.btnSend)).perform(click())
        onView(withId(R.id.btnEnter)).perform(click())
        onView(withId(R.id.etInput)).perform(replaceText("abc"))
        onView(withId(R.id.btnSend)).perform(click())
        onView(withId(R.id.btnBackspace)).perform(click())
        onView(withId(R.id.btnTab)).perform(click())
        onView(withId(R.id.btnEscape)).perform(click())
        pause()

        // Keyboard, live mode: IME calls as Gboard makes them (typing, autocorrect, swipe, emoji, paste)
        onView(withId(R.id.switchLiveTyping)).perform(setChecked(true))
        onView(withId(R.id.etInput)).perform(noSoftInput())
        for (c in listOf("l", "i", "v", "e", " ")) input { commitText(c, 1) }
        input { setComposingText("t", 1) }
        input { setComposingText("te", 1) }
        input { setComposingText("teh", 1) }
        input { commitText("the ", 1) }        // autocorrect
        input { setComposingText("quick", 1) }  // swipe
        input { finishComposingText() }
        input { commitText(" ", 1) }
        input { commitText("😀", 1) }
        input { commitText("x", 1) }
        input { deleteSurroundingText(1, 0) }
        input { commitText("y", 1) }
        onView(withId(R.id.btnBackspace)).perform(click())
        setClipboard("Wörld 漢字\nnext")
        onView(withId(R.id.etInput)).perform(pasteIntoField())   // a line break resets the field
        input { deleteSurroundingText(1, 0) }   // empty field: removes the PC's 't'
        onView(withId(R.id.btnShift)).perform(click())
        input { commitText("q", 1) }            // [SHIFT+Q]
        input { commitText("\n", 1) }
        pause()

        // Send mode again for Ctrl+A, then back to the default (live)
        onView(withId(R.id.switchLiveTyping)).perform(setChecked(false))
        onView(withId(R.id.btnCtrl)).perform(click())
        onView(withId(R.id.etInput)).perform(replaceText("a"))
        onView(withId(R.id.btnSend)).perform(click())
        pause()
        onView(withId(R.id.switchLiveTyping)).perform(setChecked(true))
        onView(withId(R.id.btnBack)).perform(click())
        pause()

        // Remotes: harmless entries only (Copy, Volume Up then Down)
        onView(withId(R.id.btnRemotes)).perform(click())
        pause()
        for ((position, label) in listOf(14 to "Copy", 3 to "Volume Up", 4 to "Volume Down")) {
            onView(withId(R.id.rvRemotes)).perform(scrollTo(position))
            onView(withText(label)).perform(click())
            pause()
        }
        onView(withId(R.id.btnBack)).perform(click())
        pause()

        // Put the sensitivity back to the default
        onView(withId(R.id.btnSettings)).perform(click())
        pause()
        onView(withId(R.id.sliderSensitivity)).perform(setSlider(100f))
        onView(withId(R.id.btnBack)).perform(click())
        pause()

        // Disconnect closes the session, and stays disconnected (no auto-discovery afterwards)
        onView(withId(R.id.btnDisconnect)).perform(click())
        SystemClock.sleep(4000)
        assertNull(ConnectionManager.client)

        // QR pairing: the server's own payload and the other accepted forms connect to the same PC
        val serverQr = InstrumentationRegistry.getArguments().getString("qr")
            ?: host.also { Log.w(TAG, "No -e qr argument: the server's QR payload was not checked") }
        for (payload in listOf(serverQr, "$host:$port")) {
            QrSteps.scan(payload)
            val client = QrSteps.waitConnected()
            assertEquals(host, client.host)
            QrSteps.waitForTrackpad()
            QrSteps.disconnectFromTrackpad()
        }
        // Codes that are not a pairing code connect nowhere
        for (payload in listOf("airkeyboard://$host", "https://example.com/$host", "$host:$port:secret", "$host:0")) {
            QrSteps.scan(payload)
            SystemClock.sleep(1500)
            assertNull(ConnectionManager.client)
            QrSteps.resumedConnectionActivity()
        }
        // An unreachable PC (TEST-NET-1): the attempt times out and the screen is usable again
        QrSteps.scan("192.0.2.1")
        waitUntil(8_000, "Scan button not enabled again after an unreachable PC") {
            try {
                onView(withId(R.id.btnScanQR)).check(matches(isEnabled()))
                true
            } catch (e: Throwable) {
                false
            }
        }
        assertNull(ConnectionManager.client)
    }

    /** Password server only (`-e password <pw>`): wrong password, remembered password, forget, QR prompt */
    @Test
    fun passwordSession() {
        val password = passwordArg()
        assumeTrue("No -e password argument", password != null)

        // 1-2. No saved key: the dialog appears; a wrong password shows the error and connects nothing
        PasswordStore.clear()
        ActivityScenario.launch(ConnectionActivity::class.java)
        waitUntil(15_000, "Password dialog did not appear") { dialogShown() }
        enterPassword("wrong-$password", remember = true)
        waitUntil(10_000, "No 'Wrong password' error") {
            try {
                onView(withText("Wrong password")).inRoot(isDialog()).check(matches(isDisplayed()))
                true
            } catch (e: Throwable) {
                false
            }
        }
        assertNull(ConnectionManager.client)

        // 3. The right password, remembered
        enterPassword(password!!, remember = true)
        val authed = QrSteps.waitConnected(15_000)
        val host = authed.host

        // 4. Type over the authenticated session (live mode: btnSend is hidden)
        QrSteps.waitForTrackpad()
        onView(withId(R.id.trackpadView)).perform(click())
        pause()
        onView(withId(R.id.btnKeyboard)).perform(click())
        pause()
        onView(withId(R.id.switchLiveTyping)).perform(setChecked(true))
        onView(withId(R.id.etInput)).perform(replaceText("Password OK 1"))
        onView(withId(R.id.btnEnter)).perform(click())
        pause()
        onView(withId(R.id.btnBack)).perform(click())
        pause()
        QrSteps.waitForTrackpad()
        QrSteps.disconnectFromTrackpad()

        // 5. The saved key connects without the dialog
        ActivityScenario.launch(ConnectionActivity::class.java)
        // Ask Espresso about the dialog only on a timeout: with no dialog it waits ~60 s for one
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (ConnectionManager.client?.isConnectedStatus() != true) {
            if (SystemClock.uptimeMillis() >= deadline) {
                assertFalse("Password dialog appeared although the password was remembered", dialogShown())
                throw AssertionError("Remembered password did not connect")
            }
            SystemClock.sleep(200)
        }
        ConnectionManager.client!!.sendKeyType("Remembered 2")
        SystemClock.sleep(1500)

        // 6. Forget the saved passwords, then disconnect
        QrSteps.waitForTrackpad()
        onView(withId(R.id.btnSettings)).perform(click())
        pause()
        onView(withId(R.id.btnForgetPasswords)).perform(ViewActions.scrollTo(), click())
        pause()
        // The app bar scrolls with Settings: in landscape the scroll above hides Back
        onView(withId(R.id.btnBack)).perform(ViewActions.scrollTo(), click())
        pause()
        QrSteps.waitForTrackpad()
        QrSteps.disconnectFromTrackpad()

        // 7. A QR scan of the same PC now asks again; Cancel connects nothing
        QrSteps.scan(host)
        waitUntil(10_000, "Password dialog did not appear after the QR scan") { dialogShown() }
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())   // Cancel
        SystemClock.sleep(500)
        assertNull(ConnectionManager.client)
    }

    private fun pause() = SystemClock.sleep(700)

    private fun input(block: android.view.inputmethod.InputConnection.() -> Unit) {
        onView(withId(R.id.etInput)).perform(ime(block))
    }

    private fun waitUntil(timeoutMs: Long, message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue(message, SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(200)
        }
    }

    private fun dialogShown(): Boolean = try {
        onView(withId(R.id.etPassword)).inRoot(isDialog()).check(matches(isDisplayed()))
        true
    } catch (e: Throwable) {
        false
    }

    /** Types into the shown password dialog and presses Connect */
    private fun enterPassword(password: String, remember: Boolean) {
        onView(withId(R.id.etPassword)).inRoot(isDialog()).perform(replaceText(password))
        onView(withId(R.id.cbRemember)).inRoot(isDialog()).perform(setChecked(remember))
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(clickWithoutTouch())   // Connect
    }

    /** A real paste from the tablet clipboard into the field, as the long-press menu does it */
    private fun pasteIntoField() = object : ViewAction {
        override fun getConstraints() = isDisplayed()
        override fun getDescription() = "paste into the field"
        override fun perform(uc: UiController, view: View) {
            (view as EditText).onTextContextMenuItem(android.R.id.paste)
            uc.loopMainThreadUntilIdle()
        }
    }

    private fun swipeBy(dx: Float) = GeneralSwipeAction(
        Swipe.SLOW,
        CoordinatesProvider { v -> center(v) },
        CoordinatesProvider { v -> center(v).let { floatArrayOf(it[0] + dx, it[1]) } },
        Press.FINGER
    )

    /** Two fingers down, optionally dragged vertically by [dy] in [steps], then lifted */
    private fun twoFinger(dy: Float, steps: Int) = object : ViewAction {
        override fun getConstraints() = isDisplayed()
        override fun getDescription() = "two-finger gesture dy=$dy"
        override fun perform(uc: UiController, view: View) {
            val cx = view.width / 2f
            val cy = view.height / 2f
            val down = SystemClock.uptimeMillis()
            fun event(action: Int, count: Int, y: Float): MotionEvent {
                val props = Array(count) { i ->
                    MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER }
                }
                val coords = Array(count) { i ->
                    MotionEvent.PointerCoords().apply { x = cx - 50f + i * 100f; this.y = y; pressure = 1f; size = 1f }
                }
                return MotionEvent.obtain(
                    down, SystemClock.uptimeMillis(), action, count, props, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
                )
            }
            val second = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
            view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 1, cy))
            view.dispatchTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN or second, 2, cy))
            for (s in 1..steps) {
                uc.loopMainThreadForAtLeast(16)
                view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 2, cy + dy * s / steps))
            }
            view.dispatchTouchEvent(event(MotionEvent.ACTION_POINTER_UP or second, 2, cy + dy))
            view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 1, cy + dy))
        }
    }

    private fun setSlider(value: Float) = object : ViewAction {
        override fun getConstraints() = isDisplayed()
        override fun getDescription() = "set slider to $value"
        override fun perform(uc: UiController, view: View) {
            (view as Slider).value = value
        }
    }

    private fun scrollTo(position: Int) = object : ViewAction {
        override fun getConstraints() = isDisplayed()
        override fun getDescription() = "scroll to $position"
        override fun perform(uc: UiController, view: View) {
            (view as RecyclerView).scrollToPosition(position)
            uc.loopMainThreadUntilIdle()
        }
    }

    private companion object {
        const val TAG = "EndToEndTest"
    }
}
