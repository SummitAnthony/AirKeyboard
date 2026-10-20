package com.airkeyboard.ui.airmouse

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.appcompat.app.AppCompatActivity
import com.airkeyboard.R
import com.airkeyboard.databinding.ActivityAirMouseBinding
import com.airkeyboard.network.ConnectionManager
import com.airkeyboard.ui.common.RemoteKeys
import com.airkeyboard.ui.common.glassToast
import com.airkeyboard.ui.settings.SettingsActivity
import kotlin.math.abs

/**
 * Gyro air mouse: while the pad is held, turning the tablet moves the cursor; let go to reposition
 * the tablet without moving it. Tap Left/Right to click; long-press Left holds the button for a drag.
 */
class AirMouseActivity : AppCompatActivity(), SensorEventListener {
    companion object {
        /** Cursor pixels per radian turned, before the pointer-speed multiplier */
        private const val GAIN_PX_PER_RAD = 1000f
        /** Slower rates (rad/s, about 1.7 °/s) are hand tremor or drift and are ignored */
        private const val DEAD_ZONE = 0.03f
        /** A longer gap between samples is not integrated as one jump */
        private const val MAX_DT_S = 0.1f
        private const val DRAG_KEEPALIVE_MS = 1000L

        /**
         * Gyro rates (device axes, rad/s, counter-clockwise positive) to cursor rates (right, down).
         * The device axes are first turned into display axes (x right, y up as shown now; z out of
         * the screen). Turning right is clockwise about the vertical, which is the display's y axis
         * when the tablet is held upright and z when it lies flat; the sum covers every grip in
         * between. Tilting the top edge up is counter-clockwise about x.
         */
        fun cursorRates(x: Float, y: Float, z: Float, rotation: Int): Pair<Float, Float> {
            val (sx, sy) = when (rotation) {
                Surface.ROTATION_90 -> Pair(-y, x)
                Surface.ROTATION_180 -> Pair(-x, -y)
                Surface.ROTATION_270 -> Pair(y, -x)
                else -> Pair(x, y)
            }
            return Pair(-(sy + z), -sx)
        }
    }

    private lateinit var binding: ActivityAirMouseBinding
    private val client get() = ConnectionManager.client
    private lateinit var sensorManager: SensorManager
    private var gyro: Sensor? = null
    private var multiplier = 1.5f

    // Held pad: display rotation taken at touch-down, last sample time, sub-pixel movement not sent yet
    private var holding = false
    private var rotation = Surface.ROTATION_0
    private var lastTimestamp = 0L
    private var remainderX = 0f
    private var remainderY = 0f

    private var leftHeld = false

    /** Keeps the server's held button alive while the tablet rests during a drag */
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            if (!leftHeld) return
            client?.sendMouseMove(0, 0)
            binding.btnAirLeft.postDelayed(this, DRAG_KEEPALIVE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAirMouseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setTitle(R.string.air_mouse)

        val c = client
        if (c?.isConnectedStatus() != true) {
            glassToast(R.string.not_connected)
            finish()
            return
        }
        binding.tvConnectionStatus.text = "${c.host}:${c.port}"

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        setupUI()
    }

    override fun onResume() {
        super.onResume()
        // A lost connection is routed to the connection screen centrally (App); this one just closes
        if (client?.isConnectedStatus() != true) {
            finish()
            return
        }
        multiplier = SettingsActivity.pointerMultiplier(this)
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(this)
        stopHolding()
        releaseLeft()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (RemoteKeys.onKeyDown(this, keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        binding.apply {
            btnBack.setOnClickListener { finish() }

            if (gyro == null) {
                tvAirStatus.setText(R.string.air_mouse_no_gyro)
                // Shown as the orange warning banner
                tvAirStatus.setBackgroundResource(R.drawable.ic_airmouse_warning_banner)
                tvAirStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_airmouse_warning_dot, 0, 0, 0)
                tvAirStatus.setTextColor(getColor(R.color.text_primary))
                holdArea.isEnabled = false
                holdArea.alpha = 0.4f
            } else {
                holdArea.setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> startHolding()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopHolding()
                    }
                    true
                }
            }

            btnAirLeft.setOnClickListener { client?.sendMouseClick(1) }
            // Long-press presses the button; it stays down until that finger lifts
            btnAirLeft.setOnLongClickListener {
                pressLeft()
                true
            }
            btnAirLeft.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) releaseLeft()
                false
            }
            btnAirRight.setOnClickListener { client?.sendMouseClick(2) }
        }
    }

    private fun startHolding() {
        holding = true
        rotation = displayRotation()
        lastTimestamp = 0L
        remainderX = 0f
        remainderY = 0f
        binding.holdArea.isActivated = true
    }

    private fun stopHolding() {
        holding = false
        binding.holdArea.isActivated = false
    }

    private fun pressLeft() {
        val c = client ?: return
        c.sendMouseDown(1)
        leftHeld = true
        binding.btnAirLeft.postDelayed(keepAliveRunnable, DRAG_KEEPALIVE_MS)
    }

    /** Releases the left button only if a long-press pressed it; safe to call any time */
    private fun releaseLeft() {
        binding.btnAirLeft.removeCallbacks(keepAliveRunnable)
        if (leftHeld) client?.sendMouseRelease(1)
        leftHeld = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!holding) return
        val c = client ?: return
        if (lastTimestamp == 0L) {
            lastTimestamp = event.timestamp
            return
        }
        val dt = ((event.timestamp - lastTimestamp) / 1e9f).coerceIn(0f, MAX_DT_S)
        lastTimestamp = event.timestamp

        val (right, down) = cursorRates(event.values[0], event.values[1], event.values[2], rotation)
        val scale = GAIN_PX_PER_RAD * multiplier * dt
        if (abs(right) > DEAD_ZONE) remainderX += right * scale
        if (abs(down) > DEAD_ZONE) remainderY += down * scale
        val sendX = remainderX.toInt()
        val sendY = remainderY.toInt()
        if (sendX != 0 || sendY != 0) {
            c.sendMouseMove(sendX, sendY)
            remainderX -= sendX
            remainderY -= sendY
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation ?: Surface.ROTATION_0
        else windowManager.defaultDisplay.rotation
}
