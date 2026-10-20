package com.airkeyboard.network

import java.nio.CharBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Crypto for the "rmauth1" password login (HMAC-SHA256 and PBKDF2 only).
 *
 * K = PBKDF2-HMAC-SHA256(UTF-8(NFC(password)), salt, iterations, 32); with n = nonceS || nonceC,
 * proofC = HMAC(K, "rmauth1 client" || n), proofS = HMAC(K, "rmauth1 server" || n) and
 * SKmac = HMAC(K, "rmauth1 mac" || n). Every frame after login carries
 * tag = HMAC(SKmac, T || frame)[0..16), T = 'T' on TCP, 'U' on UDP.
 * Pure JVM code, so it runs in local unit tests.
 */
object AuthCrypto {
    const val LABEL_CLIENT = "rmauth1 client"
    const val LABEL_SERVER = "rmauth1 server"
    const val LABEL_MAC = "rmauth1 mac"

    const val KEY_SIZE = 32
    const val TAG_SIZE = 16
    const val SALT_SIZE = 16
    const val NONCE_SIZE = 16

    /** The server uses 100k; a fake server must not make guessing cheaper, or freeze the app */
    const val MIN_ITERATIONS = 100_000
    const val MAX_ITERATIONS = 1_000_000

    private const val HMAC = "HmacSHA256"
    private val random = SecureRandom()

    /** PBKDF2-HMAC-SHA256 over Mac: minSdk 24 has no PBKDF2WithHmacSHA256 SecretKeyFactory */
    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int = KEY_SIZE): ByteArray {
        require(password.isNotEmpty() && iterations > 0 && length > 0)
        val mac = macFor(password)
        val out = ByteArray(length)
        val u = ByteArray(mac.macLength)
        val t = ByteArray(mac.macLength)
        var block = 1
        var offset = 0
        while (offset < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            mac.doFinal(u, 0)
            u.copyInto(t)
            for (i in 1 until iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val n = minOf(t.size, length - offset)
            t.copyInto(out, offset, 0, n)
            offset += n
            block++
        }
        u.fill(0)
        t.fill(0)
        return out
    }

    /** UTF-8 of the NFC form, so "ö" typed as one or two code points gives the same key */
    fun passwordBytes(password: CharArray): ByteArray =
        Normalizer.normalize(CharBuffer.wrap(password), Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val pw = passwordBytes(password)
        try {
            return pbkdf2Sha256(pw, salt, iterations)
        } finally {
            pw.fill(0)
        }
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = macFor(key)
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    fun proofClient(key: ByteArray, nonceS: ByteArray, nonceC: ByteArray): ByteArray =
        hmac(key, LABEL_CLIENT.toByteArray(Charsets.US_ASCII), nonceS, nonceC)

    fun proofServer(key: ByteArray, nonceS: ByteArray, nonceC: ByteArray): ByteArray =
        hmac(key, LABEL_SERVER.toByteArray(Charsets.US_ASCII), nonceS, nonceC)

    fun macKey(key: ByteArray, nonceS: ByteArray, nonceC: ByteArray): ByteArray =
        hmac(key, LABEL_MAC.toByteArray(Charsets.US_ASCII), nonceS, nonceC)

    /** An HMAC-SHA256 Mac keyed with [key]; not thread-safe */
    fun macFor(key: ByteArray): Mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }

    /** The 16-byte tag of one frame; [mac] is keyed with SKmac, [transport] is 'T' or 'U' */
    fun tag16(mac: Mac, transport: Char, frame: ByteArray): ByteArray {
        mac.update(transport.code.toByte())
        mac.update(frame)
        return mac.doFinal().copyOf(TAG_SIZE)
    }

    /** Constant-time comparison */
    fun equal(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    /** Standard base64 with padding (what the server sends) */
    @OptIn(ExperimentalEncodingApi::class)
    fun toBase64(bytes: ByteArray): String = Base64.encode(bytes)

    /** Null if [text] is not valid base64 */
    @OptIn(ExperimentalEncodingApi::class)
    fun fromBase64(text: String): ByteArray? =
        try {
            Base64.decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }
}
