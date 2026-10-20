package com.airkeyboard.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class HandshakeParseTest {

    private fun frame(marker: String, body: String): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return marker.toByteArray(Charsets.US_ASCII) +
            String.format(Locale.ROOT, "%03d", bytes.size).toByteArray(Charsets.US_ASCII) + bytes
    }

    /** What the Windows server sends: whitespace in the PC name becomes "_", "@rm1" marks our server */
    private fun serverSin(name: String) = frame("SIN", "${name.replace(Regex("\\s"), "_")} nopwd @rm1 530")

    private fun version(data: ByteArray) = ChunkBuilder.parseHandshake(data)!!.second

    private fun needsPassword(data: ByteArray) = ChunkBuilder.serverNeedsPassword(ChunkBuilder.parseHandshake(data)!!.first)

    @Test
    fun plainName() {
        val sin = serverSin("DESKTOP-ABC1234")
        assertEquals(530, version(sin))
        assertFalse(needsPassword(sin))
    }

    @Test
    fun serverNameWithSpaces() {
        val sin = serverSin("Living Room PC")
        assertEquals("Living_Room_PC nopwd @rm1 530", ChunkBuilder.parseHandshake(sin)!!.first)
        assertEquals(530, version(sin))
        assertFalse(needsPassword(sin))
    }

    @Test
    fun serverNameContainingPwd() {
        val sin = serverSin("Bob pwd")
        assertEquals(530, version(sin))
        assertFalse(needsPassword(sin))
    }

    @Test
    fun nameWithSpacesKept() {
        // Spaces left in the name: the fields after it are still found
        val sin = frame("SIN", "Office PC nopwd @rm1 530")
        assertEquals(530, version(sin))
        assertFalse(needsPassword(sin))
        assertEquals(530, ChunkBuilder.extractServerVersion("Office PC nopwd @rm1 530"))
    }

    @Test
    fun ourPasswordServer() {
        val sin = frame("SIN", "Living_Room_PC pwd @rmauth1 530")
        assertEquals(530, version(sin))
        assertTrue(needsPassword(sin))
    }

    @Test
    fun lengthFieldMustBeDigits() {
        assertNull(ChunkBuilder.parseHandshake("SIN-01x nopwd @rm1 530".toByteArray()))
        assertNull(ChunkBuilder.parseHandshake("SIN+05x nopwd @rm1 530".toByteArray()))
    }

    @Test
    fun discoveryReply() {
        assertEquals("MyPC", ChunkBuilder.parseDiscoveryResponse(frame("BC", "MyPC")))
        assertEquals("Wörld", ChunkBuilder.parseDiscoveryResponse(frame("BC", "Wörld")))
        assertNull(ChunkBuilder.parseDiscoveryResponse("BC-01x".toByteArray()))
        assertNull(ChunkBuilder.parseDiscoveryResponse("BC+05MyPC!".toByteArray()))
        assertNull(ChunkBuilder.parseDiscoveryResponse("BC009MyPC".toByteArray()))
    }
}
