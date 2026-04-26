package com.ailaohu.service.face

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FaceDetectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "FaceDetectionManager"
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private val cameraOpenCloseLock = Semaphore(1)

    private var isStarted = false
    private var faceDetectionCallback: FaceDetectionCallback? = null

    private var lastFaceDetectedTime = 0L
    private var faceDetected = false

    interface FaceDetectionCallback {
        fun onFaceDetected()
        fun onFaceLost()
    }

    fun setCallback(callback: FaceDetectionCallback) {
        this.faceDetectionCallback = callback
    }

    fun start() {
        if (isStarted) return
        
        Log.d(TAG, "开始启动前置摄像头人脸检测")
        startBackgroundThread()
        
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = getFrontCameraId(cameraManager)
            if (cameraId == null) {
                Log.w(TAG, "未找到前置摄像头")
                return
            }
            
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("等待相机打开超时")
            }
            
            if (context.checkSelfPermission(android.Manifest.permission.CAMERA) 
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "相机权限未授予")
                cameraOpenCloseLock.release()
                return
            }
            
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    Log.d(TAG, "相机断开连接")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    Log.e(TAG, "相机错误: $error")
                }
            }, backgroundHandler)
            
            isStarted = true
            Log.d(TAG, "前置摄像头人脸检测已启动")
            
        } catch (e: Exception) {
            Log.e(TAG, "启动前置摄像头失败", e)
            cameraOpenCloseLock.release()
        }
    }

    fun stop() {
        if (!isStarted) return
        
        Log.d(TAG, "停止前置摄像头人脸检测")
        
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "停止相机时被中断", e)
        } catch (e: Exception) {
            Log.e(TAG, "停止相机时出错", e)
        } finally {
            cameraOpenCloseLock.release()
            stopBackgroundThread()
            isStarted = false
            faceDetected = false
        }
    }

    private fun getFrontCameraId(cameraManager: CameraManager): String? {
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return cameraId
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun createCaptureSession() {
        try {
            val camera = cameraDevice ?: return
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            
            val characteristics = cameraManager.getCameraCharacteristics(camera.id)
            val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            
            val sizes = streamConfigMap?.getOutputSizes(Surface::class.java)
            val previewSize = sizes?.minByOrNull { it.width * it.height } ?: return
            
            imageReader = ImageReader.newInstance(
                previewSize.width,
                previewSize.height,
                ImageFormat.YUV_420_888,
                2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        processImage(image)
                        image.close()
                    }
                }, backgroundHandler)
            }
            
            val surfaces = listOf(imageReader!!.surface)
            
            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startPreview()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "相机配置失败")
                    }
                },
                backgroundHandler
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "创建捕获会话失败", e)
        }
    }

    private fun startPreview() {
        try {
            val camera = cameraDevice ?: return
            val session = captureSession ?: return
            val reader = imageReader ?: return
            
            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(reader.surface)
            
            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            
            session.setRepeatingRequest(
                captureRequestBuilder.build(),
                null,
                backgroundHandler
            )
            
            Log.d(TAG, "开始预览")
            
        } catch (e: Exception) {
            Log.e(TAG, "开始预览失败", e)
        }
    }

    private fun processImage(image: Image) {
        val currentTime = System.currentTimeMillis()
        
        if (currentTime - lastFaceDetectedTime < 100) {
            return
        }
        lastFaceDetectedTime = currentTime
        
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val ySize = yBuffer.remaining()
        val yData = ByteArray(ySize)
        yBuffer.get(yData)
        
        val width = image.width
        val height = image.height
        
        val isFacePresent = detectFaceSimple(yData, width, height)
        
        if (isFacePresent && !faceDetected) {
            faceDetected = true
            Log.d(TAG, "检测到人脸")
            backgroundHandler?.post {
                faceDetectionCallback?.onFaceDetected()
            }
        } else if (!isFacePresent && faceDetected) {
            faceDetected = false
            Log.d(TAG, "人脸丢失")
            backgroundHandler?.post {
                faceDetectionCallback?.onFaceLost()
            }
        }
    }

    private fun detectFaceSimple(yData: ByteArray, width: Int, height: Int): Boolean {
        val sampleStep = 8
        var brightPixels = 0
        var totalSampled = 0
        
        val centerX = width / 2
        val centerY = height / 2
        val faceRegionRadius = minOf(width, height) / 3
        
        for (y in 0 until height step sampleStep) {
            for (x in 0 until width step sampleStep) {
                val dx = x - centerX
                val dy = y - centerY
                if (dx * dx + dy * dy <= faceRegionRadius * faceRegionRadius) {
                    val index = y * width + x
                    if (index < yData.size) {
                        val luminance = yData[index].toInt() and 0xFF
                        if (luminance in 40..220) {
                            brightPixels++
                        }
                        totalSampled++
                    }
                }
            }
        }
        
        if (totalSampled == 0) return false
        
        val brightRatio = brightPixels.toFloat() / totalSampled
        
        return brightRatio > 0.3f && brightRatio < 0.85f
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "停止后台线程失败", e)
        }
    }

    fun isStarted(): Boolean = isStarted
}
