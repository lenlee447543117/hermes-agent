package com.ailaohu.service.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.ailaohu.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionManager: ScreenCapturePermissionManager
) {

    companion object {
        private const val TAG = "ScreenCaptureManager"
    }

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var savedResultCode: Int? = null
    private var savedData: Intent? = null

    fun savePermissionData(resultCode: Int, data: Intent) {
        savedResultCode = resultCode
        savedData = data
        managerScope.launch {
            permissionManager.setPermissionGranted(resultCode)
        }
    }

    fun hasPermission(): Boolean {
        return permissionManager.hasPermissionSync() && savedResultCode != null && savedData != null
    }

    fun needsReAuthorization(): Boolean {
        return permissionManager.hasPermissionSync() && (savedResultCode == null || savedData == null)
    }

    suspend fun captureScreen(): Bitmap? {
        if (!hasPermission()) {
            Log.w(TAG, "没有屏幕捕获权限")
            return null
        }

        val runningInstance = ScreenCaptureService.getRunningInstance()
        if (runningInstance != null && runningInstance.isProjectionSetup) {
            return try {
                withContext(Dispatchers.IO) {
                    delay(100)
                    runningInstance.captureScreen()
                }
            } catch (e: Exception) {
                Log.e(TAG, "直接截图失败: ${e.message}")
                captureScreenViaService()
            }
        }

        return captureScreenViaService()
    }

    private suspend fun captureScreenViaService(): Bitmap? {
        return try {
            suspendCoroutine { continuation ->
                val resultCode = savedResultCode ?: return@suspendCoroutine
                val data = savedData ?: return@suspendCoroutine

                ScreenCaptureService.startCapture(
                    context = context,
                    resultCode = resultCode,
                    data = data,
                    callback = { bitmap ->
                        if (bitmap == null) {
                            Log.w(TAG, "截图返回null，清除缓存的MediaProjection token")
                            clearPermission()
                        }
                        continuation.resume(bitmap)
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureScreen异常: ${e.message}", e)
            clearPermission()
            null
        }
    }

    fun compressBitmap(bitmap: Bitmap, targetWidth: Int): Bitmap {
        val originalWidth = bitmap.width
        if (originalWidth <= targetWidth) return bitmap
        val scale = targetWidth.toFloat() / originalWidth
        val targetHeight = (bitmap.height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 80): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    suspend fun getScreenshotBase64(): String? {
        if (!hasPermission()) {
            Log.w(TAG, "屏幕捕获权限不可用")
            return null
        }
        val rawBitmap = captureScreen() ?: run {
            Log.w(TAG, "屏幕截图失败")
            return null
        }
        val compressed = compressBitmap(rawBitmap, Constants.SCREENSHOT_TARGET_WIDTH)
        return try {
            bitmapToBase64(compressed)
        } catch (e: Exception) {
            Log.e(TAG, "Base64编码失败: ${e.message}", e)
            null
        }
    }

    fun clearPermission() {
        savedResultCode = null
        savedData = null
        managerScope.launch {
            permissionManager.clearPermission()
        }
    }
}
