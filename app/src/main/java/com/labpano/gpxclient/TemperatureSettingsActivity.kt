package com.labpano.gpxclient

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

class TemperatureSettingsActivity : Activity() {
    private lateinit var thresholdInput: EditText
    private lateinit var thresholdSlider: SeekBar
    private lateinit var currentTemperature: TextView
    private lateinit var rearmDetails: TextView
    private var syncingThresholdControls = false
    private var testPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Temperature Alert Settings"
        setContentView(buildUi())
        refreshValues()
    }

    override fun onResume() {
        super.onResume()
        refreshValues()
    }

    override fun onDestroy() {
        testPlayer?.release()
        testPlayer = null
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
            setBackgroundColor(Color.rgb(244, 246, 248))
        }

        content.addView(TextView(this).apply {
            text = "Pilot One Device Temperature"
            textSize = 22f
            setTypeface(typeface, 1)
            setTextColor(Color.rgb(32, 33, 36))
        })

        content.addView(TextView(this).apply {
            text = "The client monitors the same Pilot One temperature reported by the Camera App from /sys/class/thermal/thermal_zone0/temp. Monitoring continues in the background while the camera connection service is active."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(16))
        })

        currentTemperature = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, 1)
            setTextColor(Color.rgb(32, 33, 36))
            setPadding(0, 0, 0, dp(18))
        }
        content.addView(currentTemperature)

        content.addView(TextView(this).apply {
            text = "Warning threshold (°C)"
            textSize = 16f
            setTypeface(typeface, 1)
            setTextColor(Color.rgb(32, 33, 36))
        })

        thresholdInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Temperature warning threshold in degrees Celsius"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (syncingThresholdControls) return
                    val value = s?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return
                    if (value !in TemperatureAlertPolicy.MIN_THRESHOLD_C..TemperatureAlertPolicy.MAX_THRESHOLD_C) return
                    updateSliderFromValue(value)
                    updateRearmDetails(value)
                }
            })
        }
        content.addView(thresholdInput, LinearLayout.LayoutParams(-1, dp(56)))

        thresholdSlider = SeekBar(this).apply {
            max = SLIDER_MAX_PROGRESS
            progressTintList = ColorStateList.valueOf(PURPLE)
            thumbTintList = ColorStateList.valueOf(PURPLE)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(205, 200, 214))
            contentDescription = "Temperature warning threshold slider"
            setPadding(0, dp(6), 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || syncingThresholdControls) return
                    val value = sliderValue(progress)
                    updateInputFromValue(value)
                    updateRearmDetails(value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        content.addView(thresholdSlider, LinearLayout.LayoutParams(-1, dp(54)))

        val sliderLabels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@TemperatureSettingsActivity).apply {
                text = String.format(Locale.US, "%.0f °C", TemperatureAlertPolicy.MIN_THRESHOLD_C)
                textSize = 12f
                setTextColor(Color.DKGRAY)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@TemperatureSettingsActivity).apply {
                text = String.format(Locale.US, "%.0f °C", TemperatureAlertPolicy.MAX_THRESHOLD_C)
                textSize = 12f
                gravity = Gravity.END
                setTextColor(Color.DKGRAY)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        content.addView(sliderLabels)

        rearmDetails = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(16))
        }
        content.addView(rearmDetails)

        content.addView(Button(this).apply {
            text = "SAVE TEMPERATURE THRESHOLD"
            setOnClickListener { saveThreshold() }
        })

        content.addView(Button(this).apply {
            text = "RESET TO 73 °C"
            setOnClickListener {
                TemperatureAlertSettings.resetToDefault(this@TemperatureSettingsActivity)
                refreshValues()
                Toast.makeText(this@TemperatureSettingsActivity, "Temperature threshold reset to 73 °C", Toast.LENGTH_SHORT).show()
            }
        })

        content.addView(Button(this).apply {
            text = "TEST WARNING SOUND"
            setOnClickListener { playTestSound() }
        })

        content.addView(TextView(this).apply {
            text = "Type the exact warning temperature or use the purple slider. The warning plays once when the measured temperature rises above the threshold. It rearms after the temperature falls 3 °C below the threshold. A 10-minute cooldown prevents rapid repeat alerts."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(14), 0, 0)
        })

        return ScrollView(this).apply {
            isFillViewport = true
            addView(content, android.view.ViewGroup.LayoutParams(-1, -2))
        }
    }

    private fun refreshValues() {
        val threshold = TemperatureAlertSettings.thresholdC(this)
        if (::thresholdInput.isInitialized) updateInputFromValue(threshold)
        if (::thresholdSlider.isInitialized) updateSliderFromValue(threshold)
        updateRearmDetails(threshold)

        if (::currentTemperature.isInitialized) {
            val battery = ClientSessionState.lastDashboard?.battery
            val temperature = battery?.temperatureC
            currentTemperature.text = if (temperature != null) {
                String.format(
                    Locale.US,
                    "Current Pilot One temperature: %.1f °C",
                    temperature
                )
            } else {
                "Current Pilot One temperature: unavailable\nConnect to the Camera App to receive thermal data."
            }
            currentTemperature.setTextColor(
                if (temperature != null && temperature > threshold) Color.rgb(198, 40, 40)
                else Color.rgb(32, 33, 36)
            )
        }
    }

    private fun updateInputFromValue(value: Double) {
        if (!::thresholdInput.isInitialized) return
        val formatted = String.format(Locale.US, "%.1f", value)
        if (thresholdInput.text.toString() == formatted) return
        syncingThresholdControls = true
        try {
            thresholdInput.setText(formatted)
            thresholdInput.setSelection(thresholdInput.text.length)
        } finally {
            syncingThresholdControls = false
        }
    }

    private fun updateSliderFromValue(value: Double) {
        if (!::thresholdSlider.isInitialized) return
        val progress = ((value - TemperatureAlertPolicy.MIN_THRESHOLD_C) * SLIDER_STEPS_PER_C)
            .roundToInt()
            .coerceIn(0, SLIDER_MAX_PROGRESS)
        if (thresholdSlider.progress == progress) return
        syncingThresholdControls = true
        try {
            thresholdSlider.progress = progress
        } finally {
            syncingThresholdControls = false
        }
    }

    private fun sliderValue(progress: Int): Double =
        TemperatureAlertPolicy.MIN_THRESHOLD_C + progress.toDouble() / SLIDER_STEPS_PER_C

    private fun updateRearmDetails(threshold: Double) {
        if (!::rearmDetails.isInitialized) return
        rearmDetails.text = String.format(
            Locale.US,
            "Selected threshold: %.1f °C • Allowed range: %.0f–%.0f °C • Alarm rearms at %.1f °C or lower",
            threshold,
            TemperatureAlertPolicy.MIN_THRESHOLD_C,
            TemperatureAlertPolicy.MAX_THRESHOLD_C,
            TemperatureAlertPolicy.rearmTemperatureC(threshold)
        )
    }

    private fun saveThreshold() {
        val text = thresholdInput.text.toString().trim().replace(',', '.')
        val value = text.toDoubleOrNull()
        if (value == null) {
            thresholdInput.error = "Enter a numeric temperature in °C"
            return
        }
        val result = runCatching { TemperatureAlertSettings.saveThresholdC(this, value) }
        if (result.isFailure) {
            thresholdInput.error = result.exceptionOrNull()?.message ?: "Invalid temperature threshold"
            return
        }
        Toast.makeText(this, String.format(Locale.US, "Warning threshold saved: %.1f °C", value), Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun playTestSound() {
        if (AppSoundSettings.isMuted(this)) {
            Toast.makeText(this, "App sounds are muted", Toast.LENGTH_SHORT).show()
            return
        }
        testPlayer?.release()
        val player = MediaPlayer.create(this, R.raw.battery_temp_combined) ?: run {
            Toast.makeText(this, "Could not play the warning sound", Toast.LENGTH_LONG).show()
            return
        }
        testPlayer = player
        player.setOnCompletionListener {
            it.release()
            if (testPlayer === it) testPlayer = null
        }
        player.setOnErrorListener { failed, _, _ ->
            failed.release()
            if (testPlayer === failed) testPlayer = null
            true
        }
        player.start()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SLIDER_STEPS_PER_C = 10.0
        private val SLIDER_MAX_PROGRESS = (
            (TemperatureAlertPolicy.MAX_THRESHOLD_C - TemperatureAlertPolicy.MIN_THRESHOLD_C) * SLIDER_STEPS_PER_C
        ).roundToInt()
        private val PURPLE = Color.rgb(123, 31, 162)
    }
}
