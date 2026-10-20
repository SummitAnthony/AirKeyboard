package com.airkeyboard.network

/**
 * Parses the text of a scanned pairing QR code. Pure Kotlin (no android.*), unit-tested on the JVM.
 *
 * Accepted: "a.b.c.d" (port 1978) and "a.b.c.d:port". The host must be a strict IPv4 literal, so
 * a QR code never triggers a DNS lookup. The QR never carries a password.
 */
object PairingCode {
    data class Target(val host: String, val port: Int)

    private const val DEFAULT_PORT = 1978
    private const val MAX_LENGTH = 256

    /** The connection target, or null if [raw] is not a pairing code */
    fun parse(raw: String): Target? {
        val s = raw.trim()
        if (s.isEmpty() || s.length > MAX_LENGTH) return null

        val parts = s.split(':')
        val port = when (parts.size) {
            1 -> DEFAULT_PORT
            2 -> parsePort(parts[1]) ?: return null
            else -> return null
        }
        val host = parts[0]
        return if (isIpv4Literal(host)) Target(host, port) else null
    }

    /** ASCII digits only (Char.isDigit would accept other scripts' digits), 1..65535 */
    private fun parsePort(s: String): Int? {
        if (s.length !in 1..5 || !s.all { it in '0'..'9' }) return null
        return s.toInt().takeIf { it in 1..65535 }
    }

    /** Dotted IPv4, no leading zeros, first octet 1..223 and not 127 */
    private fun isIpv4Literal(s: String): Boolean {
        val octets = s.split('.')
        if (octets.size != 4) return false
        for (o in octets) {
            if (o.length !in 1..3 || !o.all { it in '0'..'9' }) return false
            if (o.length > 1 && o[0] == '0') return false
            if (o.toInt() > 255) return false
        }
        val first = octets[0].toInt()
        return first in 1..223 && first != 127
    }
}
