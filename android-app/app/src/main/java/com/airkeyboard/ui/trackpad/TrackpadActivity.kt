package com.airkeyboard.ui.trackpad

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Outline
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import androidx.appcompat.app.AppCompatActivity
import com.airkeyboard.R
import com.airkeyboard.databinding.ActivityTrackpadBinding
import com.airkeyboard.network.ConnectionManager
import com.airkeyboard.network.AirKeyboardClient
import com.airkeyboard.ui.common.AppPrefs
import com.airkeyboard.ui.common.ConnectionGuard
import com.airkeyboard.ui.common.RemoteKeys
import com.airkeyboard.ui.common.VoiceButton
import com.airkeyboard.ui.common.glassToast
import com.airkeyboard.ui.connection.ConnectionActivity
import com.airkeyboard.ui.keyboard.KeyboardActivity
import com.airkeyboard.ui.remotes.RemotesActivity
import com.airkeyboard.ui.settings.SettingsActivity
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Main trackpad control screen.
 * One finger moves the cursor, tap = left click, two-finger tap = right click,
 * two-finger drag = scroll. Hold still, then move = drag (left button held until the finger lifts).
 * Three fingers moving = drag too, if enabled; lifting a finger ends it. Left-handed swaps left and right.
 */
class TrackpadActivity : AppCompatActivity() {
    companion object {
        private const val TAP_TIMEOUT_MS = 200
        private const val TAP_SLOP_PX = 12f
        private const val SCROLL_STEP_PX = 40f
        /** The finger gap must change this much (and more than the fingers moved) to count as a pinch */
        private const val PINCH_SLOP_PX = 48f
        /** Change of the finger gap per zoom notch */
        private const val ZOOM_STEP_PX = 80f
        private const val DRAG_KEEPALIVE_MS = 1000L

        // Acceleration: gain = sensitivity * (MIN + SLOPE * finger speed in dp/ms), capped at MAX
        private const val ACCEL_MIN = 0.75f
        private const val ACCEL_SLOPE = 0.75f
        private const val ACCEL_MAX = 2.5f
    }

    private enum class DragState { IDLE, PENDING, ARMED, DRAGGING }

    /** Two fingers: undecided until they move together (scroll) or apart/together (pinch zoom) */
    private enum class TwoFinger { UNDECIDED, SCROLL, PINCH }

    private lateinit var binding: ActivityTrackpadBinding

    /** Hold the mic at the end of the click row to dictate: each phrase is typed on the PC; the pad shows what was heard */
    private val voice = VoiceButton(this,
        onListening = { listening ->
            showMic(listening)
            if (!listening) binding.tvPadHint.setText(R.string.trackpad_hint)
        },
        onPartial = { heard -> if (heard.isNotEmpty()) binding.tvPadHint.text = heard },
        onPhrase = { phrase ->
            val c = client
            if (c?.isConnectedStatus() == true) c.sendKeyType("$phrase ", reliable = true)
            else glassToast(R.string.not_connected)
        })
    private var micAnimator: ValueAnimator? = null
    private var client: AirKeyboardClient? = null
    private var host: String = ""
    private var port: Int = AirKeyboardClient.DEFAULT_PORT
    private var sensitivity = 1.5f
    private var acceleration = false
    private var naturalScroll = false
    private var scrollStep = SCROLL_STEP_PX
    private var leftHanded = false
    private var threeFingerDrag = false

    // Touch tracking
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var travel = 0f
    private var maxPointers = 0
    private var remainderX = 0f
    private var remainderY = 0f
    private var scrollAccum = 0f
    private var twoFinger = TwoFinger.UNDECIDED
    private var startSpread = 0f
    private var lastSpread = 0f
    private var twoFingerTravel = 0f
    private var zoomAccum = 0f
    private var pinched = false
    private var lastMoveTime = 0L

    // Drag: PENDING after a touch, ARMED once held still for the long-press timeout, DRAGGING after the first move
    private var dragState = DragState.IDLE
    private var dragClient: AirKeyboardClient? = null
    private var armTravel = 0f

    // Three fingers came down: this touch is a three-finger drag (pressed once they move), never a scroll
    private var threeFingerTouch = false

    private val armRunnable = Runnable {
        if (dragState == DragState.PENDING && client?.isConnectedStatus() == true) {
            dragState = DragState.ARMED
            armTravel = 0f
            remainderX = 0f
            remainderY = 0f
            binding.trackpadView.isActivated = true
            binding.trackpadView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /** Keeps the server's held button alive while the finger rests during a drag */
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            val c = dragClient
            if (dragState != DragState.DRAGGING || c?.isConnectedStatus() != true) {
                endDrag()
                return
            }
            c.sendMouseMove(0, 0)
            binding.trackpadView.postDelayed(this, DRAG_KEEPALIVE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrackpadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        host = intent.getStringExtra("host") ?: ""
        port = intent.getIntExtra("port", AirKeyboardClient.DEFAULT_PORT)

        // A connection lost later is routed to the connection screen centrally (App)
        client = ConnectionManager.client
        if (client?.isConnectedStatus() != true) {
            ConnectionGuard.returnToConnection(this, getString(R.string.not_connected))
            return
        }

        setupUI()
    }

    override fun onResume() {
        super.onResume()
        // Lost while in the background (Android may not have let App open the connection screen)
        client = ConnectionManager.client
        if (client?.isConnectedStatus() != true) {
            ConnectionGuard.returnToConnection(this, getString(R.string.disconnected_from_server))
            return
        }
        sensitivity = SettingsActivity.pointerMultiplier(this)
        acceleration = AppPrefs.acceleration(this)
        naturalScroll = AppPrefs.naturalScroll(this)
        scrollStep = SCROLL_STEP_PX * 100f / AppPrefs.scrollSpeed(this).coerceIn(25f, 300f)
        leftHanded = AppPrefs.leftHanded(this)
        threeFingerDrag = AppPrefs.threeFingerDrag(this)
        // Ids stay put; in left-handed mode each one sends, and is labelled as, the other button
        binding.btnLeftClick.setText(if (leftHanded) R.string.right_click else R.string.left_click)
        binding.btnRightClick.setText(if (leftHanded) R.string.left_click else R.string.right_click)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (RemoteKeys.onKeyDown(this, keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        endDrag()
    }

    override fun onDestroy() {
        // Leaving the app with Back ends the session; a rotation or theme change keeps it. Only
        // this screen's client: a newer one may already be attached by a reconnect.
        if (isFinishing && !isChangingConfigurations) {
            val c = client
            if (c != null && ConnectionManager.client === c) ConnectionManager.disconnect()
        }
        super.onDestroy()
    }

    private fun setupUI() {
        binding.apply {
            tvConnectionStatus.text = "$host:$port"

            trackpadView.setOnTouchListener { _, event -> handleTrackpadTouch(event) }
            // Round clip so the touch glow never shows past the pad's corners
            padFrame.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, resources.getDimension(R.dimen.radius_trackpad))
            }
            padFrame.clipToOutline = true
            showMic(false)
            voice.attach(btnVoicePad)

            btnLeftClick.setOnClickListener { client?.sendMouseClick(button(1)) }
            btnRightClick.setOnClickListener { client?.sendMouseClick(button(2)) }
            btnMiddleClick.setOnClickListener { client?.sendMouseClick(3) }

            // Phones held upright: five labels don't fit across, so these show only their icons
            // (sizes in dimens_pad); each keeps its label as the content description
            if (!resources.getBoolean(R.bool.pad_nav_labels)) {
                for (b in listOf(btnScrollUp, btnScrollDown, btnKeyboard, btnRemotes, btnSettings)) {
                    b.contentDescription = b.text
                    b.text = ""
                }
            }

            btnScrollUp.setOnClickListener { client?.sendMouseScroll(3) }
            btnScrollDown.setOnClickListener { client?.sendMouseScroll(-3) }

            btnKeyboard.setOnClickListener { navigateTo(KeyboardActivity::class.java) }
            btnRemotes.setOnClickListener { navigateTo(RemotesActivity::class.java) }
            btnSettings.setOnClickListener { navigateTo(SettingsActivity::class.java) }

            btnDisconnect.setOnClickListener {
                endDrag()
                ConnectionManager.disconnect()
                // A manual disconnect stays disconnected: no auto-discovery on the connection screen
                returnToConnectionScreen()
            }
        }
    }

    private fun handleTrackpadTouch(event: MotionEvent): Boolean {
        val (x, y) = centroid(event)
        showGlow(event.actionMasked, x, y)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                endDrag()
                dragState = DragState.PENDING
                binding.trackpadView.postDelayed(armRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                downTime = event.eventTime
                travel = 0f
                maxPointers = 1
                remainderX = 0f
                remainderY = 0f
                scrollAccum = 0f
                pinched = false
                lastMoveTime = event.eventTime
                threeFingerTouch = false
                lastX = x
                lastY = y
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // A second finger before the drag starts cancels it; during a drag extra fingers are ignored
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && dragState != DragState.DRAGGING) endDrag()
                maxPointers = maxOf(maxPointers, event.pointerCount)
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount == 3 &&
                    threeFingerDrag && dragState != DragState.DRAGGING
                ) {
                    threeFingerTouch = true
                    armTravel = 0f
                    remainderX = 0f
                    remainderY = 0f
                } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && threeFingerTouch) {
                    endDrag()   // lifting a finger ends a three-finger drag; the rest of this touch does nothing
                }
                lastMoveTime = event.eventTime
                // The centroid jumps when a finger is added or lifted; restart tracking from here
                val lifting = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
                val (nx, ny) = centroid(event, excludeIndex = lifting)
                lastX = nx
                lastY = ny
                // A finger more or less starts the two-finger decision over
                twoFinger = TwoFinger.UNDECIDED
                startSpread = spread(event, lifting)
                lastSpread = startSpread
                twoFingerTravel = 0f
                scrollAccum = 0f
                zoomAccum = 0f
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y
                val dist = hypot(dx, dy)
                travel += dist
                val gain = pointerGain(dist, event.eventTime - lastMoveTime)
                lastMoveTime = event.eventTime
                if (dragState == DragState.PENDING && travel > TAP_SLOP_PX) endDrag()

                if (threeFingerTouch && dragState != DragState.DRAGGING) {
                    // Like ARMED: hold the cursor still until three fingers really move, then press and catch up
                    if (event.pointerCount >= 3) {
                        armTravel += dist
                        remainderX += dx * gain
                        remainderY += dy * gain
                        if (armTravel >= TAP_SLOP_PX) {
                            binding.trackpadView.isActivated = true
                            startDrag()
                        }
                    }
                } else if (dragState == DragState.ARMED) {
                    // Hold the cursor still until the finger really moves, then press and catch up
                    armTravel += dist
                    remainderX += dx * gain
                    remainderY += dy * gain
                    if (armTravel >= TAP_SLOP_PX) startDrag()
                } else if (event.pointerCount >= 2 && dragState != DragState.DRAGGING) {
                    val spread = spread(event)
                    if (twoFinger == TwoFinger.UNDECIDED) {
                        twoFingerTravel += dist
                        val change = abs(spread - startSpread)
                        if (change > PINCH_SLOP_PX && change > twoFingerTravel) {
                            twoFinger = TwoFinger.PINCH
                            pinched = true
                            zoomAccum = spread - startSpread
                        } else if (twoFingerTravel > TAP_SLOP_PX * 2) {
                            twoFinger = TwoFinger.SCROLL
                        }
                    } else if (twoFinger == TwoFinger.PINCH) {
                        zoomAccum += spread - lastSpread
                    }
                    lastSpread = spread
                    if (twoFinger == TwoFinger.PINCH) {
                        // Fingers apart = zoom in (Ctrl + wheel up)
                        val notches = (zoomAccum / ZOOM_STEP_PX).toInt()
                        if (notches != 0) {
                            client?.sendMouseZoom(notches)
                            zoomAccum -= notches * ZOOM_STEP_PX
                        }
                    } else {
                        // Fingers down = wheel up, so the page follows the fingers; a wheel scrolls the other way.
                        // Collected while undecided too, so the scroll catches up once it is decided
                        scrollAccum += if (naturalScroll) dy else -dy
                        val notches = (scrollAccum / scrollStep).toInt()
                        if (notches != 0 && twoFinger == TwoFinger.SCROLL) {
                            client?.sendMouseScroll(notches)
                            scrollAccum -= notches * scrollStep
                        }
                    }
                } else {
                    remainderX += dx * gain
                    remainderY += dy * gain
                    val sendX = remainderX.toInt()
                    val sendY = remainderY.toInt()
                    if (sendX != 0 || sendY != 0) {
                        client?.sendMouseMove(sendX, sendY)
                        remainderX -= sendX
                        remainderY -= sendY
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                val wasDrag = dragState == DragState.ARMED || dragState == DragState.DRAGGING
                endDrag()
                val isTap = event.eventTime - downTime < TAP_TIMEOUT_MS && travel < TAP_SLOP_PX
                if (isTap && !wasDrag && !pinched) {
                    client?.sendMouseClick(button(if (maxPointers >= 2) 2 else 1))
                }
            }

            MotionEvent.ACTION_CANCEL -> endDrag()
        }
        return true
    }

    /** Touch glow: follows the fingers while down, fades out on up. Look only; the touch itself is unchanged */
    private fun showGlow(action: Int, x: Float, y: Float) {
        val glow = binding.padGlow
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                glow.translationX = x - glow.width / 2f
                glow.translationY = y - glow.height / 2f
                if (action == MotionEvent.ACTION_DOWN) glow.animate().alpha(1f).setDuration(100).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> glow.animate().alpha(0f).setDuration(250).start()
        }
    }

    /** Push-to-talk look: red (bg_mic follows activated), white "Listening" and wider while held, plus the pill on the pad */
    private fun showMic(listening: Boolean) {
        val mic = binding.btnVoicePad
        mic.isActivated = listening
        // Phones held upright have no room for "Listening": the mic stays icon-only (dimens_trackpad)
        val labelled = (listening && resources.getBoolean(R.bool.pad_mic_listening_label)) ||
            resources.getBoolean(R.bool.trackpad_mic_label)
        mic.text = if (labelled) getString(if (listening) R.string.trackpad_mic_listening else R.string.trackpad_mic_idle) else ""
        mic.iconPadding = if (labelled) (8 * resources.displayMetrics.density).toInt() else 0
        val color = getColor(if (listening) R.color.text_primary else R.color.mic_idle_text)
        mic.setTextColor(color)
        mic.iconTint = ColorStateList.valueOf(color)
        binding.tvListeningPill.visibility = if (listening) View.VISIBLE else View.GONE
        // Widen over 200ms (before the first layout the XML width is already right)
        if (!mic.isLaidOut) return
        micAnimator?.cancel()
        val to = resources.getDimensionPixelSize(if (listening) R.dimen.trackpad_mic_width_listening else R.dimen.trackpad_mic_width)
        micAnimator = ValueAnimator.ofInt(mic.width, to).apply {
            duration = 200
            addUpdateListener { a -> mic.layoutParams = mic.layoutParams.apply { width = a.animatedValue as Int } }
            start()
        }
    }

    /** ARMED -> DRAGGING: press the left button, then send the movement held back while armed */
    private fun startDrag() {
        val c = client
        if (c == null) {
            endDrag()
            return
        }
        dragClient = c
        c.sendMouseDown(1)
        val sendX = remainderX.toInt()
        val sendY = remainderY.toInt()
        if (sendX != 0 || sendY != 0) {
            c.sendMouseMove(sendX, sendY)
            remainderX -= sendX
            remainderY -= sendY
        }
        dragState = DragState.DRAGGING
        binding.trackpadView.postDelayed(keepAliveRunnable, DRAG_KEEPALIVE_MS)
    }

    /** End any drag or pending long-press; safe from any state. Releases the button only if it was pressed. */
    private fun endDrag() {
        binding.trackpadView.removeCallbacks(armRunnable)
        binding.trackpadView.removeCallbacks(keepAliveRunnable)
        if (dragState == DragState.DRAGGING) dragClient?.sendMouseRelease(1)
        binding.trackpadView.isActivated = false
        dragState = DragState.IDLE
        dragClient = null
    }

    /** Left (1) and right (2) swapped in left-handed mode; middle (3) unchanged */
    private fun button(b: Int) = if (leftHanded && b != 3) 3 - b else b

    /** Cursor px per finger px: the sensitivity, scaled up with finger speed when acceleration is on */
    private fun pointerGain(distPx: Float, dtMs: Long): Float {
        if (!acceleration) return sensitivity
        val speed = distPx / dtMs.coerceAtLeast(1L) / resources.displayMetrics.density
        return sensitivity * (ACCEL_MIN + ACCEL_SLOPE * speed).coerceAtMost(ACCEL_MAX)
    }

    /** Distance between the first two fingers on the pad, ignoring one that is lifting; 0 with fewer */
    private fun spread(event: MotionEvent, excludeIndex: Int = -1): Float {
        val idx = (0 until event.pointerCount).filter { it != excludeIndex }
        if (idx.size < 2) return 0f
        return hypot(event.getX(idx[0]) - event.getX(idx[1]), event.getY(idx[0]) - event.getY(idx[1]))
    }

    /** Average position of the fingers on the pad, optionally ignoring one that is lifting */
    private fun centroid(event: MotionEvent, excludeIndex: Int = -1): Pair<Float, Float> {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == excludeIndex) continue
            sx += event.getX(i)
            sy += event.getY(i)
            n++
        }
        return if (n == 0) Pair(event.x, event.y) else Pair(sx / n, sy / n)
    }

    private fun <T : AppCompatActivity> navigateTo(activityClass: Class<T>) {
        startActivity(Intent(this, activityClass).apply {
            putExtra("host", host)
            putExtra("port", port)
        })
    }

    private fun returnToConnectionScreen() {
        if (isFinishing) return
        startActivity(Intent(this, ConnectionActivity::class.java)
            .putExtra(ConnectionActivity.EXTRA_AUTO_DISCOVER, false)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
