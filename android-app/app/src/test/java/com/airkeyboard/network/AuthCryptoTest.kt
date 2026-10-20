package com.airkeyboard.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthCryptoTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun range(from: Int, until: Int) = ByteArray(until - from) { (from + it).toByte() }

    @Test
    fun rfc7914Pbkdf2Vector() {
        // RFC 7914 section 11: PBKDF2-HMAC-SHA256, P = "passwd", S = "salt", c = 1, dkLen = 64
        val dk = AuthCrypto.pbkdf2Sha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64)
        assertArrayEquals(
            hex(
                "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                    "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783"
            ),
            dk
        )
        assertArrayEquals(dk.copyOf(32), AuthCrypto.pbkdf2Sha256("passwd".toByteArray(), "salt".toByteArray(), 1))
    }

    /**
     * Pinned once with Python hashlib/hmac (the same derivation the server and stress-test.py use):
     * password "Test-pass-1234", salt 00..0f, 1000 iterations, nonceS 10..1f, nonceC 20..2f,
     * frame = the 44 bytes 00..2b
     */
    @Test
    fun pythonCrossVector() {
        val salt = range(0x00, 0x10)
        val nonceS = range(0x10, 0x20)
        val nonceC = range(0x20, 0x30)
        val k = AuthCrypto.deriveKey("Test-pass-1234".toCharArray(), salt, 1000)
        assertArrayEquals(hex("173deccbe4c07620e7be1a63197d442b2ac7ca253e70ef0d69f7557bb3219829"), k)
        assertArrayEquals(
            hex("d7f657633087356809e2203c6c8bc0d3663d8dfa736c3f121f167318f590be4d"),
            AuthCrypto.proofClient(k, nonceS, nonceC)
        )
        assertArrayEquals(
            hex("ad36fcfe205091e6a5f80a9b43b10acd8d70a1b907ff14d8f060b42160d6e1d7"),
            AuthCrypto.proofServer(k, nonceS, nonceC)
        )
        val sk = AuthCrypto.macKey(k, nonceS, nonceC)
        assertArrayEquals(hex("b30fc19724e9e42ac408895917b88c96ccbed101e4284fe1e2777eb58bc5b337"), sk)

        val frame = range(0x00, 0x2c)
        val mac = AuthCrypto.macFor(sk)
        assertArrayEquals(hex("22e4ae2f373e0dcaa629d63b2a585297"), AuthCrypto.tag16(mac, 'T', frame))
        assertArrayEquals(hex("62e25901e4ea178cfdf4503577962987"), AuthCrypto.tag16(mac, 'U', frame))
        // The Mac is reused for every frame of a session: the same input gives the same tag
        assertArrayEquals(hex("22e4ae2f373e0dcaa629d63b2a585297"), AuthCrypto.tag16(mac, 'T', frame))
    }

    @Test
    fun nfcNormalisation() {
        val salt = range(0x00, 0x10)
        val decomposed = AuthCrypto.deriveKey("Wörld-pass".toCharArray(), salt, 1000)
        val composed = AuthCrypto.deriveKey("Wörld-pass".toCharArray(), salt, 1000)
        assertArrayEquals(composed, decomposed)
        assertArrayEquals(hex("15accd8e37f6d1122540b6d634bd9b15870ed618e719d5ea7a104c29f1325125"), composed)
        assertArrayEquals("Wörld".toByteArray(Charsets.UTF_8), AuthCrypto.passwordBytes("Wörld".toCharArray()))
    }

    @Test
    fun base64RoundTrip() {
        assertEquals("AAECAwQFBgcICQoLDA0ODw==", AuthCrypto.toBase64(range(0x00, 0x10)))
        assertArrayEquals(range(0x10, 0x20), AuthCrypto.fromBase64("EBESExQVFhcYGRobHB0eHw=="))
        assertNull(AuthCrypto.fromBase64("not base64!"))
    }

    @Test
    fun constantTimeEqual() {
        assertTrue(AuthCrypto.equal(range(0, 16), range(0, 16)))
        assertFalse(AuthCrypto.equal(range(0, 16), range(1, 17)))
        assertFalse(AuthCrypto.equal(range(0, 16), range(0, 15)))
    }
}
