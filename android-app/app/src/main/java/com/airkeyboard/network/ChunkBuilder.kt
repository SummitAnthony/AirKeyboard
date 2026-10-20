package com.airkeyboard.network

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Builder for creating properly formatted command payloads
 * Wraps commands with "key", "mos", "clp", "sys" prefixes and proper length encoding.
 *
 * "sys" (over the TCP session): "pwr shutdown|restart|logoff|sleep|lock",
 * "url <http(s) URL>", "win list", "win focus <id>", "win close <id>", "clp get", "png" (heartbeat
 * ping, see [Heartbeat]; answered with an empty "png"). The server
 * replies on the TCP session with plain messages (3-char marker + 3-digit byte length + body,
 * untagged even with a password): "wnl" "<id>\t<title>" per window, then "wne"; "cbb", "cbd"
 * slices of its clipboard text, then "cbe" (body "toolarge" over 1 MiB). See [ServerReplies].
 */
object ChunkBuilder {
    private const val TAG = "ChunkBuilder"

    /**
     * Build mouse command payload
     * Format: "mos" + 3-digit length (ASCII) + command
     */
    fun buildMouseCommand(command: String): ByteArray = frame("mos", command)

    /**
     * Build keyboard command payload
     * Format: "key" + 3-digit length (ASCII) + key command
     */
    fun buildKeyboardCommand(keyCommand: String): ByteArray = frame("key", keyCommand)

    /** The length field counts UTF-8 bytes, which is what the server reads */
    private fun frame(type: String, command: String): ByteArray = frame(type, command.toByteArray(Charsets.UTF_8))

    private fun frame(type: String, body: ByteArray): ByteArray {
        require(body.size <= 999) { "Command too long: ${body.size} bytes" }
        return type.toByteArray(Charsets.US_ASCII) + formatLength(body.size).toByteArray(Charsets.US_ASCII) + body
    }

    /** Most text bytes one "clp" data frame carries ("d" + bytes must fit in 999) */
    const val CLIPBOARD_SLICE_MAX = 998

    /** Clipboard paste (sent over TCP): "b" starts a transfer, "d" + bytes appends, "e" pastes */
    fun clipboardBegin(): ByteArray = frame("clp", byteArrayOf('b'.code.toByte()))

    fun clipboardData(bytes: ByteArray): ByteArray = frame("clp", byteArrayOf('d'.code.toByte()) + bytes)

    fun clipboardEnd(): ByteArray = frame("clp", byteArrayOf('e'.code.toByte()))

    /** A PC system command, e.g. "pwr lock" or "win list" (see the class doc); at most 999 bytes */
    fun system(command: String): ByteArray = frame("sys", command)

    /** Whether [system] takes [command] (it fits one frame) instead of throwing */
    fun fitsSystem(command: String): Boolean = command.toByteArray(Charsets.UTF_8).size <= 999

    /**
     * Split [text]'s UTF-8 bytes into slices of at most [maxBytes] (>= 4), cutting only at
     * code-point boundaries, so no multi-byte sequence (or surrogate pair) is split
     */
    fun splitUtf8(text: String, maxBytes: Int = CLIPBOARD_SLICE_MAX): List<ByteArray> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val slices = mutableListOf<ByteArray>()
        var start = 0
        while (start < bytes.size) {
            var end = minOf(start + maxBytes, bytes.size)
            // Back off while the next byte is a continuation byte (10xxxxxx)
            while (end < bytes.size && (bytes[end].toInt() and 0xC0) == 0x80) end--
            slices.add(bytes.copyOfRange(start, end))
            start = end
        }
        return slices
    }

    /**
     * Build mouse move command
     * Sends relative coordinates to trackpad
     */
    fun mouseMove(dx: Int, dy: Int): ByteArray {
        // Format: "m <dx> <dy>"
        val command = "m $dx $dy"
        return buildMouseCommand(command)
    }

    /**
     * Build mouse click command
     * button: 1 = left, 2 = right, 3 = middle
     */
    fun mouseClick(button: Int = 1): ByteArray {
        val cmd = "c $button"
        return buildMouseCommand(cmd)
    }

    /**
     * Build mouse double click command
     */
    fun mouseDoubleClick(button: Int = 1): ByteArray {
        val cmd = "d $button"
        return buildMouseCommand(cmd)
    }

    /**
     * Build mouse button down / up commands (for drag)
     */
    fun mouseDown(button: Int = 1): ByteArray = buildMouseCommand("p $button")

    fun mouseUp(button: Int = 1): ByteArray = buildMouseCommand("r $button")

    /**
     * Build mouse scroll command
     * dy: positive for up, negative for down
     */
    fun mouseScroll(dy: Int): ByteArray {
        val cmd = "s $dy"
        return buildMouseCommand(cmd)
    }

    /** Zoom (Ctrl + wheel on the PC); positive zooms in */
    fun mouseZoom(notches: Int): ByteArray = buildMouseCommand("z $notches")

    /**
     * Build keyboard key press
     * A key name ("ENTER", "F5", "A") or a "+"-joined combo ("CTRL+SHIFT+T")
     */
    fun keyPress(key: String): ByteArray {
        val cmd = "[$key]"
        return buildKeyboardCommand(cmd)
    }

    /**
     * Build keyboard text input
     * Sends raw text to be typed
     */
    fun keyType(text: String): ByteArray {
        val cmd = "t$text"
        return buildKeyboardCommand(cmd)
    }

    /**
     * Format length as 3-digit ASCII string, right-padded with spaces
     * Example: 5 -> "005", 42 -> "042", 256 -> "256"
     */
    private fun formatLength(length: Int): String {
        // Locale.ROOT: some locales format %d with non-ASCII digits
        return String.format(Locale.ROOT, "%03d", length)
    }

    /**
     * Parse server handshake response
     * Format: "SIN" + 3-digit length + "<PC name> <nopwd|pwd> <@rm1|@rmauth1> <version>"
     * Returns: Pair of (serverInfo, version)
     */
    fun parseHandshake(data: ByteArray): Pair<String, Int>? {
        if (data.size < 6) return null
        try {
            // Verify "SIN" marker
            if (String(data, 0, 3, Charsets.UTF_8) != "SIN") return null

            // Parse 3-digit length
            val length = parseLength(String(data, 3, 3, Charsets.UTF_8).trim()) ?: return null
            if (data.size < 6 + length) return null

            val handshakeData = String(data, 6, length, Charsets.UTF_8)
            val fields = handshakeFields(handshakeData) ?: return null
            return Pair(handshakeData, fields.version)
        } catch (_: Exception) {
            return null
        }
    }

    /** The handshake's fields; the PC name may contain spaces */
    class HandshakeFields(val name: String, val needPwd: String, val pwdField: String, val version: Int)

    /**
     * Parsed from the right, so a name with spaces ("Office PC nopwd @rm1 530") or containing
     * "pwd" still works: name, "nopwd"/"pwd", the password field, version
     */
    private val HANDSHAKE_TAIL = Regex("""^(.*) (nopwd|pwd) (\S*) (\d+)$""")

    fun handshakeFields(handshakeData: String): HandshakeFields? {
        val m = HANDSHAKE_TAIL.matchEntire(handshakeData) ?: return null
        val (name, needPwd, pwdField, version) = m.destructured
        return HandshakeFields(name, needPwd, pwdField, version.toIntOrNull() ?: 0)
    }

    /** A 3-digit length field, already trimmed: digits only (rejects "-01", "+05") */
    private fun parseLength(field: String): Int? =
        if (field.isNotEmpty() && field.all { it in '0'..'9' }) field.toInt() else null

    /**
     * Check if server requires password
     */
    fun serverNeedsPassword(handshakeData: String): Boolean =
        handshakeFields(handshakeData)?.needPwd == "pwd"

    /**
     * Extract server version from handshake
     */
    fun extractServerVersion(handshakeData: String): Int = handshakeFields(handshakeData)?.version ?: 0

    /** The PC name from the handshake (for prompts); the first word if it does not parse */
    fun serverName(handshakeData: String): String =
        handshakeFields(handshakeData)?.name ?: handshakeData.substringBefore(' ')

    /** Our server with a password: "<name> pwd @rmauth1 530", followed by a "chl" message */
    fun isAuthServer(handshakeData: String): Boolean =
        handshakeFields(handshakeData)?.let { it.needPwd == "pwd" && it.pwdField == "@rmauth1" } == true

    /**
     * AirKeyboard.exe marks the password field with "@" ("@rm1" without a password, "@rmauth1"
     * with one); the app connects to nothing else
     */
    fun isOurServer(handshakeData: String): Boolean =
        handshakeFields(handshakeData)?.pwdField?.startsWith("@") == true

    /** A validated "chl" challenge from a password-protected server */
    class Challenge(val salt: ByteArray, val iterations: Int, val nonceS: ByteArray)

    /**
     * Parse a "chl" body: "1 <salt b64> <iterations> <nonceS b64>". Null unless the version is 1,
     * salt and nonce are 16 bytes and iterations are within the accepted range (a fake server
     * must not make guessing cheaper with few iterations, or freeze the app with many)
     */
    fun parseChallenge(body: String): Challenge? {
        val parts = body.split(" ")
        if (parts.size != 4 || parts[0] != "1") return null
        val salt = AuthCrypto.fromBase64(parts[1]) ?: return null
        val iterations = parts[2].takeIf { it.length <= 9 }?.let { parseLength(it) } ?: return null
        val nonceS = AuthCrypto.fromBase64(parts[3]) ?: return null
        if (salt.size != AuthCrypto.SALT_SIZE || nonceS.size != AuthCrypto.NONCE_SIZE) return null
        if (iterations !in AuthCrypto.MIN_ITERATIONS..AuthCrypto.MAX_ITERATIONS) return null
        return Challenge(salt, iterations, nonceS)
    }

    /** The login message: "ath" + length + "1 <nonceC b64> <proofC b64>" */
    fun authPayload(nonceC: ByteArray, proofC: ByteArray): ByteArray =
        frame("ath", "1 ${AuthCrypto.toBase64(nonceC)} ${AuthCrypto.toBase64(proofC)}")

    /**
     * Parse discovery response, returning the PC name
     * Format: "BC" + 3-digit length + PC name
     * Example: "BC" + "009" + "WindowsPC"
     */
    fun parseDiscoveryResponse(data: ByteArray): String? {
        if (data.size < 5) {
            Log.e(TAG, "Discovery response too short: ${data.size}")
            return null
        }

        try {
            // Verify "BC" marker
            val marker = String(data.sliceArray(0..1), Charsets.UTF_8)
            if (marker != "BC") {
                Log.e(TAG, "Invalid discovery marker: $marker")
                return null
            }

            // Parse 3-digit length
            val lengthStr = String(data.sliceArray(2..4), Charsets.UTF_8).trim()
            val length = parseLength(lengthStr)
                ?: run {
                    Log.e(TAG, "Invalid discovery length: $lengthStr")
                    return null
                }

            if (data.size < 5 + length) {
                Log.e(TAG, "Discovery data too short")
                return null
            }

            val serverName = String(data.sliceArray(5 until (5 + length)), Charsets.UTF_8)
            Log.d(TAG, "Parsed discovery: name=$serverName")
            return serverName
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing discovery response: ${e.message}", e)
            return null
        }
    }
}
