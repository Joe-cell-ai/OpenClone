package org.openclone.app

import android.app.Activity
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
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager

/** Captures only after Android's MediaProjection consent UI grants access. */
class RemoteScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var encoderThread: HandlerThread? = null
    private val mainHandler = Handler(mainLooper)
    private val sessionExpiry = Runnable { stopCapture(); stopSelf() }
    private var captureDensityDpi: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val consentData = intent?.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || consentData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val stopIntent = PendingIntent.getService(
            this, NOTIFICATION_ID,
            Intent(this, RemoteScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("OpenClone screen capture is active")
            .setContentText("Your screen is being captured. Tap Stop to end immediately.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Stop sharing", stopIntent
            ).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)

        return try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val activeProjection = manager.getMediaProjection(resultCode, consentData)
                ?: error("Android did not grant screen capture.")
            projection = activeProjection
            activeProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onCapturedContentResize(width: Int, height: Int) {
                    resizeCapture(width, height)
                }
                override fun onStop() {
                    // Android has already stopped this projection; do not call stop() again.
                    projection = null
                    stopCapture(stopProjection = false)
                    stopSelf()
                }
            }, android.os.Handler(mainLooper))

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            captureDensityDpi = metrics.densityDpi
            val dimensions = ScreenCaptureBounds.fit(metrics.widthPixels, metrics.heightPixels)
            val reader = ImageReader.newInstance(dimensions.first, dimensions.second, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            encoderThread = HandlerThread("OpenCloneFrameEncoder").apply { start() }
            reader.setOnImageAvailableListener({ source ->
                // Drain and release frames. Network streaming is intentionally not enabled in this stage.
                source.acquireLatestImage()?.use { frame ->
                    runCatching { RemoteScreenFrameStore.publish(frame) }
                        .onFailure { android.util.Log.w("OpenCloneScreen", "Could not encode screen frame", it) }
                }
            }, Handler(encoderThread!!.looper))
            virtualDisplay = activeProjection.createVirtualDisplay(
                "OpenCloneRemoteScreen", dimensions.first, dimensions.second, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
            ) ?: error("Android could not create the screen capture display.")
            getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, true).apply()
            mainHandler.removeCallbacks(sessionExpiry)
            mainHandler.postDelayed(sessionExpiry, MAX_SESSION_MILLIS)
            START_NOT_STICKY
        } catch (error: Exception) {
            android.util.Log.e("OpenCloneScreen", "Could not start consented screen capture", error)
            stopCapture()
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun resizeCapture(width: Int, height: Int) {
        val reader = imageReader ?: return
        val display = virtualDisplay ?: return
        runCatching {
            val size = ScreenCaptureBounds.fit(width, height)
            reader.setOnImageAvailableListener(null, null)
            reader.surface.let { reader.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { frame ->
                    runCatching { RemoteScreenFrameStore.publish(frame) }
                        .onFailure { android.util.Log.w("OpenCloneScreen", "Could not encode resized screen frame", it) }
                }
            }, encoderThread?.looper?.let(::Handler)) }
            display.resize(size.first, size.second, captureDensityDpi)
            reader.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { frame ->
                    runCatching { RemoteScreenFrameStore.publish(frame) }
                        .onFailure { android.util.Log.w("OpenCloneScreen", "Could not encode resized screen frame", it) }
                }
            }, encoderThread?.looper?.let(::Handler))
        }.onFailure { android.util.Log.w("OpenCloneScreen", "Could not resize capture surface", it) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Screen sharing session", NotificationManager.IMPORTANCE_LOW
            ))
        }
    }

    private fun stopCapture(stopProjection: Boolean = true) {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        encoderThread?.quitSafely()
        encoderThread = null
        mainHandler.removeCallbacks(sessionExpiry)
        RemoteScreenFrameStore.clear()
        val active = projection
        projection = null
        if (stopProjection) active?.stop()
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key) as? T
        }

    companion object {
        const val ACTION_STOP = "org.openclone.app.REMOTE_SCREEN_STOP"
        const val EXTRA_RESULT_CODE = "screen_capture_result_code"
        const val EXTRA_RESULT_DATA = "screen_capture_result_data"
        const val PREFERENCES = "openclone_remote_screen"
        const val KEY_ACTIVE = "capture_active"
        private const val CHANNEL_ID = "openclone_remote_screen"
        private const val NOTIFICATION_ID = 38474
        private const val MAX_SESSION_MILLIS = 15L * 60L * 1000L
    }
}

/** Bounds capture dimensions to reduce memory pressure while preserving aspect ratio. */
internal object ScreenCaptureBounds {
    fun fit(width: Int, height: Int, maxDimension: Int = 1920): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Screen dimensions must be positive." }
        require(maxDimension in 320..4096) { "Capture limit is outside the safe range." }
        val scale = minOf(1.0, maxDimension.toDouble() / maxOf(width, height))
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }
}


/** Holds only the latest consented frame; callers must already pass TLS + HMAC authorization. */
internal object RemoteScreenFrameStore {
    private const val JPEG_QUALITY = 65
    @Volatile private var latestFrame: ByteArray? = null

    @Synchronized fun publish(image: android.media.Image) {
        val plane = image.planes.firstOrNull() ?: return
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * rowStride
            for (x in 0 until width) {
                val offset = row + x * pixelStride
                val red = buffer.get(offset).toInt() and 0xff
                val green = buffer.get(offset + 1).toInt() and 0xff
                val blue = buffer.get(offset + 2).toInt() and 0xff
                val alpha = buffer.get(offset + 3).toInt() and 0xff
                pixels[y * width + x] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            val output = java.io.ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) { "JPEG encoding failed." }
            latestFrame = output.toByteArray().also { require(it.size <= 4 * 1024 * 1024) { "Encoded frame exceeds limit." } }
        } finally {
            bitmap.recycle()
            pixels.fill(0)
        }
    }

    @Synchronized fun latest(): ByteArray? = latestFrame?.copyOf()
    @Synchronized fun clear() { latestFrame?.fill(0); latestFrame = null }
}
