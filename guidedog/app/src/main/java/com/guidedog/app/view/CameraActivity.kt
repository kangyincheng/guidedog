package com.guidedog.app.view

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.guidedog.app.R
import com.guidedog.app.databinding.ActivityCameraBinding
import com.guidedog.app.utils.AccessibilityUtils
import com.guidedog.app.utils.CameraManager
import com.guidedog.app.utils.FrameFormat
import com.guidedog.app.utils.GestureDetector
import com.guidedog.app.utils.ImageRecognitionManager
import com.guidedog.app.utils.RecognitionMode
import com.guidedog.app.utils.TtsManager
import com.guidedog.app.utils.VibratorUtils

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraManager: CameraManager
    private lateinit var ttsManager: TtsManager
    private lateinit var vibratorUtils: VibratorUtils
    private lateinit var gestureDetector: GestureDetector
    private lateinit var recognitionManager: ImageRecognitionManager

    private var isRecognitionOn = false
    private var frameCount = 0
    private var lastFrameTime = 0L
    private var fps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = CameraManager.getInstance(this)
        ttsManager = TtsManager.getInstance(this)
        vibratorUtils = VibratorUtils.getInstance(this)
        recognitionManager = ImageRecognitionManager.getInstance(this)

        setupRecognitionManager()
        setupCameraConfig()
        setupGestureDetector()
        setupViews()
        checkPermissionAndStartCamera()
    }

    private fun setupRecognitionManager() {
        val accessKeyId = BuildConfig.ALIYUN_ACCESS_KEY_ID
        val accessKeySecret = BuildConfig.ALIYUN_ACCESS_KEY_SECRET
        if (accessKeyId.isNotEmpty() && accessKeySecret.isNotEmpty()) {
            recognitionManager.init(accessKeyId, accessKeySecret)
        }

        recognitionManager.setRecognitionInterval(3000)
        recognitionManager.setOfflineInterval(1500)
        recognitionManager.setMinConfidence(0.5)
        recognitionManager.setResultCooldown(8000)

        recognitionManager.initOffline()

        recognitionManager.startNetworkMonitor { mode ->
            onRecognitionModeChanged(mode)
        }
    }

    private fun onRecognitionModeChanged(mode: RecognitionMode) {
        val modeText = if (mode == RecognitionMode.ONLINE) {
            getString(R.string.recognition_mode_online)
        } else {
            getString(R.string.recognition_mode_offline)
        }

        val announceText = getString(R.string.recognition_mode_switched, modeText)
        ttsManager.speak(announceText, flush = true)
        AccessibilityUtils.announceForAccessibility(binding.root, announceText)
        vibratorUtils.vibrateDoubleClick()

        if (isRecognitionOn) {
            binding.tvStatus.text = getString(R.string.recognition_running) + "，" + modeText
        }
    }

    private fun setupCameraConfig() {
        cameraManager.frameFormat = FrameFormat.NV21
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(
            onSingleTap = {
                vibratorUtils.vibrateClick()
                if (isRecognitionOn) {
                    announceCameraStatus()
                } else {
                    toggleRecognition()
                }
            },
            onDoubleTap = {
                switchCamera()
            },
            onLongPress = {
                closeCamera()
            }
        )
        binding.previewView.setOnTouchListener { v, event ->
            if (AccessibilityUtils.isTalkBackEnabled(this)) {
                v.onTouchEvent(event)
            } else {
                gestureDetector.onTouch(v, event)
            }
        }
    }

    private fun setupViews() {
        binding.btnSwitchCamera.setOnClickListener {
            vibratorUtils.vibrateClick()
            switchCamera()
        }

        binding.btnToggleTorch.setOnClickListener {
            vibratorUtils.vibrateClick()
            toggleTorch()
        }

        binding.btnCloseCamera.setOnClickListener {
            vibratorUtils.vibrateClick()
            closeCamera()
        }

        binding.btnToggleRecognition.setOnClickListener {
            vibratorUtils.vibrateClick()
            toggleRecognition()
        }
    }

    private fun toggleRecognition() {
        if (!cameraManager.isRunning()) {
            val errorText = getString(R.string.recognition_camera_not_ready)
            ttsManager.speak(errorText, flush = true)
            return
        }

        isRecognitionOn = !isRecognitionOn

        if (isRecognitionOn) {
            startRecognition()
        } else {
            stopRecognition()
        }
    }

    private fun startRecognition() {
        binding.btnToggleRecognition.text = getString(R.string.stop_recognition)
        binding.btnToggleRecognition.contentDescription = getString(R.string.stop_recognition_desc)

        val modeText = if (recognitionManager.getCurrentMode() == RecognitionMode.ONLINE) {
            getString(R.string.recognition_mode_online)
        } else {
            getString(R.string.recognition_mode_offline)
        }
        binding.tvStatus.text = getString(R.string.recognition_running) + "，" + modeText

        recognitionManager.startRecognition(
            onResult = { results ->
            },
            onBroadcast = { text ->
                ttsManager.speak(text, flush = false)
                AccessibilityUtils.announceForAccessibility(binding.root, text)
            },
            onError = { error ->
                val errorText = getString(R.string.recognition_error, error)
                binding.tvStatus.text = errorText
                ttsManager.speak(errorText, flush = true)
            }
        )

        val startText = getString(R.string.recognition_started) + "，" + modeText
        ttsManager.speak(startText, flush = true)
        AccessibilityUtils.announceForAccessibility(binding.root, startText)
        vibratorUtils.vibrateSuccess()
    }

    private fun stopRecognition() {
        binding.btnToggleRecognition.text = getString(R.string.start_recognition)
        binding.btnToggleRecognition.contentDescription = getString(R.string.start_recognition_desc)
        binding.tvStatus.text = getString(R.string.camera_ready)

        recognitionManager.stopRecognition()

        val stopText = getString(R.string.recognition_stopped)
        ttsManager.speak(stopText, flush = true)
        AccessibilityUtils.announceForAccessibility(binding.root, stopText)
    }

    private fun checkPermissionAndStartCamera() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val errorText = getString(R.string.camera_not_granted)
            binding.tvStatus.text = errorText
            ttsManager.speak(errorText, flush = true)
            AccessibilityUtils.announceForAccessibility(binding.root, errorText)
            return
        }
        startCamera()
    }

    private fun startCamera() {
        binding.tvStatus.text = getString(R.string.camera_starting)
        cameraManager.startCamera(
            lifecycleOwner = this,
            previewView = binding.previewView,
            listener = { bytes, width, height, rotation, format ->
                frameCount++
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastFrameTime >= 1000) {
                    fps = frameCount
                    frameCount = 0
                    lastFrameTime = currentTime
                }

                if (isRecognitionOn) {
                    recognitionManager.feedFrame(bytes, width, height, format)
                }
            },
            onReady = {
                val readyText = getString(R.string.camera_ready)
                binding.tvStatus.text = readyText
                ttsManager.speak(readyText, flush = true)
                AccessibilityUtils.announceForAccessibility(binding.root, readyText)
                vibratorUtils.vibrateSuccess()
            },
            onError = { error ->
                binding.tvStatus.text = error
                ttsManager.speak(error, flush = true)
                AccessibilityUtils.announceForAccessibility(binding.root, error)
            }
        )
    }

    private fun switchCamera() {
        if (!cameraManager.isRunning()) return

        val wasRecognitionOn = isRecognitionOn
        if (wasRecognitionOn) {
            recognitionManager.stopRecognition()
        }

        cameraManager.switchCamera(
            lifecycleOwner = this,
            previewView = binding.previewView,
            onSwitched = {
                val lensText = if (cameraManager.getLensFacing() == androidx.camera.core.CameraSelector.LENS_FACING_FRONT) {
                    getString(R.string.camera_front)
                } else {
                    getString(R.string.camera_back)
                }
                ttsManager.speak(lensText, flush = true)
                AccessibilityUtils.announceForAccessibility(binding.root, lensText)
                vibratorUtils.vibrateDoubleClick()

                if (wasRecognitionOn) {
                    recognitionManager.startRecognition(
                        onBroadcast = { text ->
                            ttsManager.speak(text, flush = false)
                            AccessibilityUtils.announceForAccessibility(binding.root, text)
                        }
                    )
                }
            }
        )
    }

    private fun toggleTorch() {
        if (!cameraManager.isRunning()) return

        val isOn = cameraManager.toggleTorch()
        val torchText = if (isOn) {
            getString(R.string.torch_on)
        } else {
            getString(R.string.torch_off)
        }
        ttsManager.speak(torchText, flush = true)
        AccessibilityUtils.announceForAccessibility(binding.root, torchText)
    }

    private fun announceCameraStatus() {
        val cameraInfo = cameraManager.getCameraInfo()
        val statusText = if (cameraInfo != null) {
            val lensText = if (cameraInfo.lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT) {
                getString(R.string.camera_front)
            } else {
                getString(R.string.camera_back)
            }
            val torchText = if (cameraInfo.isTorchOn) {
                getString(R.string.torch_on)
            } else {
                getString(R.string.torch_off)
            }
            val recognitionText = if (isRecognitionOn) {
                val modeText = if (recognitionManager.getCurrentMode() == RecognitionMode.ONLINE) {
                    getString(R.string.recognition_mode_online)
                } else {
                    getString(R.string.recognition_mode_offline)
                }
                getString(R.string.recognition_running) + "，" + modeText
            } else {
                getString(R.string.recognition_stopped)
            }
            "${getString(R.string.camera_ready)}，$lensText，$torchText，$recognitionText"
        } else {
            getString(R.string.camera_not_ready)
        }
        ttsManager.speak(statusText, flush = true)
        AccessibilityUtils.announceForAccessibility(binding.root, statusText)
    }

    private fun closeCamera() {
        if (isRecognitionOn) {
            recognitionManager.stopRecognition()
            isRecognitionOn = false
        }

        val closeText = getString(R.string.camera_closed)
        ttsManager.speak(closeText, flush = true)
        AccessibilityUtils.announceForAccessibility(binding.root, closeText)

        binding.previewView.postDelayed({
            finish()
        }, 800)
    }

    override fun onResume() {
        super.onResume()
        if (!cameraManager.isRunning() && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else if (cameraManager.isRunning()) {
            val statusText = getString(R.string.camera_ready)
            AccessibilityUtils.announceForAccessibility(binding.root, statusText)
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRecognitionOn) {
            recognitionManager.stopRecognition()
        }
        cameraManager.stopCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        gestureDetector.destroy()
        recognitionManager.release()
        cameraManager.releaseCamera()
    }
}
