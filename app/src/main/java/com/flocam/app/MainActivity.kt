package com.flocam.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnSettings: Button

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) checkOverlayPermission()
        else tvStatus.text = getString(R.string.camera_permission_denied)
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { checkPermissionsAndStart() }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            startFloatingCamera()
        } else {
            tvStatus.text = getString(R.string.overlay_permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_status)
        btnToggle = findViewById(R.id.btn_toggle)
        btnSettings = findViewById(R.id.btn_settings)

        val tvAboutBody = findViewById<TextView>(R.id.tv_about_body)
        tvAboutBody.text = HtmlCompat.fromHtml(
            getString(R.string.about_body), HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        tvAboutBody.movementMethod = LinkMovementMethod.getInstance()

        btnToggle.setOnClickListener {
            if (FloatingCameraService.isRunning) {
                FloatingCameraService.stop(this)
                updateUI()
            } else {
                checkPermissionsAndStart()
            }
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        if (FloatingCameraService.isRunning) {
            tvStatus.text = getString(R.string.camera_running)
            btnToggle.text = getString(R.string.stop_camera)
        } else {
            tvStatus.text = getString(R.string.camera_stopped)
            btnToggle.text = getString(R.string.start_camera)
        }
    }

    private fun checkPermissionsAndStart() {
        // 1. Camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
            return
        }

        // 2. POST_NOTIFICATIONS (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        // 3. Overlay permission
        checkOverlayPermission()
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.overlay_permission_title)
                .setMessage(R.string.overlay_permission_message)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    overlayPermissionLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            startFloatingCamera()
        }
    }

    private fun startFloatingCamera() {
        FloatingCameraService.start(this)
        updateUI()
        moveTaskToBack(true)
    }
}
