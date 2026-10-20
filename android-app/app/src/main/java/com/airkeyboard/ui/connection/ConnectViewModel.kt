package com.airkeyboard.ui.connection

import android.os.SystemClock
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airkeyboard.network.LockedOutException
import com.airkeyboard.network.PasswordRequiredException
import com.airkeyboard.network.AirKeyboardClient
import com.airkeyboard.network.ServerNotAuthenticException
import com.airkeyboard.network.UnsupportedServerException
import com.airkeyboard.network.WrongPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How a connect attempt (or a whole reconnect) ended; consumed once by the connection screen */
sealed class ConnectEvent {
    /** Connected; the screen attaches [client] (it is closed here if nobody takes it) */
    class Success(val client: AirKeyboardClient, val host: String, val port: Int) : ConnectEvent()

    class Failure(val error: Throwable?, val host: String, val port: Int) : ConnectEvent()

    /** The reconnect loop ran out of time without connecting */
    class GaveUp(val host: String, val port: Int) : ConnectEvent()
}

/**
 * Runs the connection screen's connect attempts, so one in progress survives a rotation (the
 * Activity is recreated, this is not). One attempt at a time: a new one cancels the previous one,
 * including a reconnect loop.
 */
class ConnectViewModel : ViewModel() {
    companion object {
        const val RECONNECT_INTERVAL_MS = 2_000L
        const val RECONNECT_LIMIT_MS = 30_000L
    }

    private val _event = MutableLiveData<ConnectEvent?>()
    /** The last result not yet handled by the screen, else null (see [consumed]) */
    val event: LiveData<ConnectEvent?> = _event

    private val _busy = MutableLiveData(false)
    /** A single attempt the user started is running (buttons disabled meanwhile) */
    val busy: LiveData<Boolean> = _busy

    private val _reconnecting = MutableLiveData<String?>()
    /** The host being reconnected to while the reconnect loop runs, else null */
    val reconnecting: LiveData<String?> = _reconnecting

    private var job: Job? = null

    /** One attempt; [password] (zeroed afterwards) and [remember] as in [AirKeyboardClient.connect] */
    fun connect(host: String, port: Int, password: CharArray? = null, remember: Boolean = false) {
        cancel()
        _busy.value = true
        job = viewModelScope.launch {
            try {
                val client = AirKeyboardClient(host, port)
                val result = attempt(client, password, remember)
                _event.value = if (result.isSuccess) {
                    ConnectEvent.Success(client, host, port)
                } else {
                    client.close()
                    ConnectEvent.Failure(result.exceptionOrNull(), host, port)
                }
            } finally {
                password?.fill('\u0000')
                // Cancelled: cancel() already reset it, and a newer attempt may own it now
                if (isActive) _busy.value = false
            }
        }
    }

    /**
     * After a lost connection: try [host] every 2 s for up to 30 s with the saved password only.
     * A password prompt (or another login answer) ends it as a [ConnectEvent.Failure] at once,
     * so the dialog shows once instead of every 2 s.
     */
    fun reconnect(host: String, port: Int) {
        cancel()
        _reconnecting.value = host
        job = viewModelScope.launch {
            try {
                val deadline = SystemClock.elapsedRealtime() + RECONNECT_LIMIT_MS
                while (true) {
                    val client = AirKeyboardClient(host, port)
                    val result = attempt(client, null, false)
                    if (result.isSuccess) {
                        _event.value = ConnectEvent.Success(client, host, port)
                        return@launch
                    }
                    client.close()
                    val error = result.exceptionOrNull()
                    if (isLoginAnswer(error)) {
                        _event.value = ConnectEvent.Failure(error, host, port)
                        return@launch
                    }
                    if (SystemClock.elapsedRealtime() + RECONNECT_INTERVAL_MS > deadline) {
                        _event.value = ConnectEvent.GaveUp(host, port)
                        return@launch
                    }
                    delay(RECONNECT_INTERVAL_MS)
                }
            } finally {
                if (isActive) _reconnecting.value = null
            }
        }
    }

    /** Stop the attempt or reconnect loop in progress, if any (a user action takes over) */
    fun cancel() {
        job?.cancel()
        job = null
        _busy.value = false
        _reconnecting.value = null
    }

    /** The screen handled [event] */
    fun consumed() {
        _event.value = null
    }

    /**
     * [AirKeyboardClient.connect], but a client that finishes connecting after the attempt was
     * cancelled (a newer attempt, or the screen gone for good) is closed instead of leaking:
     * connect() runs on to the end, then the cancellation arrives here.
     */
    private suspend fun attempt(client: AirKeyboardClient, password: CharArray?, remember: Boolean): Result<String> =
        try {
            client.connect(password, remember)
        } catch (e: CancellationException) {
            client.close()
            throw e
        }

    /** The PC answered the login (it is up): retrying with the same saved key changes nothing */
    private fun isLoginAnswer(error: Throwable?): Boolean =
        error is PasswordRequiredException || error is WrongPasswordException || error is LockedOutException ||
            error is ServerNotAuthenticException || error is UnsupportedServerException

    override fun onCleared() {
        // viewModelScope is already cancelled (attempts close their own client); a connected
        // client the screen never took is closed here
        (_event.value as? ConnectEvent.Success)?.client?.close()
        super.onCleared()
    }
}
