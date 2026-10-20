package com.airkeyboard.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

class Utf8SplitTest {

    /** Every slice fits, decodes strictly on its own, and the slices rebuild the text */
    private fun check(text: String): List<ByteArray> {
        val slices = ChunkBuilder.splitUtf8(text)
        val rebuilt = StringBuilder()
        for (slice in slices) {
            assertTrue("slice of ${slice.size} bytes", slice.size in 1..ChunkBuilder.CLIPBOARD_SLICE_MAX)
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            rebuilt.append(decoder.decode(ByteBuffer.wrap(slice)))
        }
        assertEquals(text, rebuilt.toString())
        return slices
    }

    @Test
    fun empty() {
        assertEquals(0, ChunkBuilder.splitUtf8("").size)
    }

    @Test
    fun ascii() {
        assertEquals(listOf(998), check("a".repeat(998)).map { it.size })
        assertEquals(listOf(998, 998, 4), check("a".repeat(2000)).map { it.size })
    }

    @Test
    fun twoByteAtBoundary() {
        // "a" + é...: byte 998 is a continuation byte, so the first slice stops at 997
        assertEquals(997, check("a" + "é".repeat(1000))[0].size)
        assertEquals(998, check("é".repeat(1000))[0].size)
    }

    @Test
    fun threeByteAtBoundary() {
        assertEquals(996, check("€".repeat(1000))[0].size)
        assertEquals(998, check("aa" + "€".repeat(1000))[0].size)
    }

    @Test
    fun fourByteAtBoundary() {
        // Surrogate pairs in the String, one 4-byte sequence in UTF-8
        assertEquals(996, check("😀".repeat(500))[0].size)
        assertEquals(997, check("a" + "😀".repeat(500))[0].size)
        assertEquals(998, check("aa" + "😀".repeat(500))[0].size)
    }

    @Test
    fun clipboardFrames() {
        assertArrayEquals("clp001b".toByteArray(), ChunkBuilder.clipboardBegin())
        assertArrayEquals("clp001e".toByteArray(), ChunkBuilder.clipboardEnd())
        val data = ChunkBuilder.clipboardData(ByteArray(998) { 'x'.code.toByte() })
        assertEquals("clp999d", String(data, 0, 7, Charsets.US_ASCII))
        assertEquals(6 + 999, data.size)
    }

    @Test
    fun lengthFieldIsAsciiInArabicLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "EG"))
            assertArrayEquals("clp001b".toByteArray(), ChunkBuilder.clipboardBegin())
            assertArrayEquals("mos005m 1 2".toByteArray(), ChunkBuilder.mouseMove(1, 2))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun clipboardDataTooLong() {
        ChunkBuilder.clipboardData(ByteArray(999))
    }
}
