package com.airkeyboard.ui.keyboard

import com.airkeyboard.ui.keyboard.LiveTyping.KeyOut.Press
import com.airkeyboard.ui.keyboard.LiveTyping.KeyOut.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTypingTest {

    private val bs: LiveTyping.KeyOut = Press("BACKSPACE")

    private fun plan(old: String, new: String, vararg mods: String) = LiveTyping.plan(old, new, mods.toList())

    private fun out(old: String, new: String, vararg mods: String) = plan(old, new, *mods).out

    @Test
    fun append() {
        val p = plan("ab", "abc")
        assertEquals(listOf(Type("c")), p.out)
        assertFalse(p.reset)
        assertFalse(p.usedModifiers)
    }

    @Test
    fun delete() {
        assertEquals(listOf(bs), out("abc", "ab"))
    }

    @Test
    fun autocorrect() {
        assertEquals(listOf(bs, bs, Type("he ")), out("live teh", "live the "))
    }

    @Test
    fun midTextEdit() {
        assertEquals(List(6) { bs } + Type(", world"), out("hello world", "hello, world"))
    }

    @Test
    fun emojiAppendAndDelete() {
        assertEquals(listOf(Type("😀")), out("a", "a😀"))
        assertEquals(listOf(bs), out("a😀", "a"))
    }

    @Test
    fun sharedHighSurrogate() {
        // 😀 U+1F600 and 😁 U+1F601 share the high surrogate
        assertEquals(listOf(bs, Type("😁")), out("a😀", "a😁"))
    }

    @Test
    fun zwjFamilyDeletesEachCodePoint() {
        // man ZWJ woman ZWJ girl: 5 code points
        val family = "👨‍👩‍👧"
        assertEquals(List(5) { bs }, out(family, ""))
    }

    @Test
    fun newline() {
        val p = plan("ab", "ab\ncd")
        assertEquals(listOf(Press("ENTER"), Type("cd")), p.out)
        assertTrue(p.reset)
    }

    @Test
    fun crlfAndCr() {
        val p = plan("", "x\r\ny")
        assertEquals(listOf(Type("x"), Press("ENTER"), Type("y")), p.out)
        assertTrue(p.reset)
        assertEquals(listOf(Type("x"), Press("ENTER"), Type("y")), out("", "x\ry"))
        assertEquals(listOf(Press("ENTER"), Press("ENTER")), out("", "\n\n"))
    }

    @Test
    fun ctrlLetter() {
        val p = plan("", "a", "CTRL")
        assertEquals(listOf(Press("CTRL+A")), p.out)
        assertTrue(p.reset)
        assertTrue(p.usedModifiers)
    }

    @Test
    fun twoModifiers() {
        assertEquals(listOf(Press("CTRL+SHIFT+T")), out("x", "xt", "CTRL", "SHIFT"))
        assertEquals(listOf(Press("SHIFT+7")), out("", "7", "SHIFT"))
    }

    @Test
    fun ctrlBackspace() {
        val p = plan("ab", "a", "CTRL")
        assertEquals(listOf(Press("CTRL+BACKSPACE")), p.out)
        assertTrue(p.reset)
        assertTrue(p.usedModifiers)
    }

    @Test
    fun altEnter() {
        val p = plan("", "\n", "ALT")
        assertEquals(listOf(Press("ALT+ENTER")), p.out)
        assertTrue(p.reset)
    }

    @Test
    fun modifierDroppedForNonAscii() {
        val p = plan("", "ä", "CTRL")
        assertEquals(listOf(Type("ä")), p.out)
        assertTrue(p.usedModifiers)
        assertFalse(p.reset)
    }

    @Test
    fun modifierDroppedForWord() {
        val p = plan("", "ab", "CTRL")
        assertEquals(listOf(Type("ab")), p.out)
        assertTrue(p.usedModifiers)
        assertFalse(p.reset)
    }

    @Test
    fun equalStrings() {
        val p = plan("same", "same", "CTRL")
        assertEquals(emptyList<LiveTyping.KeyOut>(), p.out)
        assertFalse(p.reset)
        assertFalse(p.usedModifiers)
    }

    @Test
    fun selectAllCut() {
        assertEquals(List(3) { bs }, out("abc", ""))
    }

    @Test
    fun comboKey() {
        assertEquals("A", LiveTyping.comboKey("a"))
        assertEquals("Z", LiveTyping.comboKey("Z"))
        assertEquals("7", LiveTyping.comboKey("7"))
        assertNull(LiveTyping.comboKey("ä"))
        assertNull(LiveTyping.comboKey("ab"))
        assertNull(LiveTyping.comboKey("😀"))
        assertNull(LiveTyping.comboKey(""))
        assertNull(LiveTyping.comboKey(" "))
        assertNull(LiveTyping.comboKey("٣")) // Arabic-Indic digit
    }
}
