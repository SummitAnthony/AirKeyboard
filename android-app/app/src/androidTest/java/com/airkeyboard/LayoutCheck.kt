package com.airkeyboard

import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import org.hamcrest.Matcher

/**
 * Layout problems on one window, for small screens. "FAIL" lines: a view running past its parent
 * (and so past the window), text cut short with "…", text taller or wider than its view.
 * "NOTE" lines, for a look at the screenshot: tap targets under 32 dp, text under 12 sp.
 * Children of scrolling containers may run past them; their insides are still checked.
 */
object LayoutCheck {

    /** Checks the root it runs on (the focused window: a dialog when one is open) into [out] */
    fun collect(screen: String, out: MutableList<String>) = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isRoot()
        override fun getDescription() = "layout check of $screen"
        override fun perform(uiController: UiController, view: View) {
            walk(view, screen, out, inList = false)
        }
    }

    /** [inList]: inside a list row, where long data (PC window titles) may end in "…" by design */
    private fun walk(view: View, screen: String, out: MutableList<String>, inList: Boolean) {
        if (!shown(view)) return
        val res = view.resources
        val dp = res.displayMetrics.density
        if (view is TextView && view !is EditText) {
            textCut(view, inList)?.let { out += "FAIL $screen: ${name(view)} $it" }
            val sp = view.textSize / res.displayMetrics.scaledDensity
            if (view.text.isNotEmpty() && sp < 11.5f) out += "NOTE $screen: ${name(view)} text ${"%.1f".format(sp)} sp"
        }
        if (view.isClickable && (view.width < 32 * dp - 1 || view.height < 32 * dp - 1)) {
            out += "NOTE $screen: ${name(view)} tap target ${(view.width / dp).toInt()}x${(view.height / dp).toInt()} dp"
        }
        if (view !is ViewGroup) return
        val list = view is RecyclerView || view is AbsListView
        val scrolls = list || view is ScrollView || view is NestedScrollView || view is HorizontalScrollView
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (!shown(child)) continue
            if (!scrolls && (child.left < -1 || child.top < -1 || child.right > view.width + 1 || child.bottom > view.height + 1)) {
                out += "FAIL $screen: ${name(child)} [${child.left},${child.top},${child.right},${child.bottom}] " +
                    "runs past ${name(view)} (${view.width}x${view.height} px)"
            }
            walk(child, screen, out, inList || list)
        }
    }

    private fun shown(v: View) = v.visibility == View.VISIBLE && v.width > 0 && v.height > 0 && v.alpha > 0f

    private fun textCut(t: TextView, inList: Boolean): String? {
        val layout = t.layout ?: return null
        if (t.text.isEmpty()) return null
        if (!inList) for (line in 0 until layout.lineCount) {
            if (layout.getEllipsisCount(line) > 0) return "text cut short: \"${t.text}\""
        }
        // maxLines without ellipsize: the extra lines are laid out but never drawn
        if (layout.lineCount > t.maxLines) return "text hidden past ${t.maxLines} line(s): \"${t.text}\""
        val availH = t.height - t.compoundPaddingTop - t.compoundPaddingBottom
        if (layout.height > availH + 1) return "text taller than its view (${layout.height} > $availH px): \"${t.text}\""
        val availW = t.width - t.compoundPaddingLeft - t.compoundPaddingRight
        // getLineMax: without the trailing space at a line break
        val widest = (0 until layout.lineCount).maxOf { layout.getLineMax(it) }
        if (widest > availW + 1) return "text wider than its view (${widest.toInt()} > $availW px): \"${t.text}\""
        return null
    }

    private fun name(v: View): String {
        val id = if (v.id != View.NO_ID) runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() else null
        return id ?: v.javaClass.simpleName
    }
}
