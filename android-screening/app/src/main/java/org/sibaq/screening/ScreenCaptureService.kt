package org.sibaq.screening

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var server: LabStreamServer? = null
    @Volatile private var latestJpegFrame: ByteArray? = null

    @Volatile var isStreaming = false
        private set

    var customDeviceName: String = ""
    var scaleFactor: Float = 0.5f
    var targetFps: Int = 30
    var jpegQuality: Int = 60

    private val isProcessingFrame = AtomicBoolean(false)
    private var frameCount = 0
    private var lastFpsResetTime = System.currentTimeMillis()
    private var currentCalculatedFps = 0

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startBackgroundThread()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopStreaming()
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        customDeviceName = intent?.getStringExtra(EXTRA_CUSTOM_NAME) ?: ""
        scaleFactor = intent?.getFloatExtra(EXTRA_SCALE_FACTOR, 0.5f) ?: 0.5f
        targetFps = intent?.getIntExtra(EXTRA_FPS, 30) ?: 30
        jpegQuality = intent?.getIntExtra(EXTRA_QUALITY, 60) ?: 60

        if (resultCode != 0 && resultData != null) {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            startCapture(resultCode, resultData)
        }

        return START_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, resultData)?.apply {
            registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopStreaming()
                }
            }, backgroundHandler)
        }

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
        }

        val origWidth = metrics.widthPixels
        val origHeight = metrics.heightPixels
        val scaledWidth = (origWidth * scaleFactor).toInt().coerceAtLeast(120)
        val scaledHeight = (origHeight * scaleFactor).toInt().coerceAtLeast(120)

        // Use 3 buffers for smooth non-blocking pipeline
        imageReader = ImageReader.newInstance(scaledWidth, scaledHeight, PixelFormat.RGBA_8888, 3)
        imageReader?.setOnImageAvailableListener({ reader ->
            processLatestImage(reader, scaledWidth, scaledHeight)
        }, backgroundHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SIBAQLabScreen",
            scaledWidth,
            scaledHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            backgroundHandler
        )

        // Start embedded HTTP & MJPEG server
        server = LabStreamServer(
            context = this,
            port = 8080,
            frameProvider = { latestJpegFrame }
        ).apply {
            customDeviceName = this@ScreenCaptureService.customDeviceName
            frameWidth = scaledWidth
            frameHeight = scaledHeight
            start()
        }

        isStreaming = true
    }

    private fun processLatestImage(reader: ImageReader, width: Int, height: Int) {
        // If background worker is busy, drop frame immediately to maintain ultra-low latency & 30 FPS
        if (!isProcessingFrame.compareAndSet(false, true)) {
            val img = reader.acquireLatestImage()
            img?.close()
            return
        }

        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) {
                isProcessingFrame.set(false)
                return
            }

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            var bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()
                bitmap = cropped
            }

            val baos = ByteArrayOutputStream(1024 * 64)
            bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
            bitmap.recycle()

            latestJpegFrame = baos.toByteArray()
            server?.notifyNewFrameAvailable()

            // Update FPS stats
            frameCount++
            val now = System.currentTimeMillis()
            if (now - lastFpsResetTime >= 1000) {
                currentCalculatedFps = frameCount
                frameCount = 0
                lastFpsResetTime = now
                server?.currentFps = currentCalculatedFps
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image?.close()
            isProcessingFrame.set(false)
        }
    }

    fun getServerPort(): Int = 8080

    fun getConnectedClientCount(): Int = server?.getConnectedClientCount() ?: 0

    fun getCurrentFps(): Int = currentCalculatedFps

    fun updateCustomDeviceName(name: String) {
        customDeviceName = name
        server?.customDeviceName = name
    }

    private fun stopStreaming() {
        isStreaming = false
        server?.stop()
        server = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopStreaming()
        stopBackgroundThread()
        super.onDestroy()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("ScreenCaptureBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (_: Exception) {}
        backgroundThread = null
        backgroundHandler = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SIBAQ Screen Monitor Stream",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows notification when Android screen is being shared to Computer Lab Admin"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val activityIntent = Intent(this, MainActivity::class.java)
        val activityPendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (customDeviceName.isNotBlank()) {
            "$customDeviceName's Screen Active"
        } else {
            "SIBAQ Lab Screen Stream Active"
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(title)
            .setContentText("Screen is live-streaming to Computer Lab Admin")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(activityPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Sharing", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "sibaq_screen_capture_channel"
        const val NOTIFICATION_ID = 8801
        const val ACTION_STOP = "org.sibaq.screening.action.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_CUSTOM_NAME = "extra_custom_name"
        const val EXTRA_SCALE_FACTOR = "extra_scale_factor"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_QUALITY = "extra_quality"
    }
}
