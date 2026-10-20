package com.airkeyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.CompoundButton
import android.widget.EditText
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.platform.app.InstrumentationRegistry

/** Sets a switch or checkbox (e.g. switchLiveTyping, cbRemember) to [checked], firing its listener */
fun setChecked(checked: Boolean) = object : ViewAction {
    override fun getConstraints() = isDisplayed()
    override fun getDescription() = "set checked to $checked"
    override fun perform(uc: UiController, view: View) {
        (view as CompoundButton).isChecked = checked
        uc.loopMainThreadUntilIdle()
    }
}

/**
 * Clicks [view] through performClick, not a touch at its screen position. For the password
 * dialog's buttons: the dialog asks for the soft keyboard and moves when it shows, so a tap
 * computed just before can land beside the button and do nothing.
 */
fun clickWithoutTouch() = object : ViewAction {
    override fun getConstraints() = isDisplayed()
    override fun getDescription() = "performClick"
    override fun perform(uc: UiController, view: View) {
        view.performClick()
        uc.loopMainThreadUntilIdle()
    }
}

/** Focuses the field without showing the soft keyboard, so ime() calls are the only input */
fun noSoftInput() = object : ViewAction {
    override fun getConstraints() = isDisplayed()
    override fun getDescription() = "focus without soft input"
    override fun perform(uc: UiController, view: View) {
        (view as EditText).showSoftInputOnFocus = false
        view.requestFocus()
        uc.loopMainThreadUntilIdle()
    }
}

/**
 * Runs [block] on a fresh InputConnection of the field, like a keyboard would, then lets the
 * posted live-typing diff run. Fresh per call because a field reset restarts the input.
 */
fun ime(block: InputConnection.() -> Unit) = object : ViewAction {
    override fun getConstraints() = isDisplayed()
    override fun getDescription() = "IME input"
    override fun perform(uc: UiController, view: View) {
        val ic = view.onCreateInputConnection(EditorInfo())
            ?: throw AssertionError("View has no InputConnection")
        ic.block()
        uc.loopMainThreadUntilIdle()
    }
}

fun setClipboard(text: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
        val cm = instrumentation.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("test", text))
    }
}
