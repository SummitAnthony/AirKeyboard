package com.airkeyboard.ui.keyboard

/**
 * Live typing: turns an edit of the keyboard field (old text -> new text) into the keystrokes
 * that make the PC's text match. Pure Kotlin, so it is unit-tested on the JVM.
 *
 * Everything from the first difference to the end is rewritten: delete the old tail with
 * Backspace (one per code point), then type the new tail. Line breaks become Enter.
 */
object LiveTyping {
    sealed interface KeyOut {
        data class Type(val text: String) : KeyOut
        /** A key name or "+"-joined combo, as AirKeyboardClient.sendKeyPress takes it */
        data class Press(val key: String) : KeyOut
    }

    /**
     * [reset]: the field must be cleared, because the PC result can no longer be mirrored
     * (after a line break or a modifier combo). [usedModifiers]: the sticky modifiers are spent
     */
    data class LivePlan(val out: List<KeyOut>, val reset: Boolean, val usedModifiers: Boolean)

    private val LINE_BREAK = Regex("\r\n|\r|\n")

    fun plan(old: String, new: String, mods: List<String>): LivePlan {
        if (old == new) return LivePlan(emptyList(), reset = false, usedModifiers = false)

        var p = 0
        val max = minOf(old.length, new.length)
        while (p < max && old[p] == new[p]) p++
        // Never cut between the halves of a surrogate pair
        if (p > 0 && new[p - 1].isHighSurrogate()) p--

        val del = old.codePointCount(p, old.length)
        val ins = new.substring(p)

        if (mods.isNotEmpty()) {
            val key = when {
                del == 0 && ins == "\n" -> "ENTER"
                del == 0 -> comboKey(ins)
                del == 1 && ins.isEmpty() -> "BACKSPACE"
                else -> null
            }
            if (key != null) {
                return LivePlan(listOf(KeyOut.Press((mods + key).joinToString("+"))), reset = true, usedModifiers = true)
            }
            // Anything else (emoji, a non-ASCII letter, a whole word): the modifiers are dropped
        }

        val out = ArrayList<KeyOut>()
        repeat(del) { out.add(KeyOut.Press("BACKSPACE")) }
        val segments = ins.split(LINE_BREAK)
        segments.forEachIndexed { i, segment ->
            if (i > 0) out.add(KeyOut.Press("ENTER"))
            if (segment.isNotEmpty()) out.add(KeyOut.Type(segment))
        }
        return LivePlan(out, reset = segments.size > 1, usedModifiers = mods.isNotEmpty())
    }

    /** The combo key for [text] if it is exactly one ASCII letter or digit (uppercase), else null */
    fun comboKey(text: String): String? {
        if (text.length != 1) return null
        val c = text[0]
        return if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') c.uppercaseChar().toString() else null
    }
}
