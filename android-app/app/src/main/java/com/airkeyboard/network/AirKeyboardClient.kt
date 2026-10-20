package com.airkeyboard.network

import android.util.Log
import com.airkeyboard.network.encryption.EncryptionUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac

/**
 * Client for the AirKeyboard PC server.
 *
 * The TCP connection on [port] carries the "SIN" handshake and stays open for the session;
 * the server closing it is how we detect a disconnect (the server is also pinged, see
 * [Heartbeat], so a PC that vanishes without closing it is noticed too). Commands go out as
 * chunk frames over UDP to the same port, or over the TCP session when they must be reliable
 * and in order (live typing, paste, system commands).
 * A password-protected server (rmauth1) gets a login first, then a 16-byte tag on every frame.
 * Any server that isn't AirKeyboard.exe is refused ([UnsupportedServerException]).
 */
class AirKeyboardClient(
    val host: String,
    val port: Int = DEFAULT_PORT
) {
    companion object {
        private const val TAG = "AirKeyboardClient"
        const val DEFAULT_PORT = 1978
        private const val HANDSHAKE_TIMEOUT_MS = 5000

        /** A TCP write stuck this long means the server stopped reading: drop the connection */
        private const val WRITE_TIMEOUT_MS = 10_000L

        /** Trackpad moves are skipped while this many commands still wait to be sent */
        private const val MAX_PENDING_FOR_MOVES = 256

        /**
         * After a paste, other commands follow it over TCP for this long, so none can run before
         * its Ctrl+V (the server may take 5 s to set the clipboard + 1.5 s idle wait + 0.4 s)
         */
        private const val PASTE_ORDER_MS = 8_000L

        /** A lost release leaves the button down: it goes out again this long after the first */
        private val RELEASE_REPEAT_MS = longArrayOf(40, 120)

        /** A clipboard or window list request with no reply this long gets null */
        private const val REPLY_TIMEOUT_MS = 5_000L

        /** Our server is pinged ("sys png") this often; see [Heartbeat] for when it counts as gone */
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
    }

    private var socket: Socket? = null
    private var tcpOut: OutputStream? = null
    private var udpSocket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private val connected = AtomicBoolean(false)
    private val pending = AtomicInteger()
    /** When the TCP write in progress started (currentTimeMillis), 0 if none */
    @Volatile private var writeStartedAt = 0L
    /** Until when commands go over TCP behind the last paste; sender thread only */
    private var pasteUntil = 0L
    private var serverVersion = 0
    var serverInfo: String = ""
        private set
    private var chunkId = 0
    private var lastSendTime = System.currentTimeMillis()
    /** Keyed with this session's SKmac after a password login, else null; sender thread only */
    private var tagMac: Mac? = null

    // One thread keeps commands in order without blocking the UI (and runs delayed copies)
    private val sender = Executors.newSingleThreadScheduledExecutor()

    // The outstanding "sys" request of each kind waiting for its reply (see request)
    private val clipboardRequest = AtomicReference<Request<String>?>()
    private val windowsRequest = AtomicReference<Request<List<PcWindow>>?>()

    /** Pings written and server messages read on our server's session (see [startHeartbeat]) */
    private val heartbeat = Heartbeat()

    /** Runs on a background thread when the connection is lost (not after [close]); set by [ConnectionManager] */
    @Volatile var onDisconnected: (() -> Unit)? = null

    /**
     * Open the TCP connection and perform the handshake.
     *
     * For a password-protected server this also signs in, with K derived from [password] (then
     * kept in [PasswordStore], saved on the tablet too if [remember]) or else a K kept earlier.
     * Without either it fails with [PasswordRequiredException] and holds no connection while the
     * user types. Other failures: [WrongPasswordException], [LockedOutException],
     * [ServerNotAuthenticException], [UnsupportedServerException]. [password] is zeroed.
     */
    suspend fun connect(password: CharArray? = null, remember: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), HANDSHAKE_TIMEOUT_MS)
            s.soTimeout = HANDSHAKE_TIMEOUT_MS
            // Nagle would hold a keystroke typed right after another until the PC's delayed ACK
            s.tcpNoDelay = true
            socket = s
            tcpOut = s.getOutputStream()
            val input = s.getInputStream()
            Log.d(TAG, "TCP connected to $host:$port")

            val handshake = readMessage(input, "SIN")
            val (info, version) = ChunkBuilder.parseHandshake(handshake)
                ?: throw IllegalStateException("Invalid handshake from server")
            serverInfo = info
            serverVersion = version
            val needsPassword = ChunkBuilder.serverNeedsPassword(info)
            Log.d(TAG, "Handshake: version $version${if (needsPassword) ", password" else ""}")

            if (!ChunkBuilder.isOurServer(info)) {
                throw UnsupportedServerException()
            } else if (ChunkBuilder.isAuthServer(info)) {
                signIn(input, ChunkBuilder.serverName(info), password, remember)
            } else if (needsPassword) {
                // An AirKeyboard PC with a newer login than this app knows
                throw UnsupportedServerException()
            }

            serverAddress = s.inetAddress
            udpSocket = DatagramSocket()

            // Handshake done: block indefinitely on reads so the watcher only wakes on close
            s.soTimeout = 0
            connected.set(true)
            startDisconnectWatcher(s.getInputStream())
            startWriteWatchdog(s)
            startHeartbeat(s)

            Result.success("Connected to $host:$port (version $serverVersion)")
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}", e)
            closeSockets()
            Result.failure(e)
        } finally {
            password?.fill('\u0000')
        }
    }

    /**
     * The rmauth1 login after "SIN ... pwd @rmauth1 530": read "chl", send "ath" (chunk id 0,
     * untagged, so tagged frames continue from id 1), check "cin ok <proofS>", key the tag Mac
     */
    private fun signIn(input: InputStream, serverName: String, password: CharArray?, remember: Boolean) {
        val chl = readMessage(input, "chl")
        val challenge = ChunkBuilder.parseChallenge(String(chl, 6, chl.size - 6, Charsets.US_ASCII))
            ?: throw IllegalStateException("Unsupported password scheme")
        val salt = AuthCrypto.toBase64(challenge.salt)
        val key = if (password != null) {
            AuthCrypto.deriveKey(password, challenge.salt, challenge.iterations)
        } else {
            PasswordStore.get(salt) ?: throw PasswordRequiredException(serverName)
        }
        try {
            val nonceC = AuthCrypto.randomBytes(AuthCrypto.NONCE_SIZE)
            val proofC = AuthCrypto.proofClient(key, challenge.nonceS, nonceC)
            // Nothing runs on the sender thread before connected is set, so chunk is safe here
            tcpOut!!.apply {
                write(chunk(ChunkBuilder.authPayload(nonceC, proofC), 'T'))
                flush()
            }
            val reply = try {
                readMessage(input, "cin")
            } catch (e: IOException) {
                throw IOException("Connection lost while signing in")
            }
            val fields = String(reply, 6, reply.size - 6, Charsets.US_ASCII).split(" ")
            when (fields[0]) {
                "ok" -> {
                    val proofS = fields.getOrNull(1)?.let { AuthCrypto.fromBase64(it) }
                    if (proofS == null || !AuthCrypto.equal(proofS, AuthCrypto.proofServer(key, challenge.nonceS, nonceC))) {
                        throw ServerNotAuthenticException()
                    }
                    val macKey = AuthCrypto.macKey(key, challenge.nonceS, nonceC)
                    tagMac = AuthCrypto.macFor(macKey)
                    macKey.fill(0)
                    if (password != null) PasswordStore.put(salt, key, persist = remember)
                }
                "fail" -> if (password == null) {
                    // The kept key no longer works (the PC password changed): ask again
                    PasswordStore.remove(salt)
                    throw PasswordRequiredException(serverName, wrong = true)
                } else {
                    throw WrongPasswordException(serverName)
                }
                "locked" -> throw LockedOutException(fields.getOrNull(1)?.toIntOrNull() ?: 0)
                else -> throw IllegalStateException("Unexpected reply while signing in")
            }
        } finally {
            key.fill(0)
        }
    }

    /** One server message: 3-char [marker], 3-digit length, then exactly that many bytes */
    private fun readMessage(input: InputStream, marker: String): ByteArray {
        val head = readFully(input, 6)
        if (String(head, 0, 3, Charsets.US_ASCII) != marker) throw IllegalStateException("Unexpected message from server")
        val length = String(head, 3, 3, Charsets.US_ASCII).trim()
        if (length.isEmpty() || !length.all { it in '0'..'9' }) throw IllegalStateException("Invalid message from server")
        return head + readFully(input, length.toInt())
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(bytes, read, size - read)
            if (n < 0) throw EOFException("Server closed connection during handshake")
            read += n
        }
        return bytes
    }

    private fun startDisconnectWatcher(input: InputStream) {
        Thread({
            try {
                readReplies(input)
                // Whatever follows bytes that were not a message: read until the connection closes
                val buffer = ByteArray(256)
                while (input.read(buffer) != -1) {
                }
            } catch (_: Exception) {
            }
            if (connected.compareAndSet(true, false)) {
                Log.d(TAG, "Server closed the connection")
                closeSockets()
                onDisconnected?.invoke()
            }
            failRequests()
        }, "ak-tcp-watcher").apply { isDaemon = true }.start()
    }

    /**
     * Our server's replies to "sys" requests (see [ServerReplies]), until the stream ends.
     * Unknown markers are skipped; bytes that are not a message end it early (the caller then
     * just reads until the connection closes).
     */
    private fun readReplies(input: InputStream) {
        val clipboard = ServerReplies.ClipboardReply()
        val windows = ServerReplies.WindowsReply()
        while (true) {
            val message = try {
                ServerReplies.read(input)
            } catch (_: ServerReplies.MalformedException) {
                Log.w(TAG, "Unreadable message from the server, ignoring the rest")
                null
            } ?: return
            heartbeat.onMessage(System.currentTimeMillis(), isPong = message.marker == ServerReplies.PONG)
            when (message.marker) {
                ServerReplies.WINDOW -> windows.add(message.body)
                ServerReplies.WINDOWS_END -> windowsRequest.getAndSet(null)?.complete(windows.end())
                ServerReplies.CLIPBOARD_BEGIN -> clipboard.begin()
                ServerReplies.CLIPBOARD_DATA -> clipboard.data(message.body)
                ServerReplies.CLIPBOARD_END -> clipboardRequest.getAndSet(null)?.complete(clipboard.end(message.body))
            }
        }
    }

    /** A request's callback, run exactly once: with the result, or null */
    private class Request<T>(private val onResult: (T?) -> Unit) {
        private val done = AtomicBoolean(false)

        fun complete(result: T?) {
            if (!done.compareAndSet(false, true)) return
            // A throwing callback must not end the reader (that would look like a disconnect)
            try {
                onResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Reply callback failed: ${e.message}", e)
            }
        }
    }

    /**
     * Send "sys" [command] and wait for its reply in [slot]. One outstanding request per kind:
     * a newer one takes over the slot (the older gets null). Replies carry no request id, so a
     * late reply to the older one answers the newer; it holds the same kind of data.
     */
    private fun <T> request(slot: AtomicReference<Request<T>?>, command: String, onResult: (T?) -> Unit) {
        val request = Request<T>(onResult)
        if (!connected.get()) return request.complete(null)
        slot.getAndSet(request)?.complete(null)
        try {
            sender.schedule(Runnable {
                if (slot.compareAndSet(request, null)) request.complete(null)
            }, REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // Closed meanwhile
            if (slot.compareAndSet(request, null)) request.complete(null)
            return
        }
        send(ChunkBuilder.system(command), reliable = true)
    }

    /** The session ended: outstanding requests get null */
    private fun failRequests() {
        clipboardRequest.getAndSet(null)?.complete(null)
        windowsRequest.getAndSet(null)?.complete(null)
    }

    /**
     * A server that stops reading TCP leaves a write blocked forever, and every later command
     * queued behind it. Close the socket then: that unblocks the write, and the disconnect
     * watcher reports the disconnect.
     */
    private fun startWriteWatchdog(s: Socket) {
        Thread({
            while (connected.get()) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val started = writeStartedAt
                if (started != 0L && System.currentTimeMillis() - started > WRITE_TIMEOUT_MS) {
                    Log.w(TAG, "TCP write stuck for ${WRITE_TIMEOUT_MS / 1000} s, closing the connection")
                    try { s.close() } catch (_: Exception) {}
                    return@Thread
                }
            }
        }, "ak-tcp-write-watchdog").apply { isDaemon = true }.start()
    }

    /**
     * Every 5 s, drop the connection if the PC has gone silent ([Heartbeat]), else ping it. A PC
     * that vanished without closing TCP (power cut, Wi-Fi lost) would otherwise never be noticed.
     * Closing the socket ends the disconnect watcher's read, which reports the
     * disconnect like a server close. Runs on [sender], so a ping is written in order with the
     * other commands and its write time is when it really went out (not when it was queued).
     */
    private fun startHeartbeat(s: Socket) {
        try {
            sender.scheduleWithFixedDelay(Runnable {
                // A throwing periodic task would silently stop the heartbeat
                try {
                    if (!connected.get()) return@Runnable
                    if (heartbeat.isLost(System.currentTimeMillis())) {
                        Log.w(TAG, "No reply from the server for ${Heartbeat.TIMEOUT_MS / 1000} s, closing the connection")
                        try { s.close() } catch (_: Exception) {}
                        return@Runnable
                    }
                    val started = System.currentTimeMillis()
                    sendNow(ChunkBuilder.system("png"), viaTcp = true)
                    heartbeat.onPingWritten(started, System.currentTimeMillis())
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat failed: ${e.message}", e)
                }
                // First ping at once: until its pong arrives the heartbeat is off (an older
                // AirKeyboard.exe may not answer pings), so a PC that hangs in the first seconds
                // would otherwise never be noticed
            }, 0, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // Closed meanwhile
        }
    }

    /** Write to the TCP session under the watchdog; call only on [sender] */
    private fun tcpWrite(out: OutputStream, bytes: ByteArray) {
        writeStartedAt = System.currentTimeMillis()
        try {
            out.write(bytes)
            out.flush()
        } finally {
            writeStartedAt = 0L
        }
    }

    /**
     * Queue one command. [reliable] sends it over the TCP session (in order, never lost), else it
     * goes over UDP; [isMove] marks a droppable move.
     */
    fun send(payload: ByteArray, reliable: Boolean = false, isMove: Boolean = false) =
        sendAll(listOf(payload), reliable, isMove)

    /**
     * Queue the frames of one command (see [send]). They all take the same transport, chosen
     * once: the server keeps no order between its TCP and UDP threads, so a long text split
     * across the end of a paste's TCP window would be typed out of order.
     */
    private fun sendAll(payloads: List<ByteArray>, reliable: Boolean, isMove: Boolean = false) {
        if (!connected.get()) return
        // Moves are the only commands safe to lose; skip them rather than let a stalled queue grow
        if (isMove && pending.get() >= MAX_PENDING_FOR_MOVES) return
        pending.incrementAndGet()
        sender.execute {
            try {
                val viaTcp = viaTcp(reliable, anyOrder = isMove)
                for (payload in payloads) sendNow(payload, viaTcp)
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    /**
     * Whether a command goes over the TCP session; call only on [sender]. [anyOrder] commands
     * (moves, repeated releases) may overtake a paste, so they stay on UDP right after one.
     */
    private fun viaTcp(reliable: Boolean, anyOrder: Boolean): Boolean =
        reliable || (!anyOrder && System.currentTimeMillis() < pasteUntil)

    /**
     * Send one command frame now, over TCP if [viaTcp] (see [viaTcp]); call only on [sender].
     * Does nothing once the sockets are closed.
     */
    private fun sendNow(payload: ByteArray, viaTcp: Boolean) {
        try {
            val udp = udpSocket
            val out = tcpOut
            if (!viaTcp) {
                if (udp != null) {
                    val frame = chunk(payload, 'U')
                    udp.send(DatagramPacket(frame, frame.size, serverAddress, port))
                }
            } else if (out != null) {
                // Reliable and ordered, or right after a paste: same TCP stream as the paste, so it
                // runs after its Ctrl+V
                tcpWrite(out, chunk(payload, 'T'))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}", e)
        }
    }

    /**
     * Encrypt one chunk frame, plus its tag once signed in ([transport] 'T' for TCP, 'U' for
     * UDP); call only on [sender]
     */
    private fun chunk(payload: ByteArray, transport: Char): ByteArray {
        val now = System.currentTimeMillis()
        val interval = (now - lastSendTime) / 1000f
        lastSendTime = now
        val frame = EncryptionUtil.encryptChunk(payload, chunkId++, interval)
        val mac = tagMac ?: return frame
        return frame + AuthCrypto.tag16(mac, transport, frame)
    }

    /**
     * Put [text] on the PC clipboard and paste it there (Ctrl+V).
     * Always goes over the TCP session as "clp" chunk frames: begin, data slices, end.
     */
    fun sendClipboardPaste(text: String) {
        if (!connected.get()) return
        val payloads = listOf(ChunkBuilder.clipboardBegin()) +
            ChunkBuilder.splitUtf8(text).map { ChunkBuilder.clipboardData(it) } +
            ChunkBuilder.clipboardEnd()
        pending.incrementAndGet()
        sender.execute {
            try {
                tcpOut?.let { out ->
                    for (payload in payloads) tcpWrite(out, chunk(payload, 'T'))
                    // Commands sent next follow the paste over TCP for a while (see send)
                    pasteUntil = System.currentTimeMillis() + PASTE_ORDER_MS
                }
            } catch (e: Exception) {
                Log.e(TAG, "Clipboard paste failed: ${e.message}", e)
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    fun sendMouseMove(dx: Int, dy: Int) = send(ChunkBuilder.mouseMove(dx, dy), isMove = true)

    /** button: 1 = left, 2 = right, 3 = middle */
    fun sendMouseClick(button: Int = 1) = send(ChunkBuilder.mouseClick(button))

    fun sendMouseDoubleClick(button: Int = 1) = send(ChunkBuilder.mouseDoubleClick(button))

    fun sendMouseDown(button: Int = 1) = send(ChunkBuilder.mouseDown(button))

    fun sendMouseUp(button: Int = 1) = send(ChunkBuilder.mouseUp(button))

    /**
     * Release a held button (end of a drag). A lost release leaves the button stuck, so on UDP
     * copies follow at 40 and 120 ms; an extra release is harmless. Each copy takes a fresh
     * chunk id when it runs, and does nothing once the sockets are closed.
     */
    fun sendMouseRelease(button: Int = 1) {
        val payload = ChunkBuilder.mouseUp(button)
        send(payload)
        if (!connected.get() || udpSocket == null) return
        for (delay in RELEASE_REPEAT_MS) {
            sender.schedule(Runnable {
                if (udpSocket != null) sendNow(payload, viaTcp = false)
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    /** Positive scrolls up, negative scrolls down, in wheel notches */
    fun sendMouseScroll(notches: Int) = send(ChunkBuilder.mouseScroll(notches))

    /** Positive zooms in, in wheel notches */
    fun sendMouseZoom(notches: Int) = send(ChunkBuilder.mouseZoom(notches))

    /** A key name such as "ENTER", or a combo such as "CTRL+C"; [reliable]: see [send] */
    fun sendKeyPress(key: String, reliable: Boolean = false) = send(ChunkBuilder.keyPress(key), reliable)

    /** Long text is split so each frame stays within the 3-digit length field; [reliable]: see [send] */
    fun sendKeyType(text: String, reliable: Boolean = false) {
        val frames = mutableListOf<ByteArray>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + 200, text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            frames += ChunkBuilder.keyType(text.substring(start, end))
            start = end
        }
        if (frames.isNotEmpty()) sendAll(frames, reliable)
    }

    /**
     * A PC system command, sent as a "sys" frame over the TCP session. [command] is one of:
     * "pwr shutdown" | "pwr restart" | "pwr sleep" | "pwr logoff" | "pwr lock",
     * "url <http(s) URL>" (open in the PC's default browser),
     * "win focus <id>" | "win close <id>" (ids from [requestWindows]).
     * Returns true if queued; false if not connected or too long for one frame
     * ([ChunkBuilder.fitsSystem]).
     */
    fun sendSystem(command: String): Boolean {
        if (!connected.get()) return false
        val payload = try {
            ChunkBuilder.system(command)
        } catch (_: IllegalArgumentException) {
            // Never the command itself: it may be a private URL
            Log.w(TAG, "System command too long, not sent (${command.length} chars)")
            return false
        }
        send(payload, reliable = true)
        return true
    }

    /**
     * Ask the PC for its clipboard text. [onResult] runs once on a background thread with the
     * text, or null if the server can't, has none, or 5 s pass without a reply (at once, on the
     * calling thread, when not connected).
     */
    fun requestPcClipboard(onResult: (String?) -> Unit) = request(clipboardRequest, "clp get", onResult)

    /**
     * Ask the PC for its open app windows. [onResult] runs once on a background thread with the
     * list (front-most first), or null on failure or after 5 s without a reply (at once, on the
     * calling thread, when not connected).
     */
    fun requestWindows(onResult: (List<PcWindow>?) -> Unit) = request(windowsRequest, "win list", onResult)

    fun isConnectedStatus(): Boolean = connected.get()

    fun getServerVersion(): Int = serverVersion

    private fun closeSockets() {
        try { socket?.close() } catch (_: Exception) {}
        udpSocket?.close()
        socket = null
        udpSocket = null
        tcpOut = null
    }

    /** Close the session. Does not invoke [onDisconnected]. */
    fun close() {
        connected.set(false)
        failRequests()
        // Close TCP here too: it unblocks a paste write stuck on the sender thread
        try { socket?.close() } catch (_: Exception) {}
        sender.execute { closeSockets() }
        sender.shutdown()
    }
}
