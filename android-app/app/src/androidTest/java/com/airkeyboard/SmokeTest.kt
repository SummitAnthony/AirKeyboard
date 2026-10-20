package com.airkeyboard

import android.app.Activity
import android.app.UiAutomation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.material.button.MaterialButtonToggleGroup
import com.airkeyboard.network.ConnectionManager
import com.airkeyboard.ui.airmouse.AirMouseActivity
import com.airkeyboard.ui.apps.AppsActivity
import com.airkeyboard.ui.common.AppPrefs
import com.airkeyboard.ui.connection.ConnectionActivity
import com.airkeyboard.ui.settings.SettingsActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Quick smoke run against the live PC server, portrait then landscape: opens every screen and
 * reads data, but sends no keys and no clicks to the PC. `-e qr <PC LAN IP>` (required).
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instr.targetContext
    private var autoRotate = 0
    /** `-e shots true`: a screenshot of every screen into the app's files/shots/ */
    private val shots = InstrumentationRegistry.getArguments().getString("shots") == "true"
    private var orientation = ""
    /** [LayoutCheck] lines of every screen, written to the app's files/layout-report.txt */
    private val layoutReport = mutableListOf<String>()

    @Test
    fun smoke() {
        autoRotate = Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        val qr = qrArg()
        ActivityScenario.launch<ConnectionActivity>(
            Intent(ctx, ConnectionActivity::class.java).putExtra(ConnectionActivity.EXTRA_AUTO_DISCOVER, false)
        )
        for ((name, rotation) in listOf("portrait" to UiAutomation.ROTATION_FREEZE_0, "landscape" to UiAutomation.ROTATION_FREEZE_90)) {
            rotate(rotation)
            orientation = name
            shot("connection")
            QrSteps.scan(qr)
            val host = QrSteps.waitConnected(15_000).host
            QrSteps.waitForTrackpad()
            shot("trackpad")
            step(name, "1 trackpad+settings") { settingsStep() }
            step(name, "2 keyboard") { keyboardStep() }
            step(name, "3 remotes") { remotesStep() }
            step(name, "4 recent PCs") {
                // performClick: a tap right after Back can land on the Remotes window still closing above
                tap(R.id.btnDisconnect)
                waitFor(5_000, "Client still set after Disconnect") { ConnectionManager.client == null }
                QrSteps.resumedConnectionActivity()
                waitFor(5_000, "Recent PCs does not list $host") { recentListed(host) }
            }
        }
        java.io.File(ctx.filesDir, "layout-report.txt").writeText(layoutReport.joinToString("\n"))
        layoutReport.forEach { Log.i(TAG, it) }
        val failures = layoutReport.filter { it.startsWith("FAIL") }
        assertTrue("Layout problems:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @After
    fun restore() {
        instr.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        // Wait for it: the runner kills the process right after, which can drop a pending rotation
        val display = ctx.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (display.rotation != 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        if (autoRotate == 1) instr.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }

    private fun step(orientation: String, name: String, block: () -> Unit) {
        try {
            block()
            Log.i(TAG, "PASS $orientation step $name")
        } catch (e: Throwable) {
            Log.e(TAG, "FAIL $orientation step $name", e)
            throw e
        }
    }

    private fun settingsStep() {
        tap(R.id.btnSettings)
        waitFor(5_000, "Settings did not open") { displayed(R.id.toggleTheme) }
        shot("settings")
        if (shots) {
            val a = resumed()!!
            instr.runOnMainSync {
                (a.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as? android.widget.ScrollView)
                    ?.fullScroll(View.FOCUS_DOWN)
            }
            shot("settings-bottom")
        }
        // Switch to the other theme and back, so the shots of the later screens keep the saved one
        val themes = if (AppPrefs.redTheme(instr.targetContext)) listOf(R.id.btnThemeBlue, R.id.btnThemeRed)
            else listOf(R.id.btnThemeRed, R.id.btnThemeBlue)
        for (button in themes) {
            tap(button)
            waitTheme(button)
        }
        tap(R.id.btnBack)
        QrSteps.waitForTrackpad()
    }

    /** The screens recreate in the new theme: wait for a resumed settings screen showing [checked], unchanged for 500 ms */
    private fun waitTheme(checked: Int) {
        var last: Activity? = null
        var since = 0L
        waitFor(10_000, "Settings did not settle on the new theme") {
            val a = resumed() as? SettingsActivity
            var ok = false
            if (a != null) instr.runOnMainSync {
                ok = a.findViewById<MaterialButtonToggleGroup>(R.id.toggleTheme)?.checkedButtonId == checked &&
                    a.hasWindowFocus()
            }
            if (!ok || a !== last) {
                last = if (ok) a else null
                since = SystemClock.uptimeMillis()
                false
            } else {
                SystemClock.uptimeMillis() - since >= 500
            }
        }
        instr.waitForIdleSync()
    }

    private fun keyboardStep() {
        tap(R.id.btnKeyboard)
        waitFor(5_000, "Keyboard did not open") { displayed(R.id.btnCopyFromPc) }
        val activity = resumed()!!
        // With the soft keyboard up, as when typing (API 30+ can tell)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val deadline = SystemClock.uptimeMillis() + 3_000
            var ime = false
            while (!ime && SystemClock.uptimeMillis() < deadline) {
                instr.runOnMainSync {
                    ime = activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
                }
                if (!ime) SystemClock.sleep(200)
            }
            if (ime) shot("keyboard-typing")
        }
        // No soft keyboard, so the key panel and its tabs show
        instr.runOnMainSync {
            val field = activity.findViewById<View>(R.id.etInput)
            field.clearFocus()
            activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
        }
        waitFor(5_000, "Key panel tabs not shown") { displayed(R.id.panelTabs) }
        shot("keyboard")
        for (tab in listOf(R.id.btnTabNumpad, R.id.btnTabNav, R.id.btnTabFKeys)) {
            tap(tab)
            waitFor(3_000, "Tab $tab did not open its panel") {
                var ok = false
                instr.runOnMainSync {
                    ok = activity.findViewById<MaterialButtonToggleGroup>(R.id.panelTabs).checkedButtonId == tab &&
                        (activity.findViewById<android.view.ViewGroup>(R.id.keyPanel).childCount > 0)
                }
                ok
            }
            shot("keyboard-" + ctx.resources.getResourceEntryName(tab).removePrefix("btnTab").lowercase())
        }

        // Copy from PC: the clipboard changes, or the PC clipboard could not be read
        val sentinel = "smoke-sentinel-${SystemClock.uptimeMillis()}"
        setClipboard(sentinel)
        tap(R.id.btnCopyFromPc)
        val deadline = SystemClock.uptimeMillis() + 6_000
        var clip: String? = sentinel
        while (SystemClock.uptimeMillis() < deadline) {
            clip = readClipboard()
            if (clip != sentinel) break
            SystemClock.sleep(200)
        }
        if (clip != null && clip != sentinel && clip.isNotEmpty()) {
            Log.i(TAG, "Copy from PC: copied ${clip.length} chars to the tablet clipboard")
        } else {
            // Unchanged: the app showed "Couldn't read the PC clipboard"; confirm the PC really answers empty
            val latch = CountDownLatch(1)
            var answer: String? = "unset"
            ConnectionManager.client!!.requestPcClipboard { answer = it; latch.countDown() }
            assertTrue("clp get did not answer", latch.await(6, TimeUnit.SECONDS))
            assertTrue("Clipboard unchanged although the PC returned text", answer.isNullOrEmpty())
            Log.i(TAG, "Copy from PC: couldn't read the PC clipboard (message path)")
        }
        tap(R.id.btnBack)
        QrSteps.waitForTrackpad()
    }

    private fun remotesStep() {
        tap(R.id.btnRemotes)
        waitFor(5_000, "Remotes did not open") { displayed(R.id.rvRemotes) }
        shot("remotes")

        // Presentation: open and back, nothing pressed
        tap(R.id.btnPresentation)
        waitFor(5_000, "Presentation did not open") { displayed(R.id.btnNext) }
        shot("presentation")
        tap(R.id.btnBack)
        waitFor(5_000, "Back to remotes") { displayed(R.id.rvRemotes) }

        // App switcher: the 'win list' answer fills the list
        tap(R.id.btnApps)
        waitFor(5_000, "App switcher did not open") { displayed(R.id.rvWindows) || displayed(R.id.tvState) }
        waitFor(6_000, "No window rows from 'win list'") {
            val a = resumed() as? AppsActivity ?: return@waitFor false
            var rows = 0
            var state = ""
            instr.runOnMainSync {
                rows = a.findViewById<RecyclerView>(R.id.rvWindows).adapter?.itemCount ?: 0
                state = a.findViewById<TextView>(R.id.tvState).let { if (it.visibility == View.VISIBLE) it.text.toString() else "" }
            }
            if (rows == 0 && state.isNotEmpty() && state != ctx.getString(R.string.windows_loading)) {
                throw AssertionError("App switcher shows '$state' instead of windows")
            }
            rows > 0
        }
        shot("apps")
        tap(R.id.btnBack)
        waitFor(5_000, "Back to remotes") { displayed(R.id.rvRemotes) }

        // Bookmarks: the list (or its empty message) shows; no row is tapped
        tap(R.id.btnBookmarks)
        waitFor(5_000, "Bookmarks did not show") { displayed(R.id.rvBookmarks) || displayed(R.id.tvEmpty) }
        shot("bookmarks")
        tap(R.id.btnBack)
        waitFor(5_000, "Back to remotes") { displayed(R.id.rvRemotes) }

        // Air mouse: the hold pad, or the no-gyroscope message
        tap(R.id.btnAirMouse)
        waitFor(5_000, "Air mouse did not show") {
            val a = resumed() as? AirMouseActivity ?: return@waitFor false
            var ok = false
            instr.runOnMainSync {
                val hold = a.findViewById<View>(R.id.holdArea)
                val status = a.findViewById<TextView>(R.id.tvAirStatus).text.toString()
                ok = hold.isShown && (hold.isEnabled || status == ctx.getString(R.string.air_mouse_no_gyro))
            }
            ok
        }
        shot("airmouse")
        pressBack()
        waitFor(5_000, "Back to remotes") { displayed(R.id.rvRemotes) }

        // Power panel: shown, then dismissed with Back, nothing chosen
        tap(R.id.btnPower)
        onView(withText(R.string.power_shutdown)).inRoot(isDialog()).check(matches(isDisplayed()))
        shot("power")
        pressBack()
        waitFor(5_000, "Power panel still open") { resumed()?.hasFocusOnMain() == true && displayed(R.id.rvRemotes) }

        tap(R.id.btnBack)
        QrSteps.waitForTrackpad()
    }

    /** Screenshot (with `-e shots true`) and layout check of the focused window */
    private fun shot(screen: String) {
        instr.waitForIdleSync()
        if (shots) {
            // Let any toast ("Connected to …", "Copied …") fade first: a short one lasts 2 s
            SystemClock.sleep(3000)
            val dir = java.io.File(ctx.filesDir, "shots").apply { mkdirs() }
            instr.uiAutomation.takeScreenshot()?.let { bmp ->
                java.io.File(dir, "$orientation-$screen.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        onView(isRoot()).perform(LayoutCheck.collect("$orientation-$screen", layoutReport))
    }

    private fun recentListed(host: String): Boolean {
        val a = QrSteps.currentConnectionActivity() ?: return false
        var ok = false
        instr.runOnMainSync {
            val section = a.findViewById<View>(R.id.recentSection)
            val list = a.findViewById<android.view.ViewGroup>(R.id.recentList)
            ok = section.visibility == View.VISIBLE && (0 until list.childCount).any { i ->
                list.getChildAt(i).findViewById<TextView>(R.id.tvDetail)?.text?.startsWith("$host:") == true
            }
        }
        return ok
    }

    /** Rotates the display and waits until the resumed screen is laid out for it */
    private fun rotate(freeze: Int) {
        instr.uiAutomation.setRotation(freeze)
        waitFor(10_000, "Rotation to $freeze did not finish") {
            val a = resumed() ?: return@waitFor false
            var ok = false
            instr.runOnMainSync {
                @Suppress("DEPRECATION")
                val display = a.windowManager.defaultDisplay
                val size = Point().also { @Suppress("DEPRECATION") display.getRealSize(it) }
                val root = a.window.decorView
                ok = display.rotation == freeze && root.width > 0 &&
                    (root.width > root.height) == (size.x > size.y) && a.hasWindowFocus()
            }
            ok
        }
        instr.waitForIdleSync()
    }

    private fun Activity.hasFocusOnMain(): Boolean {
        var f = false
        instr.runOnMainSync { f = hasWindowFocus() }
        return f
    }

    private fun resumed(): Activity? {
        var found: Activity? = null
        instr.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
        }
        return found
    }

    /** [id] is shown on the resumed screen (it may be scrolled off: landscape screens are short) */
    private fun displayed(id: Int): Boolean {
        val a = resumed() ?: return false
        var shown = false
        instr.runOnMainSync { shown = a.findViewById<View>(id)?.isShown == true }
        return shown
    }

    /** performClick on [id] once it shows: no touch, so nothing can land on another window or the PC */
    private fun tap(id: Int) {
        waitFor(5_000, "View $id not shown") { displayed(id) }
        val a = resumed()!!
        instr.runOnMainSync { a.findViewById<View>(id).performClick() }
        instr.waitForIdleSync()
    }

    private fun readClipboard(): String? {
        var text: String? = null
        instr.runOnMainSync {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        }
        return text
    }

    private fun waitFor(timeoutMs: Long, message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError(message)
            SystemClock.sleep(200)
        }
    }

    private companion object {
        const val TAG = "SmokeTest"
    }
}
