package com.guidedog.app.utils

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

typealias FrameListener = (ByteArray, Int, Int, Int, Int) -> Unit

enum class FrameFormat(val value: Int) {
    NV21(ImageFormat.NV21),
    YUV_420_888(ImageFormat.YUV_420_888),
    JPEG(ImageFormat.JPEG)
}

class CameraManager private constructor(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraExecutor: ExecutorService? = null

    private val isCameraRunning = AtomicBoolean(false)
    private val isReleasing = AtomicBoolean(false)
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private var frameListener: FrameListener? = null
    private var onCameraReady: (() -> Unit)? = null
    private var onCameraError: ((String) -> Unit)? = null

    private val retryCount = AtomicInteger(0)
    private val maxRetries = 3

    var targetResolution: Size? = null
    var frameFormat: FrameFormat = FrameFormat.NV21
    var targetFps: Int = 30

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        listener: FrameListener? = null,
        onReady: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (isCameraRunning.get()) {
            onReady?.invoke()
            return
        }

        if (isReleasing.get()) {
            Log.w(TAG, "相机正在释放中，请稍后重试")
            return
        }

        frameListener = listener
        onCameraReady = onReady
        onCameraError = onError
        retryCount.set(0)

        cameraExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "CameraFrameThread").apply {
                priority = Thread.MAX_PRIORITY
            }
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(lifecycleOwner, previewView)
            } catch (e: Exception) {
                val errorMsg = "相机启动失败: ${e.message}"
                Log.e(TAG, errorMsg, e)
                handleCameraError(errorMsg)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val cameraProvider = cameraProvider ?: run {
            handleCameraError("相机服务未初始化")
            return
        }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0

        val previewBuilder = Preview.Builder()
            .setTargetRotation(rotation)

        targetResolution?.let {
            previewBuilder.setTargetResolution(it)
        }

        preview = previewBuilder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analysisBuilder = ImageAnalysis.Builder()
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

        targetResolution?.let {
            analysisBuilder.setTargetResolution(it)
        }

        imageAnalysis = analysisBuilder.build().also {
            it.setAnalyzer(cameraExecutor!!) { imageProxy ->
                processImage(imageProxy)
            }
        }

        try {
            cameraProvider.unbindAll()

            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )

            isCameraRunning.set(true)
            retryCount.set(0)
            onCameraReady?.invoke()
        } catch (e: Exception) {
            val errorMsg = "相机绑定失败: ${e.message}"
            Log.e(TAG, errorMsg, e)
            handleCameraError(errorMsg)
        }
    }

    private fun processImage(imageProxy: androidx.camera.core.ImageProxy) {
        val listener = frameListener ?: run {
            imageProxy.close()
            return
        }

        if (isReleasing.get() || !isCameraRunning.get()) {
            imageProxy.close()
            return
        }

        try {
            val width = imageProxy.width
            val height = imageProxy.height
            val rotation = imageProxy.imageInfo.rotationDegrees

            val frameBytes = when (frameFormat) {
                FrameFormat.NV21 -> convertYuvToNv21(imageProxy)
                FrameFormat.YUV_420_888 -> convertYuvToByteArray(imageProxy)
                FrameFormat.JPEG -> convertYuvToJpeg(imageProxy)
            }

            if (frameBytes != null) {
                listener(frameBytes, width, height, rotation, frameFormat.value)
            }
        } catch (e: Exception) {
            Log.e(TAG, "帧处理异常", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun convertYuvToByteArray(imageProxy: androidx.camera.core.ImageProxy): ByteArray? {
        val image = imageProxy.image ?: return null
        if (image.format != ImageFormat.YUV_420_888) return null

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        return nv21
    }

    private fun convertYuvToNv21(imageProxy: androidx.camera.core.ImageProxy): ByteArray? {
        val image = imageProxy.image ?: return null
        if (image.format != ImageFormat.YUV_420_888) return null

        val width = imageProxy.width
        val height = imageProxy.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val nv21 = ByteArray(width * height * 3 / 2)

        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            val yLength = minOf(yRowStride, width)
            yBuffer.get(nv21, row * width, yLength)
        }

        val uvHeight = height / 2
        var uvOffset = width * height

        for (row in 0 until uvHeight) {
            val uRowOffset = row * uvRowStride
            val vRowOffset = row * uvRowStride

            for (col in 0 until width / 2) {
                val uIndex = uRowOffset + col * uvPixelStride
                val vIndex = vRowOffset + col * uvPixelStride

                nv21[uvOffset++] = vBuffer[vIndex]
                nv21[uvOffset++] = uBuffer[uIndex]
            }
        }

        return nv21
    }

    private fun convertYuvToJpeg(imageProxy: androidx.camera.core.ImageProxy): ByteArray? {
        val nv21 = convertYuvToNv21(imageProxy) ?: return null
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 80, out)
        return out.toByteArray()
    }

    fun switchCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onSwitched: (() -> Unit)? = null
    ) {
        if (!isCameraRunning.get()) return

        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        stopCamera()

        previewView.postDelayed({
            startCamera(
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                listener = frameListener,
                onReady = {
                    onSwitched?.invoke()
                },
                onError = onCameraError
            )
        }, 300)
    }

    fun toggleTorch(): Boolean {
        val cam = camera ?: return false
        return try {
            val isOn = cam.cameraInfo.torchState.value == TorchState.ON
            cam.cameraControl.enableTorch(!isOn)
            !isOn
        } catch (e: Exception) {
            Log.e(TAG, "闪光灯切换失败", e)
            false
        }
    }

    fun isTorchOn(): Boolean {
        return camera?.cameraInfo?.torchState?.value == TorchState.ON
    }

    fun stopCamera() {
        if (!isCameraRunning.get() && !isReleasing.get()) return

        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "解绑相机失败", e)
        }

        camera = null
        preview = null
        imageAnalysis = null
        isCameraRunning.set(false)
    }

    fun releaseCamera() {
        if (isReleasing.getAndSet(true)) return

        stopCamera()

        frameListener = null
        onCameraReady = null
        onCameraError = null

        cameraExecutor?.let { executor ->
            executor.shutdown()
            try {
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    executor.shutdownNow()
                }
            } catch (e: InterruptedException) {
                executor.shutdownNow()
            }
        }
        cameraExecutor = null

        cameraProvider = null
        isReleasing.set(false)
    }

    private fun handleCameraError(errorMsg: String) {
        Log.e(TAG, errorMsg)

        if (retryCount.getAndIncrement() < maxRetries) {
            Log.w(TAG, "相机启动重试 ${retryCount.get()}/$maxRetries")
            try {
                cameraProvider?.unbindAll()
            } catch (e: Exception) {
                Log.e(TAG, "重试前解绑失败", e)
            }
        } else {
            onCameraError?.invoke(errorMsg)
            releaseCamera()
        }
    }

    fun isRunning(): Boolean = isCameraRunning.get()

    fun getLensFacing(): Int = lensFacing

    fun setFrameListener(listener: FrameListener?) {
        frameListener = listener
    }

    fun getCameraInfo(): CameraInfo? {
        val cam = camera ?: return null
        return CameraInfo(
            isRunning = isCameraRunning.get(),
            lensFacing = lensFacing,
            isTorchOn = isTorchOn(),
            resolution = targetResolution?.toString() ?: "自动"
        )
    }

    data class CameraInfo(
        val isRunning: Boolean,
        val lensFacing: Int,
        val isTorchOn: Boolean,
        val resolution: String
    )

    companion object {
        private const val TAG = "CameraManager"

        @Volatile
        private var INSTANCE: CameraManager? = null

        fun getInstance(context: Context): CameraManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CameraManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
