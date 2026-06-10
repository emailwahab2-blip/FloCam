package com.flocam.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService

class FloatingCameraService : LifecycleService() {

    companion object {
        var isRunning = false

        const val CHANNEL_ID = "flocam_channel"
        const val NOTIFICATION_ID = 1
        const val PREF_NAME = "flocam_prefs"
        const val PREF_SHAPE = "shape"
        const val PREF_SIZE = "size"
        const val SHAPE_CIRCLE = "circle"
        const val SHAPE_SQUARE = "square"
        const val DEFAULT_SIZE_DP = 200

        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE = "ACTION_UPDATE"

        fun start(context: Context) {
            val intent = Intent(context, FloatingCameraService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingCameraService::class.java))
        }

        fun update(context: Context) {
            context.startService(Intent(context, FloatingCameraService::class.java).apply {
                action = ACTION_UPDATE
            })
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var previewView: PreviewView
    private lateinit var prefs: SharedPreferences
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var cameraProvider: ProcessCameraProvider? = null
    private var useFrontCamera = true

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isMoving = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        createFloatingView()
        startCamera()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "FloCam Service", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Floating camera overlay" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopPending = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingCameraService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_camera_notify)
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_close_notify, getString(R.string.stop_camera), stopPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingView() {
        val sizePx = dpToPx(prefs.getInt(PREF_SIZE, DEFAULT_SIZE_DP))
        val shape = prefs.getString(PREF_SHAPE, SHAPE_CIRCLE) ?: SHAPE_CIRCLE

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        layoutParams = WindowManager.LayoutParams(
            sizePx, sizePx, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 150
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_camera, null)
        previewView = floatingView.findViewById(R.id.preview_view)

        applyShape(shape)

        floatingView.setOnTouchListener { _, event -> handleTouch(event) }

        floatingView.findViewById<ImageButton>(R.id.btn_close).setOnClickListener {
            stopSelf()
        }
        floatingView.findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
        floatingView.findViewById<ImageButton>(R.id.btn_flip).setOnClickListener {
            useFrontCamera = !useFrontCamera
            cameraProvider?.let { bindCamera(it) }
        }

        windowManager.addView(floatingView, layoutParams)
    }

    private fun applyShape(shape: String) {
        val container = floatingView.findViewById<FrameLayout>(R.id.camera_container)
        if (shape == SHAPE_CIRCLE) {
            container.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            container.clipToOutline = true
        } else {
            container.outlineProvider = ViewOutlineProvider.BOUNDS
            container.clipToOutline = false
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isMoving = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (dx * dx + dy * dy > 25) isMoving = true
                layoutParams.x = initialX + dx
                layoutParams.y = initialY + dy
                windowManager.updateViewLayout(floatingView, layoutParams)
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!isMoving) toggleControls()
                true
            }
            else -> false
        }
    }

    private fun toggleControls() {
        val overlay = floatingView.findViewById<View>(R.id.controls_overlay)
        if (overlay.visibility == View.VISIBLE) {
            overlay.visibility = View.GONE
        } else {
            overlay.visibility = View.VISIBLE
            overlay.postDelayed({ overlay.visibility = View.GONE }, 3000)
        }
    }

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).also { future ->
            future.addListener({
                cameraProvider = future.get()
                cameraProvider?.let { bindCamera(it) }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview)
        }.onFailure { e ->
            // Fallback to back camera if front not available
            if (useFrontCamera) {
                useFrontCamera = false
                runCatching {
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                }
            }
        }
    }

    private fun updateAppearance() {
        if (!::floatingView.isInitialized) return
        val sizePx = dpToPx(prefs.getInt(PREF_SIZE, DEFAULT_SIZE_DP))
        val shape = prefs.getString(PREF_SHAPE, SHAPE_CIRCLE) ?: SHAPE_CIRCLE
        layoutParams.width = sizePx
        layoutParams.height = sizePx
        windowManager.updateViewLayout(floatingView, layoutParams)
        applyShape(shape)
        floatingView.requestLayout()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_UPDATE -> updateAppearance()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        cameraProvider?.unbindAll()
        if (::floatingView.isInitialized) {
            runCatching { windowManager.removeView(floatingView) }
        }
        super.onDestroy()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
