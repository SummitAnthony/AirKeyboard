package com.airkeyboard.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Handshake and challenge parsing (AirKeyboard.exe sends "@rm1" or "@rmauth1") */
class ChunkBuilderTest {

    private fun sin(body: String): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return "SIN".toByteArray(Charsets.US_ASCII) +
            String.format(Locale.ROOT, "%03d", bytes.size).toByteArray(Charsets.US_ASCII) + bytes
    }

    /** The handshake text as connect() sees it */
    private fun info(body: String) = ChunkBuilder.parseHandshake(sin(body))!!.first

    private fun range(from: Int, until: Int) = ByteArray(until - from) { (from + it).toByte() }

    // ---- Handshake ----

    @Test
    fun ourNopwdServer() {
        val i = info("DESKTOP-ABC1234 nopwd @rm1 530")
        assertEquals(530, ChunkBuilder.extractServerVersion(i))
        assertFalse(ChunkBuilder.serverNeedsPassword(i))
        assertFalse(ChunkBuilder.isAuthServer(i))
        assertTrue(ChunkBuilder.isOurServer(i))
        assertEquals("DESKTOP-ABC1234", ChunkBuilder.serverName(i))
    }

    @Test
    fun ourPasswordServer() {
        val i = info("DESKTOP-ABC1234 pwd @rmauth1 530")
        assertEquals(530, ChunkBuilder.extractServerVersion(i))
        assertTrue(ChunkBuilder.serverNeedsPassword(i))
        assertTrue(ChunkBuilder.isAuthServer(i))
        assertTrue(ChunkBuilder.isOurServer(i))
    }

    @Test
    fun namesWithSpaces() {
        val nopwd = info("Living Room PC nopwd @rm1 530")
        assertEquals("Living Room PC", ChunkBuilder.serverName(nopwd))
        assertEquals(530, ChunkBuilder.extractServerVersion(nopwd))
        assertFalse(ChunkBuilder.serverNeedsPassword(nopwd))
        assertTrue(ChunkBuilder.isOurServer(nopwd))

        val pwd = info("Living Room PC pwd @rmauth1 530")
        assertEquals("Living Room PC", ChunkBuilder.serverName(pwd))
        assertEquals(530, ChunkBuilder.extractServerVersion(pwd))
        assertTrue(ChunkBuilder.isAuthServer(pwd))
    }

    @Test
    fun namesContainingPwdWords() {
        val nopwd = info("Bob pwd nopwd @rm1 530")
        assertEquals("Bob pwd", ChunkBuilder.serverName(nopwd))
        assertFalse(ChunkBuilder.serverNeedsPassword(nopwd))
        assertTrue(ChunkBuilder.isOurServer(nopwd))

        val pwd = info("my nopwd PC pwd @rmauth1 530")
        assertEquals("my nopwd PC", ChunkBuilder.serverName(pwd))
        assertTrue(ChunkBuilder.isAuthServer(pwd))
    }

    @Test
    fun otherNopwdServerIsNotOurs() {
        // connect() refuses it
        val i = info("Windows nopwd - 530")
        assertEquals(530, ChunkBuilder.extractServerVersion(i))
        assertFalse(ChunkBuilder.serverNeedsPassword(i))
        assertFalse(ChunkBuilder.isOurServer(i))
        assertFalse(ChunkBuilder.isOurServer(info("Office PC nopwd - 530")))
    }

    @Test
    fun otherPasswordServerIsNotOurs() {
        // Field 3 is not our login marker: connect() refuses it
        for (body in listOf("Windows pwd secret 530", "Office PC pwd hunter2 530")) {
            val i = info(body)
            assertTrue(body, ChunkBuilder.serverNeedsPassword(i))
            assertFalse(body, ChunkBuilder.isAuthServer(i))
            assertFalse(body, ChunkBuilder.isOurServer(i))
            assertEquals(body, 530, ChunkBuilder.extractServerVersion(i))
        }
        // A look-alike marker is not our scheme either
        assertFalse(ChunkBuilder.isAuthServer(info("Windows pwd @rmauth2 530")))
        assertFalse(ChunkBuilder.isAuthServer(info("Windows nopwd @rmauth1 530")))
    }

    @Test
    fun malformedHandshakes() {
        assertNull(ChunkBuilder.parseHandshake(sin("Windows")))
        assertNull(ChunkBuilder.parseHandshake(sin("Windows nopwd - x530")))
        assertNull(ChunkBuilder.parseHandshake(sin("nopwd - 530")))
        assertNull(ChunkBuilder.parseHandshake(sin("Windows nopwd @rm1 530 EXTRA")))
        assertNull(ChunkBuilder.parseHandshake("SIN".toByteArray()))
        assertNull(ChunkBuilder.parseHandshake("XIN007a b c 1".toByteArray()))
        assertNull(ChunkBuilder.parseHandshake("SIN099Windows nopwd @rm1 530".toByteArray()))
        assertFalse(ChunkBuilder.isOurServer("garbage"))
        assertFalse(ChunkBuilder.isAuthServer("garbage"))
    }

    // ---- Challenge ("chl") ----

    private val salt = "AAECAwQFBgcICQoLDA0ODw==" // 00..0f
    private val nonceS = "EBESExQVFhcYGRobHB0eHw==" // 10..1f

    @Test
    fun validChallenge() {
        val c = ChunkBuilder.parseChallenge("1 $salt 100000 $nonceS")
        assertNotNull(c)
        assertArrayEquals(range(0x00, 0x10), c!!.salt)
        assertEquals(100_000, c.iterations)
        assertArrayEquals(range(0x10, 0x20), c.nonceS)
        assertEquals(1_000_000, ChunkBuilder.parseChallenge("1 $salt 1000000 $nonceS")!!.iterations)
    }

    @Test
    fun challengeIterationsOutOfRange() {
        // A fake server must not make offline guessing cheaper (S1) or freeze the app
        for (iter in listOf("99999", "10000", "1", "0", "1000001", "999999999", "9999999999")) {
            assertNull(iter, ChunkBuilder.parseChallenge("1 $salt $iter $nonceS"))
        }
        for (iter in listOf("-100000", "+100000", "1e5", "100000.0", "", "١٠٠٠٠٠")) {
            assertNull(iter, ChunkBuilder.parseChallenge("1 $salt $iter $nonceS"))
        }
    }

    @Test
    fun challengeWrongVersion() {
        assertNull(ChunkBuilder.parseChallenge("2 $salt 100000 $nonceS"))
        assertNull(ChunkBuilder.parseChallenge("0 $salt 100000 $nonceS"))
        assertNull(ChunkBuilder.parseChallenge("v1 $salt 100000 $nonceS"))
    }

    @Test
    fun challengeSaltAndNonceLengths() {
        val short = "AAECAwQFBgcICQoLDA0O" // 15 bytes
        val long = "EBESExQVFhcYGRobHB0eHyA=" // 17 bytes
        assertNull(ChunkBuilder.parseChallenge("1 $short 100000 $nonceS"))
        assertNull(ChunkBuilder.parseChallenge("1 $long 100000 $nonceS"))
        assertNull(ChunkBuilder.parseChallenge("1 $salt 100000 $short"))
        assertNull(ChunkBuilder.parseChallenge("1 $salt 100000 $long"))
        assertNull(ChunkBuilder.parseChallenge("1 not*base64 100000 $nonceS"))
        assertNull(ChunkBuilder.parseChallenge("1 $salt 100000 not*base64"))
    }

    @Test
    fun challengeFieldCount() {
        assertNull(ChunkBuilder.parseChallenge(""))
        assertNull(ChunkBuilder.parseChallenge("1 $salt 100000"))
        assertNull(ChunkBuilder.parseChallenge("1 $salt 100000 $nonceS extra"))
        assertNull(ChunkBuilder.parseChallenge(" 1 $salt 100000 $nonceS"))
        assertNull(ChunkBuilder.parseChallenge("1  $salt 100000 $nonceS"))
    }

    // ---- Login message ("ath") ----

    @Test
    fun authPayload() {
        val p = ChunkBuilder.authPayload(range(0x20, 0x30), range(0x40, 0x60))
        assertEquals(
            "ath071" + "1 ICEiIyQlJicoKSorLC0uLw== QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8=",
            String(p, Charsets.US_ASCII)
        )
    }

    // ---- System commands ----

    @Test
    fun fitsSystemShortCommand() {
        assertTrue(ChunkBuilder.fitsSystem("url https://a.b"))
    }

    @Test
    fun fitsSystemTooLong() {
        assertFalse(ChunkBuilder.fitsSystem("url " + "a".repeat(2000)))
    }
}
