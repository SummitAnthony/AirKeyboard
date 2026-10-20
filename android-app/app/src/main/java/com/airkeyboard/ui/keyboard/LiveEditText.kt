package com.airkeyboard.ui.keyboard

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

/**
 * EditText that reports Backspace on an empty field in live typing mode.
 * The field doesn't change then, so the text diff can't see it; IMEs that delete through
 * deleteSurroundingText would otherwise never reach the PC.
 */
class LiveEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatEditText(context, attrs) {

    var live = false
    var onEmptyBackspace: (() -> Unit)? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val target = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(target, true) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
                emptyBackspace(beforeLength) || super.deleteSurroundingText(beforeLength, afterLength)

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
                emptyBackspace(beforeLength) ||
                    super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        }
    }

    /** True if this delete was a Backspace on the empty field and has been reported */
    private fun emptyBackspace(beforeLength: Int): Boolean {
        if (!live || beforeLength <= 0 || !text.isNullOrEmpty()) return false
        onEmptyBackspace?.invoke()
        return true
    }
}
