package com.flocam.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private var selectedBgUri: Uri? = null

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            selectedBgUri = uri
            showBgPreview(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val prefs = getSharedPreferences(FloatingCameraService.PREF_NAME, Context.MODE_PRIVATE)

        // ---- Size ----
        val seekBarSize = findViewById<SeekBar>(R.id.seekbar_size)
        val tvSizeValue = findViewById<TextView>(R.id.tv_size_value)
        val savedSize = prefs.getInt(FloatingCameraService.PREF_SIZE, FloatingCameraService.DEFAULT_SIZE_DP)
        seekBarSize.progress = ((savedSize - 100) * 100 / 300).coerceIn(0, 100)
        tvSizeValue.text = getString(R.string.size_dp_value, savedSize)
        seekBarSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvSizeValue.text = getString(R.string.size_dp_value, 100 + progress * 3)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // ---- Shape ----
        val rgShape = findViewById<RadioGroup>(R.id.rg_shape)
        val savedShape = prefs.getString(FloatingCameraService.PREF_SHAPE, FloatingCameraService.SHAPE_CIRCLE)
        when (savedShape) {
            FloatingCameraService.SHAPE_CIRCLE -> rgShape.check(R.id.rb_circle)
            FloatingCameraService.SHAPE_SQUARE -> rgShape.check(R.id.rb_square)
        }

        // ---- Background mode ----
        val rgBgMode = findViewById<RadioGroup>(R.id.rg_bg_mode)
        val btnPickBg = findViewById<Button>(R.id.btn_pick_bg)
        val ivBgPreview = findViewById<ImageView>(R.id.iv_bg_preview)
        val tvBgHint = findViewById<TextView>(R.id.tv_bg_hint)

        val savedBgMode = prefs.getString(FloatingCameraService.PREF_BG_MODE, FloatingCameraService.BG_MODE_OFF)
            ?: FloatingCameraService.BG_MODE_OFF
        val savedBgUriStr = prefs.getString(FloatingCameraService.PREF_BG_IMAGE_URI, null)

        when (savedBgMode) {
            FloatingCameraService.BG_MODE_BLUR -> rgBgMode.check(R.id.rb_bg_blur)
            FloatingCameraService.BG_MODE_REPLACE -> rgBgMode.check(R.id.rb_bg_replace)
            else -> rgBgMode.check(R.id.rb_bg_off)
        }

        if (savedBgUriStr != null) {
            selectedBgUri = Uri.parse(savedBgUriStr)
        }

        // Show/hide gallery UI based on saved mode
        applyReplaceUiVisibility(
            savedBgMode == FloatingCameraService.BG_MODE_REPLACE,
            savedBgUriStr != null,
            btnPickBg, ivBgPreview, tvBgHint
        )
        if (savedBgUriStr != null && savedBgMode == FloatingCameraService.BG_MODE_REPLACE) {
            runCatching { ivBgPreview.setImageURI(selectedBgUri) }
        }

        // ---- Segmentation quality ----
        val llSegQuality = findViewById<LinearLayout>(R.id.ll_seg_quality)
        val rgSegQuality = findViewById<RadioGroup>(R.id.rg_seg_quality)

        val savedQuality = prefs.getString(
            FloatingCameraService.PREF_SEG_QUALITY, FloatingCameraService.SEG_QUALITY_NORMAL
        ) ?: FloatingCameraService.SEG_QUALITY_NORMAL
        when (savedQuality) {
            FloatingCameraService.SEG_QUALITY_SMOOTH -> rgSegQuality.check(R.id.rb_seg_smooth)
            else -> rgSegQuality.check(R.id.rb_seg_normal)
        }
        llSegQuality.visibility = if (savedBgMode != FloatingCameraService.BG_MODE_OFF)
            View.VISIBLE else View.GONE

        rgBgMode.setOnCheckedChangeListener { _, checkedId ->
            val isReplace = checkedId == R.id.rb_bg_replace
            val isOff = checkedId == R.id.rb_bg_off
            applyReplaceUiVisibility(isReplace, selectedBgUri != null, btnPickBg, ivBgPreview, tvBgHint)
            llSegQuality.visibility = if (isOff) View.GONE else View.VISIBLE
        }

        btnPickBg.setOnClickListener {
            galleryLauncher.launch("image/*")
        }

        // ---- Apply ----
        val btnApply = findViewById<Button>(R.id.btn_apply)
        btnApply.setOnClickListener {
            val size = 100 + seekBarSize.progress * 3
            val shape = when (rgShape.checkedRadioButtonId) {
                R.id.rb_circle -> FloatingCameraService.SHAPE_CIRCLE
                R.id.rb_square -> FloatingCameraService.SHAPE_SQUARE
                else -> FloatingCameraService.SHAPE_CIRCLE
            }
            val bgMode = when (rgBgMode.checkedRadioButtonId) {
                R.id.rb_bg_blur -> FloatingCameraService.BG_MODE_BLUR
                R.id.rb_bg_replace -> FloatingCameraService.BG_MODE_REPLACE
                else -> FloatingCameraService.BG_MODE_OFF
            }

            if (bgMode == FloatingCameraService.BG_MODE_REPLACE && selectedBgUri == null) {
                Toast.makeText(this, getString(R.string.pick_image_first), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val segQuality = when (rgSegQuality.checkedRadioButtonId) {
                R.id.rb_seg_smooth -> FloatingCameraService.SEG_QUALITY_SMOOTH
                else -> FloatingCameraService.SEG_QUALITY_NORMAL
            }

            prefs.edit()
                .putInt(FloatingCameraService.PREF_SIZE, size)
                .putString(FloatingCameraService.PREF_SHAPE, shape)
                .putString(FloatingCameraService.PREF_BG_MODE, bgMode)
                .putString(FloatingCameraService.PREF_BG_IMAGE_URI, selectedBgUri?.toString())
                .putString(FloatingCameraService.PREF_SEG_QUALITY, segQuality)
                .apply()

            if (FloatingCameraService.isRunning) {
                FloatingCameraService.update(this)
            }
            finish()
        }
    }

    private fun applyReplaceUiVisibility(
        isReplace: Boolean,
        hasImage: Boolean,
        btnPickBg: Button,
        ivBgPreview: ImageView,
        tvBgHint: TextView
    ) {
        btnPickBg.visibility = if (isReplace) View.VISIBLE else View.GONE
        ivBgPreview.visibility = if (isReplace && hasImage) View.VISIBLE else View.GONE
        tvBgHint.visibility = if (isReplace && !hasImage) View.VISIBLE else View.GONE
    }

    private fun showBgPreview(uri: Uri) {
        val ivBgPreview = findViewById<ImageView>(R.id.iv_bg_preview)
        val tvBgHint = findViewById<TextView>(R.id.tv_bg_hint)
        runCatching { ivBgPreview.setImageURI(uri) }
        ivBgPreview.visibility = View.VISIBLE
        tvBgHint.visibility = View.GONE
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
