package com.airkeyboard.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.util.Locale

/** The server's "sys" replies: message framing, window entries, clipboard reassembly */
class ServerRepliesTest {

    private fun message(marker: String, body: ByteArray): ByteArray =
        marker.toByteArray(Charsets.US_ASCII) +
            String.format(Locale.ROOT, "%03d", body.size).toByteArray(Charsets.US_ASCII) + body

    private fun message(marker: String, body: String) = message(marker, body.toByteArray(Charsets.UTF_8))

    @Test
    fun readsMessagesThenEnd() {
        val input = ByteArrayInputStream(message("wnl", "123\tNotepad") + message("xyz", "skipped") + message("wne", ""))
        val first = ServerReplies.read(input)!!
        assertEquals("wnl", first.marker)
        assertEquals("123\tNotepad", String(first.body, Charsets.UTF_8))
        // An unknown marker is read whole, so the next message still lines up
        assertEquals("xyz", ServerReplies.read(input)!!.marker)
        val end = ServerReplies.read(input)!!
        assertEquals("wne", end.marker)
        assertEquals(0, end.body.size)
        assertNull(ServerReplies.read(input))
    }

    @Test(expected = EOFException::class)
    fun endInsideMessage() {
        ServerReplies.read(ByteArrayInputStream("cbd010abc".toByteArray()))
    }

    @Test(expected = ServerReplies.MalformedException::class)
    fun badLength() {
        ServerReplies.read(ByteArrayInputStream("cbd-01abc".toByteArray()))
    }

    @Test
    fun parseWindow() {
        val w = ServerReplies.parseWindow("65538\tReport – Word\twith tab".toByteArray(Charsets.UTF_8))!!
        assertEquals("65538", w.id)
        assertEquals("Report – Word\twith tab", w.title)
        assertNull(ServerReplies.parseWindow("\tNo id".toByteArray()))
        assertNull(ServerReplies.parseWindow("12a\tBad id".toByteArray()))
        assertNull(ServerReplies.parseWindow("123".toByteArray()))
    }

    @Test
    fun windowsReply() {
        val reply = ServerReplies.WindowsReply()
        reply.add("1\tA".toByteArray())
        reply.add("junk".toByteArray())
        reply.add("2\tB".toByteArray())
        assertEquals(listOf(PcWindow("1", "A"), PcWindow("2", "B")), reply.end())
        // The next list starts empty
        assertEquals(emptyList<PcWindow>(), reply.end())
    }

    @Test
    fun clipboardReassemblesSplitCodePoints() {
        val text = "a€😀".repeat(400)
        val reply = ServerReplies.ClipboardReply()
        reply.begin()
        // Slices on code-point boundaries, as the server sends them
        for (slice in ChunkBuilder.splitUtf8(text, 900)) reply.data(slice)
        assertEquals(text, reply.end(ByteArray(0)))
    }

    @Test
    fun clipboardFailures() {
        val reply = ServerReplies.ClipboardReply()
        // No text on the PC clipboard
        reply.begin()
        assertNull(reply.end(ByteArray(0)))
        // Too large
        reply.begin()
        assertNull(reply.end("toolarge".toByteArray()))
        // Data and end without a begin
        reply.data("x".toByteArray())
        assertNull(reply.end(ByteArray(0)))
        // More than the server may send
        reply.begin()
        repeat(1200) { reply.data(ByteArray(900)) }
        assertNull(reply.end(ByteArray(0)))
    }

    @Test
    fun systemFrame() {
        assertArrayEquals("sys008pwr lock".toByteArray(), ChunkBuilder.system("pwr lock"))
    }

    @Test
    fun readsEmptyPong() {
        val pong = ServerReplies.read(ByteArrayInputStream("png000".toByteArray()))!!
        assertEquals(ServerReplies.PONG, pong.marker)
        assertEquals(0, pong.body.size)
    }
}
