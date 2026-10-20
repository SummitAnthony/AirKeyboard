package com.airkeyboard.ui.common

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Hold-to-talk dictation with the system speech recognizer, without its dialog.
 * The recognizer ends each session at a pause; while [start] is held (until [stop]) it is started
 * again right away, so long dictation keeps going. Each finished phrase goes to [onPhrase], the
 * words heard so far to [onPartial] ("" when listening ends). Main thread only.
 */
class PushToTalk(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onPhrase: (String) -> Unit,
    private val onError: (Int) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var holding = false

    val available get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (holding) return
        holding = true
        listen()
    }

    /** Stop listening; the phrase being spoken still arrives through [onPhrase] */
    fun stop() {
        if (!holding) return
        holding = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.stopListening()
    }

    fun destroy() {
        holding = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Hints only: many recognizers ignore them, hence the restart while held
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L))
    }

    /** One session ended: listen again while the button is held, else report the end */
    private fun ended(delayMs: Long = 0) {
        if (holding) handler.postDelayed({ if (holding) listen() }, delayMs) else onPartial("")
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            best(partialResults)?.let(onPartial)
        }

        override fun onResults(results: Bundle?) {
            best(results)?.takeIf { it.isNotBlank() }?.let(onPhrase)
            ended()
        }

        override fun onError(error: Int) {
            when (error) {
                // Silence, or nothing understood: keep listening while held
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ended()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> ended(delayMs = 300)
                // Stopped by the release before anything was heard
                SpeechRecognizer.ERROR_CLIENT -> if (holding) { holding = false; onError(error) } else onPartial("")
                else -> {
                    holding = false
                    onPartial("")
                    onError(error)
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun best(bundle: Bundle?) =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}
