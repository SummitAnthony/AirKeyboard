package com.airkeyboard.ui.common

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.airkeyboard.R

/**
 * A hold-to-talk button (see [PushToTalk]): listens while held, a quick tap only explains that.
 * Asks for the microphone the first time. Create it as a property of [activity] (it registers a
 * permission request, which must happen before the activity starts), then [attach] the view.
 * [onListening] changes the button's look; [onPartial] and [onPhrase] are [PushToTalk]'s.
 */
class VoiceButton(
    private val activity: AppCompatActivity,
    private val onListening: (Boolean) -> Unit,
    onPartial: (String) -> Unit,
    onPhrase: (String) -> Unit
) {
    private val talk = PushToTalk(activity, onPartial, onPhrase) {
        activity.glassToast(activity.getString(R.string.voice_error, it))
    }
    private var downAt = 0L

    private val micPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        activity.glassToast(if (granted) R.string.voice_hold_hint else R.string.voice_mic_denied)
    }

    init {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) = release()
            override fun onDestroy(owner: LifecycleOwner) = talk.destroy()
        })
    }

    @SuppressLint("ClickableViewAccessibility") // performClick runs on release
    fun attach(view: View) = view.setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> press(v, event.eventTime)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (downAt != 0L) {
                if (event.eventTime - downAt < 400) {
                    activity.glassToast(R.string.voice_hold_hint)
                }
                release()
                v.isPressed = false
                if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            }
        }
        true
    }

    private fun press(v: View, time: Long) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!talk.available) {
            activity.glassToast(R.string.voice_unavailable)
            return
        }
        downAt = time
        v.isPressed = true
        onListening(true)
        talk.start()
    }

    private fun release() {
        if (downAt == 0L) return
        downAt = 0L
        talk.stop()
        onListening(false)
    }
}
