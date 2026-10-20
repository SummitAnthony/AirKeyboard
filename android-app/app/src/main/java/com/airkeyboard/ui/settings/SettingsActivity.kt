package com.airkeyboard.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.airkeyboard.R
import com.airkeyboard.databinding.ActivitySettingsBinding
import com.airkeyboard.network.PasswordStore
import com.airkeyboard.network.AirKeyboardClient
import com.airkeyboard.ui.common.AppPrefs
import com.airkeyboard.ui.common.glassToast

/**
 * Settings screen: every control saves as soon as it changes (no Save button)
 */
class SettingsActivity : AppCompatActivity() {
    companion object {
        private const val PREFS = "settings"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val DEFAULT_SENSITIVITY = 100f

        /** Trackpad-to-cursor scale; 100% sensitivity = 1.5x */
        fun pointerMultiplier(context: Context): Float =
            1.5f * sensitivityPercent(context) / 100f

        private fun sensitivityPercent(context: Context): Float =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY)
    }

    private lateinit var binding: ActivitySettingsBinding
    private var host: String = ""
    private var port: Int = AirKeyboardClient.DEFAULT_PORT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        host = intent.getStringExtra("host") ?: "127.0.0.1"
        port = intent.getIntExtra("port", AirKeyboardClient.DEFAULT_PORT)

        setupUI()
    }

    private fun setupUI() {
        binding.apply {
            tvConnectionStatus.text = "$host:$port"

            // Pointer
            bindSlider(sliderSensitivity, tvSensitivityValue, sensitivityPercent(this@SettingsActivity), KEY_SENSITIVITY)
            bindSwitch(rowAcceleration, switchAcceleration, AppPrefs.acceleration(this@SettingsActivity), AppPrefs.KEY_ACCELERATION)
            bindSwitch(rowThreeFingerDrag, switchThreeFingerDrag, AppPrefs.threeFingerDrag(this@SettingsActivity), AppPrefs.KEY_THREE_FINGER_DRAG)

            // Scrolling
            bindSwitch(rowNaturalScroll, switchNaturalScroll, AppPrefs.naturalScroll(this@SettingsActivity), AppPrefs.KEY_NATURAL_SCROLL)
            bindSlider(sliderScrollSpeed, tvScrollSpeedValue, AppPrefs.scrollSpeed(this@SettingsActivity), AppPrefs.KEY_SCROLL_SPEED)

            // Buttons
            bindSwitch(rowLeftHanded, switchLeftHanded, AppPrefs.leftHanded(this@SettingsActivity), AppPrefs.KEY_LEFT_HANDED)
            bindSwitch(rowVolumeKeys, switchVolumeKeys, AppPrefs.volumeKeysRemote(this@SettingsActivity), AppPrefs.KEY_VOLUME_KEYS)

            // Display; the keep-awake flag is applied by App when a screen resumes
            bindSwitch(rowKeepAwake, switchKeepAwake, AppPrefs.keepScreenAwake(this@SettingsActivity), AppPrefs.KEY_KEEP_AWAKE)
            toggleTheme.check(if (AppPrefs.redTheme(this@SettingsActivity)) R.id.btnThemeRed else R.id.btnThemeBlue)
            toggleTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val red = checkedId == R.id.btnThemeRed
                // Recreates the open screens in the new theme
                if (red != AppPrefs.redTheme(this@SettingsActivity)) AppPrefs.setRedTheme(this@SettingsActivity, red)
            }

            btnForgetPasswords.setOnClickListener {
                PasswordStore.clear()
                glassToast(R.string.passwords_forgotten)
            }

            // Back button
            btnBack.setOnClickListener { finish() }
        }
    }

    /** Shows the saved state and saves every change under [key]; tapping anywhere on [row] toggles it */
    private fun bindSwitch(row: View, switch: SwitchMaterial, checked: Boolean, key: String) {
        switch.isChecked = checked
        row.setOnClickListener { switch.toggle() }
        switch.setOnCheckedChangeListener { _, isChecked -> AppPrefs.set(this, key, isChecked) }
    }

    /** Shows the saved percentage and saves every change under [key] */
    private fun bindSlider(slider: Slider, label: TextView, percent: Float, key: String) {
        slider.value = percent.coerceIn(slider.valueFrom, slider.valueTo)
        label.text = getString(R.string.settings_percent, slider.value.toInt())
        slider.addOnChangeListener { _, value, _ ->
            label.text = getString(R.string.settings_percent, value.toInt())
            AppPrefs.set(this, key, value)
        }
    }
}
