package com.airkeyboard.ui.common

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * App-wide settings, in the same "settings" file as the sensitivity and live-typing switches.
 * Settings writes them; the control screens read them in onResume.
 */
object AppPrefs {
    private const val PREFS = "settings"

    const val KEY_NATURAL_SCROLL = "natural_scroll"
    const val KEY_SCROLL_SPEED = "scroll_speed"
    const val KEY_ACCELERATION = "acceleration"
    const val KEY_LEFT_HANDED = "left_handed"
    const val KEY_THREE_FINGER_DRAG = "three_finger_drag"
    const val KEY_VOLUME_KEYS = "volume_keys_remote"
    const val KEY_KEEP_AWAKE = "keep_screen_awake"
    const val KEY_NIGHT_MODE = "night_mode"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Two-finger scroll moves the page with the fingers (like a phone) instead of like a wheel */
    fun naturalScroll(context: Context) = prefs(context).getBoolean(KEY_NATURAL_SCROLL, true)

    /** Scroll speed in percent, 25..300; 100 = one notch per 40 px of finger travel */
    fun scrollSpeed(context: Context) = prefs(context).getFloat(KEY_SCROLL_SPEED, 100f)

    /** Faster finger moves go proportionally further */
    fun acceleration(context: Context) = prefs(context).getBoolean(KEY_ACCELERATION, true)

    /** Swaps left and right click (tap = right, two-finger tap = left, buttons swapped) */
    fun leftHanded(context: Context) = prefs(context).getBoolean(KEY_LEFT_HANDED, false)

    /** Three fingers down and moving = drag with the left button held */
    fun threeFingerDrag(context: Context) = prefs(context).getBoolean(KEY_THREE_FINGER_DRAG, true)

    /** The tablet's volume buttons change the PC volume on the control screens */
    fun volumeKeysRemote(context: Context) = prefs(context).getBoolean(KEY_VOLUME_KEYS, true)

    fun keepScreenAwake(context: Context) = prefs(context).getBoolean(KEY_KEEP_AWAKE, true)

    /**
     * The red glass theme instead of the default blue one. Red is the "night" resources
     * (values-night, drawable-night), so it is stored as the night mode; an older saved
     * "Follow system" or "Light" reads as blue.
     */
    fun redTheme(context: Context) =
        prefs(context).getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_NO) == AppCompatDelegate.MODE_NIGHT_YES

    fun nightMode(context: Context) =
        if (redTheme(context)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO

    fun set(context: Context, key: String, value: Boolean) = prefs(context).edit().putBoolean(key, value).apply()

    fun set(context: Context, key: String, value: Float) = prefs(context).edit().putFloat(key, value).apply()

    /** Saves the theme and recreates the open screens in it */
    fun setRedTheme(context: Context, red: Boolean) {
        val mode = if (red) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        prefs(context).edit().putInt(KEY_NIGHT_MODE, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
