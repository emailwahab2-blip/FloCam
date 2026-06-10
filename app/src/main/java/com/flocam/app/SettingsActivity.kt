package com.flocam.app

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val prefs = getSharedPreferences(FloatingCameraService.PREF_NAME, Context.MODE_PRIVATE)
        val seekBarSize = findViewById<SeekBar>(R.id.seekbar_size)
        val tvSizeValue = findViewById<TextView>(R.id.tv_size_value)
        val rgShape = findViewById<RadioGroup>(R.id.rg_shape)
        val btnApply = findViewById<Button>(R.id.btn_apply)

        val savedSize = prefs.getInt(FloatingCameraService.PREF_SIZE, FloatingCameraService.DEFAULT_SIZE_DP)
        val savedShape = prefs.getString(FloatingCameraService.PREF_SHAPE, FloatingCameraService.SHAPE_CIRCLE)

        // Seekbar maps 0–100 → 100dp–400dp
        seekBarSize.progress = ((savedSize - 100) * 100 / 300).coerceIn(0, 100)
        tvSizeValue.text = getString(R.string.size_dp_value, savedSize)

        seekBarSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val dp = 100 + progress * 3
                tvSizeValue.text = getString(R.string.size_dp_value, dp)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        when (savedShape) {
            FloatingCameraService.SHAPE_CIRCLE -> rgShape.check(R.id.rb_circle)
            FloatingCameraService.SHAPE_SQUARE -> rgShape.check(R.id.rb_square)
        }

        btnApply.setOnClickListener {
            val size = 100 + seekBarSize.progress * 3
            val shape = when (rgShape.checkedRadioButtonId) {
                R.id.rb_circle -> FloatingCameraService.SHAPE_CIRCLE
                R.id.rb_square -> FloatingCameraService.SHAPE_SQUARE
                else -> FloatingCameraService.SHAPE_CIRCLE
            }
            prefs.edit()
                .putInt(FloatingCameraService.PREF_SIZE, size)
                .putString(FloatingCameraService.PREF_SHAPE, shape)
                .apply()

            if (FloatingCameraService.isRunning) {
                FloatingCameraService.update(this)
            }
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
