package com.airkeyboard.ui.common

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import com.airkeyboard.R

/** Glass pill toast near the bottom of the screen (Glass spec: dark glass, 14sp SemiBold) */
fun Context.glassToast(text: CharSequence, length: Int = Toast.LENGTH_SHORT) {
    val view = LayoutInflater.from(this).inflate(R.layout.view_glass_toast, null) as TextView
    view.text = text
    @Suppress("DEPRECATION")
    Toast(applicationContext).apply {
        duration = length
        this.view = view
        setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, (72 * resources.displayMetrics.density).toInt())
    }.show()
}

fun Context.glassToast(@StringRes text: Int, length: Int = Toast.LENGTH_SHORT) = glassToast(getString(text), length)
