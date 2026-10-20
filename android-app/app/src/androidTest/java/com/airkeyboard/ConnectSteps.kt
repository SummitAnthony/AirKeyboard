package com.airkeyboard

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import com.airkeyboard.network.ConnectionManager
import com.airkeyboard.network.AirKeyboardClient
import com.airkeyboard.ui.connection.ConnectionActivity

/** The PC password from `-e password ...`, or null for a server without one. Never log it. */
fun passwordArg(): String? = InstrumentationRegistry.getArguments().getString("password")

/** The PC's QR payload from `-e qr <PC LAN IP>`, required by the tests that pair by QR code */
fun qrArg(): String = InstrumentationRegistry.getArguments().getString("qr")
    ?: throw IllegalArgumentException("Pass the PC's address: -e qr <PC LAN IP>")

/**
 * Launches the connection screen and waits up to 15 s for auto-discovery to connect. If the
 * password dialog appears instead, enters [password] with Remember set to [remember] and
 * waits up to 15 s more. Returns the connected client.
 */
fun connectViaDiscovery(password: String? = passwordArg(), remember: Boolean = false): AirKeyboardClient {
    ActivityScenario.launch(ConnectionActivity::class.java)
    val deadline = SystemClock.uptimeMillis() + 15_000
    var hadFocus = false
    while (true) {
        ConnectionManager.client?.let { if (it.isConnectedStatus()) return it }
        if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("Auto-discovery did not connect")
        // A dialog takes the window focus from the resumed screen. Only then ask Espresso,
        // which waits up to 60 s for a dialog root that is not there.
        val activity = QrSteps.currentConnectionActivity()
        if (activity != null) {
            var focused = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { focused = activity.hasWindowFocus() }
            if (focused) hadFocus = true else if (hadFocus && passwordDialogShown()) break
        }
        SystemClock.sleep(200)
    }
    if (password == null) throw AssertionError("The PC asked for a password; pass -e password")
    onView(withId(R.id.etPassword)).inRoot(isDialog()).perform(replaceText(password))
    onView(withId(R.id.cbRemember)).inRoot(isDialog()).perform(setChecked(remember))
    onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(clickWithoutTouch())   // Connect
    val loginDeadline = SystemClock.uptimeMillis() + 15_000
    while (true) {
        ConnectionManager.client?.let { if (it.isConnectedStatus()) return it }
        if (SystemClock.uptimeMillis() >= loginDeadline) throw AssertionError("Password login did not connect")
        SystemClock.sleep(200)
    }
}

private fun passwordDialogShown(): Boolean = try {
    onView(withId(R.id.etPassword)).inRoot(isDialog()).check(matches(isDisplayed()))
    true
} catch (e: Throwable) {
    false
}
