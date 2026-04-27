package com.ailaohu.service.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.ailaohu.util.Constants
import kotlinx.coroutines.*

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCapture"
        private const val NOTIFICATION_ID = 1001
        private const val VIRTUAL_DISPLAY_NAME = "AILaoHuoCapture"
        private const val ACTION_CAPTURE_SCREEN = "com.ailaohu.action.CAPTURE_SCREEN"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        private var instance: ScreenCaptureService? = null

        fun getRunningInstance(): ScreenCaptureService? = instance

        private var captureCallback: ((android.graphics.Bitmap?) -> Unit)? = null

        fun startCapture(
            context: Context,
            resultCode: Int,
            data: Intent,
            callback: (android.graphics.Bitmap?) -> Unit
        ) {
            captureCallback = callback

            val runningInstance = instance
            if (runningInstance != null && runningInstance.isProjectionSetup) {
                Log.d(TAG, "MediaProjection已就绪，直接截图")
                runningInstance.serviceScope.launch {
                    delay(100)
                    val bitmap = runningInstance.captureScreen()
                    callback(bitmap)
                    captureCallback = null
                }
                return
            }

            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_CAPTURE_SCREEN
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var isProjectionSetup = false
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CAPTURE_SCREEN) {
            startForeground(NOTIFICATION_ID, createNotification())

            if (isProjectionSetup && mediaProjection != null && virtualDisplay != null) {
                Log.d(TAG, "MediaProjection已就绪，直接截图")
                serviceScope.launch {
                    delay(100)
                    val bitmap = captureScreen()
                    captureCallback?.invoke(bitmap)
                    captureCallback = null
                }
                return START_STICKY
            }

            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA)
            }

            if (data == null) {
                stopSelf()
                return START_NOT_STICKY
            }

            try {
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, data)

                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.d(TAG, "MediaProjection stopped")
                        isProjectionSetup = false
                        cleanup()
                        isRunning = false
                    }
                }, null)

                setupVirtualDisplay()
                isProjectionSetup = true
                isRunning = true

                serviceScope.launch {
                    delay(300)
                    val bitmap = captureScreen()
                    captureCallback?.invoke(bitmap)
                    captureCallback = null
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "MediaProjection token已失效，清除缓存: ${e.message}")
                isProjectionSetup = false
                cleanup()
                captureCallback?.invoke(null)
                captureCallback = null
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun setupVirtualDisplay() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME, width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        Log.d(TAG, "VirtualDisplay created: ${width}x${height} @${density}dpi")
    }

    fun captureScreen(): android.graphics.Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = android.graphics.Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding > 0) {
                android.graphics.Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "截屏失败", e)
            null
        } finally {
            image.close()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.CHANNEL_SCREEN_CAPTURE,
                "屏幕采集服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AI沪老需要屏幕采集权限来识别界面元素"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, Constants.CHANNEL_SCREEN_CAPTURE)
                .setContentTitle("AI沪老正在获取屏幕")
                .setContentText("正在分析当前界面")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("AI沪老正在获取屏幕")
                .setContentText("正在分析当前界面")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
        }
    }

    private fun cleanup() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onDestroy() {
        serviceScope.cancel()
        cleanup()
        isRunning = false
        isProjectionSetup = false
        instance = null
        captureCallback = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
