package com.airkeyboard.network

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Our server's replies to "sys" requests, on the TCP session after the handshake: plain messages
 * of a 3-char marker, a 3-digit byte length and the body (see [ChunkBuilder]).
 */
object ServerReplies {
    const val WINDOW = "wnl"
    const val WINDOWS_END = "wne"
    const val CLIPBOARD_BEGIN = "cbb"
    const val CLIPBOARD_DATA = "cbd"
    const val CLIPBOARD_END = "cbe"
    /** The reply to the heartbeat's "sys png", empty body */
    const val PONG = "png"

    /** The server sends at most 1 MiB of clipboard text; more is not a valid reply */
    const val CLIPBOARD_MAX_BYTES = 1024 * 1024

    class Message(val marker: String, val body: ByteArray)

    /** Bytes that are not a message: the stream can't be read as messages from here on */
    class MalformedException : Exception("Not a server message")

    /**
     * The next message, or null if the stream ends between messages. Throws [EOFException] if it
     * ends inside one, [MalformedException] if the length field is not 3 digits.
     */
    fun read(input: InputStream): Message? {
        val head = ByteArray(6)
        if (!readFully(input, head, allowEnd = true)) return null
        val length = String(head, 3, 3, Charsets.US_ASCII)
        if (!length.all { it in '0'..'9' }) throw MalformedException()
        val body = ByteArray(length.toInt())
        readFully(input, body, allowEnd = false)
        return Message(String(head, 0, 3, Charsets.US_ASCII), body)
    }

    /** False if the stream ended before the first byte and [allowEnd]; else fills [buffer] */
    private fun readFully(input: InputStream, buffer: ByteArray, allowEnd: Boolean): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) {
                if (read == 0 && allowEnd) return false
                throw EOFException("Server closed the connection inside a message")
            }
            read += n
        }
        return true
    }

    /** A "wnl" body "<id>\t<title>"; null unless the id is a decimal number */
    fun parseWindow(body: ByteArray): PcWindow? {
        val text = String(body, Charsets.UTF_8)
        val tab = text.indexOf('\t')
        if (tab <= 0) return null
        val id = text.substring(0, tab)
        if (!id.all { it in '0'..'9' }) return null
        return PcWindow(id, text.substring(tab + 1))
    }

    /** Collects one clipboard reply: "cbb", "cbd" slices, "cbe" */
    class ClipboardReply {
        private var buffer: ByteArrayOutputStream? = null

        fun begin() {
            buffer = ByteArrayOutputStream()
        }

        /** A slice outside a reply, or past the size limit (the reply then fails), is dropped */
        fun data(body: ByteArray) {
            val b = buffer ?: return
            if (b.size() + body.size > CLIPBOARD_MAX_BYTES) {
                buffer = null
                return
            }
            b.write(body)
        }

        /** The text; null if the reply failed ("toolarge" or another body), had no text, or never began */
        fun end(body: ByteArray): String? {
            val b = buffer
            buffer = null
            if (b == null || body.isNotEmpty() || b.size() == 0) return null
            return String(b.toByteArray(), Charsets.UTF_8)
        }
    }

    /** Collects one window list: "wnl" per window, "wne" */
    class WindowsReply {
        private val windows = mutableListOf<PcWindow>()

        /** Unparsable entries are dropped, and any past 100 (the server sends at most 50) */
        fun add(body: ByteArray) {
            if (windows.size < 100) parseWindow(body)?.let { windows += it }
        }

        fun end(): List<PcWindow> = windows.toList().also { windows.clear() }
    }
}
