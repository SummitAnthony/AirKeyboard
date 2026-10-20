package com.airkeyboard.ui.keyboard

import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.airkeyboard.R

/**
 * The extra key panels of the keyboard screen (F-keys, numpad, navigation), as data.
 * [key] is the server's key name (windows-server InputController); null leaves a gap.
 */
object KeyPanels {
    data class Key(val label: String, val key: String?, val weight: Float = 1f)

    val F_KEYS: List<List<Key>> = listOf(
        (1..6).map { Key("F$it", "F$it") },
        (7..12).map { Key("F$it", "F$it") },
    )

    val NUMPAD: List<List<Key>> = listOf(
        listOf(num(7), num(8), num(9), Key("÷", "DIVIDE"), Key("Num Lock", "NUMLOCK")),
        listOf(num(4), num(5), num(6), Key("×", "MULTIPLY"), Key("−", "SUBTRACT")),
        listOf(num(1), num(2), num(3), Key("+", "ADD", 2f)),
        listOf(Key("0", "NUMPAD0", 2f), Key(".", "DECIMAL"), Key("Enter", "ENTER", 2f)),
    )

    val NAV: List<List<Key>> = listOf(
        listOf(Key("Insert", "INSERT"), Key("Home", "HOME"), Key("PgUp", "PAGEUP"),
            Key("", null), Key("↑", "UP"), Key("", null)),
        listOf(Key("Delete", "DELETE"), Key("End", "END"), Key("PgDn", "PAGEDOWN"),
            Key("←", "LEFT"), Key("↓", "DOWN"), Key("→", "RIGHT")),
    )

    private fun num(n: Int) = Key("$n", "NUMPAD$n")

    /**
     * Replace [container]'s children with [rows] of glass keys; a tap calls [onKey] with the key name.
     * Every row of a panel adds up to the same number of columns and a key's weight is its column
     * span; a base width of (span - 1) gaps lines the keys up as a grid.
     */
    fun build(container: LinearLayout, rows: List<List<Key>>, onKey: (String) -> Unit) {
        container.removeAllViews()
        val context = container.context
        val gap = context.resources.getDimensionPixelSize(R.dimen.kb_gap_key)
        val height = context.resources.getDimensionPixelSize(R.dimen.height_key)
        val inflater = LayoutInflater.from(context)
        for (row in rows) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { i, k ->
                val view = if (k.key == null) {
                    View(context)
                } else {
                    (inflater.inflate(R.layout.keyboard_key, line, false) as Button).apply {
                        text = k.label
                        // On a narrow key "Num Lock" wraps at the space rather than shrinking further
                        if (' ' in k.label) maxLines = 2
                        setOnClickListener { onKey(k.key) }
                    }
                }
                line.addView(view, LinearLayout.LayoutParams(((k.weight - 1) * gap).toInt(),
                    LinearLayout.LayoutParams.MATCH_PARENT, k.weight)
                    .apply { if (i > 0) marginStart = gap })
            }
            container.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
                .apply { topMargin = gap })
        }
    }
}
