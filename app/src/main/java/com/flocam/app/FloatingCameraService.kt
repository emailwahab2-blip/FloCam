package com.flocam.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.util.concurrent.Executors

class FloatingCameraService : LifecycleService() {

    companion object {
        var isRunning = false

        const val CHANNEL_ID = "flocam_channel"
        const val NOTIFICATION_ID = 1
        const val PREF_NAME = "flocam_prefs"
        const val PREF_SHAPE = "shape"
        const val PREF_SIZE = "size"
        const val PREF_BG_MODE = "bg_mode"
        const val PREF_BG_IMAGE_URI = "bg_image_uri"
        const val BG_MODE_OFF = "off"
        const val BG_MODE_BLUR = "blur"
        const val BG_MODE_REPLACE = "replace"
        const val SHAPE_CIRCLE = "circle"
        const val SHAPE_SQUARE = "square"
        const val DEFAULT_SIZE_DP = 200

        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE = "ACTION_UPDATE"

        const val PREF_SAVED_X = "saved_x"
        const val PREF_SAVED_Y = "saved_y"

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

    // Core views
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var previewView: PreviewView
    private lateinit var prefs: SharedPreferences
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var compositeOverlay: ImageView? = null

    // Camera
    private var cameraProvider: ProcessCameraProvider? = null
    private var useFrontCamera = true

    // Background mode state
    @Volatile private var bgMode = BG_MODE_OFF
    @Volatile private var segmentationActive = false
    @Volatile private var customBgBitmap: Bitmap? = null

    // Cached scaled background (avoid rescaling every frame)
    private var scaledBgCache: Bitmap? = null
    private var scaledBgCacheW = -1
    private var scaledBgCacheH = -1

    // ML Kit segmenter
    private var segmenter: com.google.mlkit.vision.segmentation.Segmenter? = null

    // RenderScript for blur (API ≤ 30)
    @Suppress("DEPRECATION")
    private var rs: android.renderscript.RenderScript? = null
    @Suppress("DEPRECATION")
    private var rsBlurScript: android.renderscript.ScriptIntrinsicBlur? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    // Fullscreen state
    private var isFullscreen = false

    // Drag state
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
        bgMode = prefs.getString(PREF_BG_MODE, BG_MODE_OFF) ?: BG_MODE_OFF
        if (bgMode != BG_MODE_OFF) {
            initSegmenter()
            if (bgMode == BG_MODE_BLUR) initRenderScript()
            if (bgMode == BG_MODE_REPLACE) {
                customBgBitmap = loadCustomBackground(prefs.getString(PREF_BG_IMAGE_URI, null))
            }
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        createFloatingView()
        startCamera()
    }

    // ── Segmenter / RenderScript lifecycle ───────────────────────────────────

    private fun initSegmenter() {
        segmenter?.close()
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .enableRawSizeMask()
            .build()
        segmenter = Segmentation.getClient(options)
    }

    @Suppress("DEPRECATION")
    private fun initRenderScript() {
        rs?.destroy()
        rs = android.renderscript.RenderScript.create(this)
        rsBlurScript = android.renderscript.ScriptIntrinsicBlur.create(
            rs, android.renderscript.Element.U8_4(rs!!)
        )
        rsBlurScript!!.setRadius(20f)
    }

    @Suppress("DEPRECATION")
    private fun destroyRenderScript() {
        rs?.destroy()
        rs = null
        rsBlurScript = null
    }

    // ── Notification ─────────────────────────────────────────────────────────

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
            this, 0, Intent(this, MainActivity::class.java),
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

    // ── Floating view ─────────────────────────────────────────────────────────

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
            x = 50; y = 150
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_camera, null)
        previewView = floatingView.findViewById(R.id.preview_view)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        compositeOverlay = floatingView.findViewById(R.id.composite_overlay)

        applyShape(shape)
        floatingView.setOnTouchListener { _, event -> handleTouch(event) }

        floatingView.findViewById<ImageButton>(R.id.btn_close).setOnClickListener { stopSelf() }
        floatingView.findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
        floatingView.findViewById<ImageButton>(R.id.btn_flip).setOnClickListener {
            useFrontCamera = !useFrontCamera
            cameraProvider?.let { bindCamera(it) }
        }
        floatingView.findViewById<ImageButton>(R.id.btn_fullscreen).setOnClickListener {
            if (isFullscreen) exitFullscreen() else enterFullscreen()
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
        container.invalidateOutline()
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x; initialY = layoutParams.y
                initialTouchX = event.rawX; initialTouchY = event.rawY
                isMoving = false; true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isFullscreen) return true
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (dx * dx + dy * dy > 25) isMoving = true
                layoutParams.x = initialX + dx; layoutParams.y = initialY + dy
                windowManager.updateViewLayout(floatingView, layoutParams); true
            }
            MotionEvent.ACTION_UP -> { if (!isMoving) toggleControls(); true }
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

    // ── Camera ────────────────────────────────────────────────────────────────

    private fun startCamera() {
        val cpFuture = ProcessCameraProvider.getInstance(this)
        cpFuture.addListener({
            cameraProvider = cpFuture.get()
            cameraProvider?.let { bindCamera(it) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        // Mirror overlay for front camera, matching PreviewView behaviour
        compositeOverlay?.scaleX = if (useFrontCamera) -1f else 1f

        clearSegmentationOverlay()

        runCatching {
            provider.unbindAll()
            if (bgMode == BG_MODE_OFF) {
                provider.bindToLifecycle(this, selector, preview)
            } else {
                val analysis = buildSegmentationAnalysis()
                segmentationActive = true
                provider.bindToLifecycle(this, selector, preview, analysis)
            }
        }.onFailure {
            if (useFrontCamera) {
                useFrontCamera = false
                compositeOverlay?.scaleX = 1f
                runCatching {
                    provider.unbindAll()
                    val preview2 = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    if (bgMode == BG_MODE_OFF) {
                        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview2)
                    } else {
                        val analysis = buildSegmentationAnalysis()
                        segmentationActive = true
                        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview2, analysis)
                    }
                }
            }
        }
    }

    // ── Segmentation analysis ─────────────────────────────────────────────────

    private fun buildSegmentationAnalysis(): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    processFrame(imageProxy)
                }
            }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val rotation = imageProxy.imageInfo.rotationDegrees

        // Convert RGBA plane to Bitmap
        val frameBitmap = Bitmap.createBitmap(
            imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
        )
        imageProxy.planes[0].buffer.rewind()
        frameBitmap.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
        imageProxy.close()

        if (!segmentationActive) { frameBitmap.recycle(); return }

        try {
            // rotation metadata tells ML Kit orientation; enableRawSizeMask() makes
            // mask dimensions match the raw (non-rotated) bitmap dimensions
            val inputImage = InputImage.fromBitmap(frameBitmap, rotation)
            val mask = Tasks.await(segmenter!!.process(inputImage)) as SegmentationMask

            if (!segmentationActive) { frameBitmap.recycle(); return }

            val composited = compositeBitmap(frameBitmap, mask)
            frameBitmap.recycle()

            // Rotate to display orientation
            val displayBitmap = rotateBitmap(composited, rotation)
            if (displayBitmap !== composited) composited.recycle()

            if (!segmentationActive) { displayBitmap.recycle(); return }

            Handler(Looper.getMainLooper()).post {
                if (segmentationActive) {
                    compositeOverlay?.setImageBitmap(displayBitmap)
                    compositeOverlay?.visibility = View.VISIBLE
                } else {
                    displayBitmap.recycle()
                }
            }
        } catch (e: Exception) {
            frameBitmap.recycle()
        }
    }

    // ── Compositing ───────────────────────────────────────────────────────────

    private fun compositeBitmap(frame: Bitmap, mask: SegmentationMask): Bitmap {
        val w = frame.width
        val h = frame.height
        val maskW = mask.width
        val maskH = mask.height

        // Read mask into float array — buffer is ByteBuffer, each float = 4 bytes
        val maskBuf = mask.buffer
        maskBuf.rewind()
        val maskData = FloatArray(maskBuf.remaining() / 4)
        maskBuf.asFloatBuffer().get(maskData)

        // Original frame pixels
        val framePixels = IntArray(w * h)
        frame.getPixels(framePixels, 0, w, 0, 0, w, h)

        // Background pixels (blurred frame or custom image)
        val bgPixels: IntArray = when (bgMode) {
            BG_MODE_BLUR -> {
                val blurred = blurBitmap(frame)
                val px = IntArray(w * h)
                blurred.getPixels(px, 0, w, 0, 0, w, h)
                blurred.recycle()
                px
            }
            BG_MODE_REPLACE -> {
                val bg = getOrScaleBg(w, h) ?: return frame.copy(frame.config, false)
                val px = IntArray(w * h)
                bg.getPixels(px, 0, w, 0, 0, w, h)
                px
            }
            else -> return frame.copy(frame.config, false)
        }

        // Composite: foreground pixels where mask confidence > 0.5, else background
        val exact = (maskW == w && maskH == h)
        val scaleX = if (exact) 1f else maskW.toFloat() / w
        val scaleY = if (exact) 1f else maskH.toFloat() / h

        val resultPixels = IntArray(w * h)
        for (i in resultPixels.indices) {
            val confidence = if (exact) {
                maskData[i]
            } else {
                val mx = (i % w * scaleX).toInt().coerceIn(0, maskW - 1)
                val my = (i / w * scaleY).toInt().coerceIn(0, maskH - 1)
                maskData[my * maskW + mx]
            }
            resultPixels[i] = if (confidence > 0.5f) framePixels[i] else bgPixels[i]
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(resultPixels, 0, w, 0, 0, w, h)
        return result
    }

    // ── Blur helper (RenderScript) ────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun blurBitmap(source: Bitmap): Bitmap {
        val script = rsBlurScript
        val rsCtx = rs
        if (script == null || rsCtx == null) return source.copy(Bitmap.Config.ARGB_8888, false)

        val blurred = source.copy(Bitmap.Config.ARGB_8888, true)
        val input = android.renderscript.Allocation.createFromBitmap(rsCtx, blurred)
        val output = android.renderscript.Allocation.createTyped(rsCtx, input.type)
        script.setInput(input)
        script.forEach(output)
        output.copyTo(blurred)
        input.destroy()
        output.destroy()
        return blurred
    }

    // ── Background image helpers ──────────────────────────────────────────────

    /** Returns a cached scaled copy of the custom background at (w × h). */
    private fun getOrScaleBg(w: Int, h: Int): Bitmap? {
        val src = customBgBitmap ?: return null
        if (scaledBgCache != null && scaledBgCacheW == w && scaledBgCacheH == h) {
            return scaledBgCache
        }
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        scaledBgCache?.recycle()
        scaledBgCache = scaled
        scaledBgCacheW = w
        scaledBgCacheH = h
        return scaled
    }

    private fun loadCustomBackground(uriStr: String?): Bitmap? {
        if (uriStr == null) return null
        return try {
            val uri = Uri.parse(uriStr)
            val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(contentResolver, uri)
                ) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
            // Clamp to 1920 px on longest side to limit memory usage
            val maxDim = 1920
            if (raw.width > maxDim || raw.height > maxDim) {
                val scale = maxDim.toFloat() / maxOf(raw.width, raw.height)
                Bitmap.createScaledBitmap(
                    raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true
                ).also { raw.recycle() }
            } else raw
        } catch (e: Exception) {
            null
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // ── Overlay clear ─────────────────────────────────────────────────────────

    private fun clearSegmentationOverlay() {
        segmentationActive = false
        compositeOverlay?.post {
            compositeOverlay?.visibility = View.GONE
            compositeOverlay?.setImageBitmap(null)
        }
        // Recycle the scaled cache on the analysis executor thread so we don't
        // race with an in-progress frame that might be referencing it
        analysisExecutor.execute {
            scaledBgCache?.recycle()
            scaledBgCache = null
            scaledBgCacheW = -1
            scaledBgCacheH = -1
        }
    }

    // ── Fullscreen ────────────────────────────────────────────────────────────

    private fun enterFullscreen() {
        prefs.edit()
            .putInt(PREF_SAVED_X, layoutParams.x)
            .putInt(PREF_SAVED_Y, layoutParams.y)
            .apply()
        applyShape(SHAPE_SQUARE)
        animateTransition {
            layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.x = 0
            layoutParams.y = 0
            @Suppress("DEPRECATION")
            layoutParams.flags = layoutParams.flags or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            windowManager.updateViewLayout(floatingView, layoutParams)
            isFullscreen = true
            floatingView.findViewById<ImageButton>(R.id.btn_fullscreen)
                .setImageResource(R.drawable.ic_fullscreen_exit)
        }
    }

    private fun exitFullscreen() {
        val sizePx = dpToPx(prefs.getInt(PREF_SIZE, DEFAULT_SIZE_DP))
        animateTransition {
            layoutParams.width = sizePx
            layoutParams.height = sizePx
            layoutParams.x = prefs.getInt(PREF_SAVED_X, 50)
            layoutParams.y = prefs.getInt(PREF_SAVED_Y, 150)
            @Suppress("DEPRECATION")
            layoutParams.flags = layoutParams.flags and
                WindowManager.LayoutParams.FLAG_FULLSCREEN.inv() and
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN.inv()
            windowManager.updateViewLayout(floatingView, layoutParams)
            isFullscreen = false
            floatingView.findViewById<ImageButton>(R.id.btn_fullscreen)
                .setImageResource(R.drawable.ic_fullscreen)
            val shape = prefs.getString(PREF_SHAPE, SHAPE_CIRCLE) ?: SHAPE_CIRCLE
            applyShape(shape)
        }
    }

    private fun animateTransition(midAction: () -> Unit) {
        floatingView.animate()
            .alpha(0f).scaleX(0.85f).scaleY(0.85f)
            .setDuration(120)
            .withEndAction {
                midAction()
                floatingView.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(150)
                    .start()
            }
            .start()
    }

    // ── Settings update ───────────────────────────────────────────────────────

    private fun updateAppearance() {
        if (!::floatingView.isInitialized) return

        // Size + shape (skip layout update while fullscreen — restored on exit)
        val sizePx = dpToPx(prefs.getInt(PREF_SIZE, DEFAULT_SIZE_DP))
        val shape = prefs.getString(PREF_SHAPE, SHAPE_CIRCLE) ?: SHAPE_CIRCLE
        if (!isFullscreen) {
            layoutParams.width = sizePx; layoutParams.height = sizePx
            windowManager.updateViewLayout(floatingView, layoutParams)
            applyShape(shape)
            floatingView.requestLayout()
        }

        // Background mode – tear down old resources, build new ones
        val newMode = prefs.getString(PREF_BG_MODE, BG_MODE_OFF) ?: BG_MODE_OFF
        bgMode = newMode

        segmenter?.close(); segmenter = null
        destroyRenderScript()
        customBgBitmap?.recycle(); customBgBitmap = null

        if (newMode != BG_MODE_OFF) {
            initSegmenter()
            if (newMode == BG_MODE_BLUR) initRenderScript()
            if (newMode == BG_MODE_REPLACE) {
                customBgBitmap = loadCustomBackground(prefs.getString(PREF_BG_IMAGE_URI, null))
            }
        }

        cameraProvider?.let { bindCamera(it) }
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────

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
        segmentationActive = false
        cameraProvider?.unbindAll()
        segmenter?.close()
        @Suppress("DEPRECATION") rs?.destroy()
        customBgBitmap?.recycle()
        scaledBgCache?.recycle()
        analysisExecutor.shutdown()
        if (::floatingView.isInitialized) {
            runCatching { windowManager.removeView(floatingView) }
        }
        super.onDestroy()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
