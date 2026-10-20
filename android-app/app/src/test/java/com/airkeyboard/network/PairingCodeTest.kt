package com.airkeyboard.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PairingCodeTest {

    private fun target(host: String, port: Int = 1978) = PairingCode.Target(host, port)

    @Test
    fun accepts() {
        val cases = mapOf(
            "192.168.0.100" to target("192.168.0.100"),
            " 192.168.0.100\r\n" to target("192.168.0.100"),
            "192.168.0.100:1978" to target("192.168.0.100"),
            "192.168.0.100:5000" to target("192.168.0.100", 5000),
            "1.0.0.0" to target("1.0.0.0"),
            "223.255.255.255" to target("223.255.255.255"),
            "10.0.0.5:65535" to target("10.0.0.5", 65535),
            "10.0.0.5:1" to target("10.0.0.5", 1),
        )
        for ((raw, expected) in cases) assertEquals(raw, expected, PairingCode.parse(raw))
    }

    @Test
    fun rejects() {
        val cases = listOf(
            "", "   ", "hello",
            "https://example.com", "http://192.168.0.100:1978", "airkeyboard://192.168.0.100",
            "my-pc:1978", "localhost",
            "192.168.0", "1.2.3.4.5", "192.168.0.256", "192.168.0.01", "192.168..1", "192.168.0.1.",
            "192.168.0.100:", ":1978",
            "192.168.0.100:0", "192.168.0.100:65536", "192.168.0.100:-1", "192.168.0.100:+1978",
            "192.168.0.100:19a8", "192.168.0.100:001978", "192.168.0.100:1978:secret",
            "127.0.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255",
            "[::1]:1978", "fe80::1", "::1",
            "١٩٢.١٦٨.٠.١", // Arabic-Indic digits
            "１９２.１６８.０.１", // fullwidth digits
            "192.168.0.100:١٩٧٨",
            "192.168.0.100\u0000", "192.168.0.100:1978\u0000",
            "192.168.0.100?x",
            "1".repeat(257),
            "192.168.0.100:1978" + " ".repeat(240) + "x",
        )
        for (raw in cases) assertNull(raw, PairingCode.parse(raw))
    }

    @Test
    fun overlongInputRejectedEvenIfValidPrefix() {
        assertNull(PairingCode.parse("192.168.0.100:1978?" + "a".repeat(256)))
    }

    private val strictIpv4 = Regex("([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})")

    @Test
    fun fuzz() {
        val alphabet = "0123456789.:/-?#=&,abcdeomrsuMRT \n\t\u0000١"
        val rnd = Random(20261016)
        repeat(20_000) {
            val s = buildString { repeat(rnd.nextInt(0, 41)) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            val t = PairingCode.parse(s) ?: return@repeat
            val m = strictIpv4.matchEntire(t.host)
            assertTrue("host from '$s': ${t.host}", m != null)
            val octets = m!!.groupValues.drop(1)
            assertTrue("octets from '$s'", octets.all { it.toInt() <= 255 && (it.length == 1 || it[0] != '0') })
            val first = octets[0].toInt()
            assertTrue("first octet from '$s'", first in 1..223 && first != 127)
            assertTrue("port from '$s': ${t.port}", t.port in 1..65535)
        }
        // A fuzzed hit cannot be guaranteed, so prove the checks run on at least one valid shape
        assertEquals(target("1.2.3.4", 5), PairingCode.parse("1.2.3.4:5"))
    }
}
