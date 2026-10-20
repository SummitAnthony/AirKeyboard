package com.airkeyboard

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed

/** How a [longPressDrag] ends: lift the finger, cancel the gesture, or leave the finger down */
enum class End { UP, CANCEL, NONE }

/** Centre of [v] in screen coordinates */
fun center(v: View): FloatArray {
    val loc = IntArray(2)
    v.getLocationOnScreen(loc)
    return floatArrayOf(loc[0] + v.width / 2f, loc[1] + v.height / 2f)
}

/**
 * One finger held still until the trackpad arms a drag (it must turn activated), then moved
 * by [dx] in 20 steps, held still for [holdMs], then ended as [end]. With UP or CANCEL the
 * trackpad must no longer be activated afterwards; with NONE the drag is left running.
 */
fun longPressDrag(dx: Float, end: End, holdMs: Long = 0) = object : ViewAction {
    override fun getConstraints() = isDisplayed()
    override fun getDescription() = "long-press drag dx=$dx end=$end hold=$holdMs"
    override fun perform(uc: UiController, view: View) {
        val cx = view.width / 2f
        val cy = view.height / 2f
        val down = SystemClock.uptimeMillis()
        fun dispatch(action: Int, x: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, cy, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        dispatch(MotionEvent.ACTION_DOWN, cx)
        uc.loopMainThreadForAtLeast(ViewConfiguration.getLongPressTimeout() + 300L)
        if (!view.isActivated) throw AssertionError("Drag did not arm after the long-press timeout")
        for (s in 1..20) {
            dispatch(MotionEvent.ACTION_MOVE, cx + dx * s / 20)
            uc.loopMainThreadForAtLeast(16)
        }
        if (holdMs > 0) uc.loopMainThreadForAtLeast(holdMs)
        if (!view.isActivated) throw AssertionError("Drag ended before the finger was lifted")
        when (end) {
            End.UP -> dispatch(MotionEvent.ACTION_UP, cx + dx)
            End.CANCEL -> dispatch(MotionEvent.ACTION_CANCEL, cx + dx)
            End.NONE -> return
        }
        uc.loopMainThreadForAtLeast(150)
        if (view.isActivated) throw AssertionError("Trackpad still activated after $end")
    }
}

/**
 * One finger held still until the trackpad arms a drag (it must turn activated), then lifted at
 * the same point. The trackpad must no longer be activated, and the PC must see no button at all.
 */
fun longPressLift() = object : ViewAction {
    override fun getConstraints() = isDisplayed()
    override fun getDescription() = "long-press lift without moving"
    override fun perform(uc: UiController, view: View) {
        val cx = view.width / 2f
        val cy = view.height / 2f
        val down = SystemClock.uptimeMillis()
        fun dispatch(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, cx, cy, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        dispatch(MotionEvent.ACTION_DOWN)
        uc.loopMainThreadForAtLeast(ViewConfiguration.getLongPressTimeout() + 300L)
        if (!view.isActivated) throw AssertionError("Drag did not arm after the long-press timeout")
        dispatch(MotionEvent.ACTION_UP)
        uc.loopMainThreadForAtLeast(150)
        if (view.isActivated) throw AssertionError("Trackpad still activated after lifting an armed press")
    }
}

/** Cancels whatever gesture the view still thinks is in progress (e.g. after a NONE drag) */
fun cancelTouch() = object : ViewAction {
    override fun getConstraints() = isDisplayed()
    override fun getDescription() = "cancel touch"
    override fun perform(uc: UiController, view: View) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, view.width / 2f, view.height / 2f, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        view.dispatchTouchEvent(event)
        event.recycle()
        uc.loopMainThreadUntilIdle()
    }
}
