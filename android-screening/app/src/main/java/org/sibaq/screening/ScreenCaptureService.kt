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
import android.os.PowerManager
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
    private var wakeLock: PowerManager.WakeLock? = null

    private var server: LabStreamServer? = null
    @Volatile private var latestJpegFrame: ByteArray? = null

    @Volatile var isStreaming = false
        private set

    var customDeviceName: String = ""
    var masterServerIp: String = ""
    var scaleFactor: Float = 0.4f
    var targetFps: Int = 30
    var jpegQuality: Int = 50

    private val isProcessingFrame = AtomicBoolean(false)
    private var reusableBitmap: Bitmap? = null
    private var reusableCroppedBitmap: Bitmap? = null
    private val reusableBaos = ByteArrayOutputStream(1024 * 64)

    private var frameCount = 0
    private var lastFpsResetTime = System.currentTimeMillis()
    private var currentCalculatedFps = 0
    private var lastIdleFrameTime = 0L

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startBackgroundThread()
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sibaq:ScreenCaptureWakeLock").apply {
                setReferenceCounted(false)
            }
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            val isAuthorized = intent.getBooleanExtra(EXTRA_FORCE_STOP_AUTHORIZED, false)
            if (isAuthorized) {
                // Explicit authorized stop by user/admin via confirmation prompt
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_STREAM_SHOULD_RUN, false)
                    .apply()
                stopStreaming()
                stopSelf()
                return START_NOT_STICKY
            } else {
                // Unauthorized or notification stop attempt: show prompt in MainActivity!
                val promptIntent = Intent(this, MainActivity::class.java).apply {
                    this.action = ACTION_REQUEST_STOP
                    putExtra(EXTRA_PROMPT_STOP, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(promptIntent)
                return START_STICKY
            }
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        customDeviceName = intent?.getStringExtra(EXTRA_CUSTOM_NAME) ?: ""
        masterServerIp = intent?.getStringExtra(EXTRA_MASTER_IP) ?: ""
        scaleFactor = intent?.getFloatExtra(EXTRA_SCALE_FACTOR, 0.4f) ?: 0.4f
        targetFps = intent?.getIntExtra(EXTRA_FPS, 30) ?: 30
        jpegQuality = intent?.getIntExtra(EXTRA_QUALITY, 50) ?: 50

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
        try {
            wakeLock?.acquire(24 * 60 * 60 * 1000L) // Keep CPU active while screening
        } catch (_: Exception) {}

        // Persist that screen streaming should be active across reboots and app life cycles
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STREAM_SHOULD_RUN, true)
            .apply()

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, resultData)?.apply {
            registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val streamShouldRun = prefs.getBoolean(KEY_STREAM_SHOULD_RUN, false)
                    if (streamShouldRun) {
                        // Stream was stopped by system or display event without authorization: recover!
                        val recoverIntent = Intent(this@ScreenCaptureService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra(EXTRA_AUTO_RESUME, true)
                        }
                        startActivity(recoverIntent)
                    } else {
                        stopStreaming()
                    }
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
            masterServerIp = this@ScreenCaptureService.masterServerIp
            frameWidth = scaledWidth
            frameHeight = scaledHeight
            start()
        }

        isStreaming = true
    }

    private fun processLatestImage(reader: ImageReader, width: Int, height: Int) {
        val activeClients = server?.getConnectedClientCount() ?: 0
        val now = System.currentTimeMillis()

        // Battery Power Saver: If 0 viewers connected, drop frame processing to 1 FPS
        if (activeClients == 0 && (now - lastIdleFrameTime < 1000)) {
            val img = reader.acquireLatestImage()
            img?.close()
            return
        }

        // Non-blocking frame drop: if background worker is busy, drop intermediate frames
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

            lastIdleFrameTime = now

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val paddedWidth = width + rowPadding / pixelStride

            // Reuse Bitmap Memory Buffer to eliminate Android Garbage Collection (GC) pauses
            if (reusableBitmap == null || reusableBitmap!!.width != paddedWidth || reusableBitmap!!.height != height) {
                reusableBitmap?.recycle()
                reusableBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            }
            val bmp = reusableBitmap!!
            buffer.rewind()
            bmp.copyPixelsFromBuffer(buffer)

            val finalBmp = if (rowPadding > 0) {
                if (reusableCroppedBitmap == null || reusableCroppedBitmap!!.width != width || reusableCroppedBitmap!!.height != height) {
                    reusableCroppedBitmap?.recycle()
                    reusableCroppedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                }
                val croppedBmp = reusableCroppedBitmap!!
                val canvas = android.graphics.Canvas(croppedBmp)
                canvas.drawBitmap(bmp, 0f, 0f, null)
                croppedBmp
            } else {
                bmp
            }

            reusableBaos.reset()
            finalBmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, reusableBaos)

            latestJpegFrame = reusableBaos.toByteArray()
            server?.notifyNewFrameAvailable()

            // Update FPS stats
            frameCount++
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

    fun updateMasterServerIp(ip: String) {
        masterServerIp = ip
        server?.masterServerIp = ip
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
        reusableBitmap?.recycle()
        reusableBitmap = null
        reusableCroppedBitmap?.recycle()
        reusableCroppedBitmap = null
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val shouldRun = prefs.getBoolean(KEY_STREAM_SHOULD_RUN, false)
        stopStreaming()
        stopBackgroundThread()
        super.onDestroy()
        if (shouldRun) {
            // Process killed unexpectedly while stream should still be active: auto-recover!
            val recoverIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_AUTO_RESUME, true)
            }
            startActivity(recoverIntent)
        }
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
        // Notification "Stop Sharing" button opens MainActivity with confirmation/PIN prompt
        val stopPromptIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_REQUEST_STOP
            putExtra(EXTRA_PROMPT_STOP, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val stopPendingIntent = PendingIntent.getActivity(
            this, 101, stopPromptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
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
        const val ACTION_REQUEST_STOP = "org.sibaq.screening.action.REQUEST_STOP"
        const val EXTRA_FORCE_STOP_AUTHORIZED = "extra_force_stop_authorized"
        const val EXTRA_PROMPT_STOP = "extra_prompt_stop"
        const val EXTRA_AUTO_RESUME = "extra_auto_resume"
        const val PREFS_NAME = "sibaq_settings"
        const val KEY_STREAM_SHOULD_RUN = "stream_should_run"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_CUSTOM_NAME = "extra_custom_name"
        const val EXTRA_MASTER_IP = "extra_master_ip"
        const val EXTRA_SCALE_FACTOR = "extra_scale_factor"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_QUALITY = "extra_quality"
    }
}
