package com.airkeyboard.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Locale
import kotlin.concurrent.thread

/** connect() against a fake PC on localhost that sends one handshake */
class AirKeyboardClientConnectTest {

    private fun connectTo(handshake: String): Result<String> =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val pc = thread {
                server.accept().use { s ->
                    val body = handshake.toByteArray(Charsets.UTF_8)
                    s.getOutputStream().write(
                        "SIN".toByteArray(Charsets.US_ASCII) +
                            String.format(Locale.ROOT, "%03d", body.size).toByteArray(Charsets.US_ASCII) + body
                    )
                    // Stay connected until the client sends something or closes
                    s.getInputStream().read()
                }
            }
            val client = AirKeyboardClient("127.0.0.1", server.localPort)
            try {
                runBlocking { client.connect() }
            } finally {
                client.close()
                pc.join(5_000)
            }
        }

    @Test
    fun refusesServersThatAreNotAirKeyboard() {
        for (handshake in listOf("Office PC nopwd - 530", "Windows pwd secret 530", "Office_PC pwd @rmauth2 530")) {
            val error = connectTo(handshake).exceptionOrNull()
            assertTrue("$handshake: $error", error is UnsupportedServerException)
        }
    }

    @Test
    fun connectsToAirKeyboard() {
        val result = connectTo("Office_PC nopwd @rm1 530")
        assertTrue("${result.exceptionOrNull()}", result.isSuccess)
    }
}
