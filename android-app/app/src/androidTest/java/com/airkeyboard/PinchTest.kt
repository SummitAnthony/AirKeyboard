package com.airkeyboard

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airkeyboard.ui.connection.ConnectionActivity
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pinch out then in on the trackpad: the PC should get `mos z` lines adding up to +5 then -5
 * (check the server log). The fingers' midpoint stays still, so it must not scroll or click.
 * `-e qr <PC LAN IP>` (required). Run with a capture window in front on the PC.
 */
@RunWith(AndroidJUnit4::class)
class PinchTest {

    @Test
    fun pinchZooms() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val qr = qrArg()
        ActivityScenario.launch<ConnectionActivity>(
            Intent(instr.targetContext, ConnectionActivity::class.java).putExtra(ConnectionActivity.EXTRA_AUTO_DISCOVER, false)
        )
        QrSteps.scan(qr)
        QrSteps.waitConnected(15_000)
        QrSteps.waitForTrackpad()
        onView(withId(R.id.trackpadView)).perform(pinch(100f, 500f))   // 400 px apart = 5 notches in
        SystemClock.sleep(500)
        onView(withId(R.id.trackpadView)).perform(pinch(500f, 100f))   // 5 notches out
        SystemClock.sleep(1000)
    }

    /** Two fingers on a horizontal line through the centre, their gap going from [from] to [to] px */
    private fun pinch(from: Float, to: Float) = object : ViewAction {
        override fun getConstraints() = isDisplayed()
        override fun getDescription() = "pinch $from -> $to"
        override fun perform(uc: UiController, view: View) {
            val cx = view.width / 2f
            val cy = view.height / 2f
            val down = SystemClock.uptimeMillis()
            fun event(action: Int, count: Int, gap: Float): MotionEvent {
                val props = Array(count) { i ->
                    MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER }
                }
                val coords = Array(count) { i ->
                    MotionEvent.PointerCoords().apply { x = cx + (if (i == 0) -gap else gap) / 2; y = cy; pressure = 1f; size = 1f }
                }
                return MotionEvent.obtain(
                    down, SystemClock.uptimeMillis(), action, count, props, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
                )
            }
            val second = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
            view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 1, from))
            view.dispatchTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN or second, 2, from))
            val steps = 20
            for (s in 1..steps) {
                uc.loopMainThreadForAtLeast(16)
                view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 2, from + (to - from) * s / steps))
            }
            view.dispatchTouchEvent(event(MotionEvent.ACTION_POINTER_UP or second, 2, to))
            view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 1, to))
        }
    }
}
