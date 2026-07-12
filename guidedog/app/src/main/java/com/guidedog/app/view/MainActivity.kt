package com.guidedog.app.view

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.guidedog.app.R
import com.guidedog.app.controller.PermissionController
import com.guidedog.app.databinding.ActivityMainBinding
import com.guidedog.app.service.SpeechRecognitionService
import com.guidedog.app.utils.AccessibilityUtils
import com.guidedog.app.utils.GestureDetector
import com.guidedog.app.utils.SpeechCommandParser
import com.guidedog.app.utils.TtsManager
import com.guidedog.app.utils.VibratorUtils

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionController: PermissionController
    private lateinit var ttsManager: TtsManager
    private lateinit var vibratorUtils: VibratorUtils
    private lateinit var gestureDetector: GestureDetector

    private val functionNames = arrayOf(
        R.string.function_navigation,
        R.string.function_recognition,
        R.string.function_settings
    )

    private val functionDescs = arrayOf(
        R.string.function_navigation_desc,
        R.string.function_recognition_desc,
        R.string.function_settings_desc
    )

    private var currentFunctionIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissionController = PermissionController(this)
        ttsManager = TtsManager.getInstance(this)
        vibratorUtils = VibratorUtils.getInstance(this)

        setupGestureDetector()
        setupViews()
        checkAndRequestPermissions()
        initTts()
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(
            onSingleTap = { handleSingleTap() },
            onDoubleTap = { handleDoubleTap() },
            onLongPress = { handleLongPress() }
        )
        binding.btnMainFunction.setOnTouchListener { v, event ->
            if (AccessibilityUtils.isTalkBackEnabled(this)) {
                v.onTouchEvent(event)
            } else {
                gestureDetector.onTouch(v, event)
            }
        }

        binding.btnMainFunction.setOnClickListener {
            if (AccessibilityUtils.isTalkBackEnabled(this)) {
                vibratorUtils.vibrateClick()
                executeCurrentFunction()
            }
        }

        binding.btnMainFunction.setOnLongClickListener {
            if (AccessibilityUtils.isTalkBackEnabled(this)) {
                vibratorUtils.vibrateLongPress()
                toggleSpeechRecognition()
                true
            } else {
                false
            }
        }
    }

    private fun setupViews() {
        updateFunctionDisplay()

        binding.btnSpeedDown.setOnClickListener {
            vibratorUtils.vibrateClick()
            adjustSpeed(-0.2f)
        }

        binding.btnSpeedUp.setOnClickListener {
            vibratorUtils.vibrateClick()
            adjustSpeed(0.2f)
        }

        binding.btnStopTts.setOnClickListener {
            vibratorUtils.vibrateClick()
            stopTts()
        }

        binding.btnToggleSpeech.setOnClickListener {
            vibratorUtils.vibrateClick()
            toggleSpeechRecognition()
        }
    }

    private fun handleSingleTap() {
        vibratorUtils.vibrateClick()
        openRecognitionMode()
    }

    private fun handleDoubleTap() {
        vibratorUtils.vibrateDoubleClick()
        openNavigationMode()
    }

    private fun handleLongPress() {
        vibratorUtils.vibrateLongPress()
        toggleSpeechRecognition()
    }

    private fun openNavigationMode() {
        val text = "${getString(R.string.function_navigation)}，${getString(R.string.tts_test_content)}"
        ttsManager.speak(text, flush = true)
        AccessibilityUtils.announceForAccessibility(binding.root, text)
    }

    private fun executeCurrentFunction() {
        when (currentFunctionIndex) {
            0 -> {
                speakFunctionAction(getString(R.string.function_navigation), getString(R.string.tts_test_content))
            }
            1 -> {
                openRecognitionMode()
            }
            2 -> {
                speakFunctionAction(getString(R.string.function_settings), getString(R.string.tts_test_content))
            }
        }
    }

    private fun openRecognitionMode() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val text = getString(R.string.camera_not_granted)
            ttsManager.speak(text, flush = true)
            AccessibilityUtils.announceForAccessibility(binding.root, text)
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                PermissionController.REQUEST_CODE_REQUIRED_PERMISSIONS
            )
            return
        }

        val text = getString(R.string.function_recognition)
        ttsManager.speak(text, flush = true)
        AccessibilityUtils.announceForAccessibility(binding.root, text)

        binding.btnMainFunction.postDelayed({
            val intent = Intent(this, CameraActivity::class.java)
            startActivity(intent)
        }, 500)
    }

    private fun speakFunctionAction(functionName: String, actionText: String) {
        val text = "$functionName，$actionText"
        ttsManager.speak(text, flush = true)
        AccessibilityUtils.announceForAccessibility(binding.root, text)
    }

    private fun switchToNextFunction() {
        currentFunctionIndex = (currentFunctionIndex + 1) % functionNames.size
        updateFunctionDisplay()
        val functionName = getString(functionNames[currentFunctionIndex])
        val announceText = "${getString(R.string.function_switched)}$functionName"
        ttsManager.speak(announceText, flush = true)
        AccessibilityUtils.announceForAccessibility(binding.root, announceText)
    }

    private fun updateFunctionDisplay() {
        val nameResId = functionNames[currentFunctionIndex]
        val descResId = functionDescs[currentFunctionIndex]
        binding.tvCurrentFunction.setText(nameResId)
        binding.btnMainFunction.setText(nameResId)
        binding.btnMainFunction.contentDescription = "${getString(descResId)}，${getString(R.string.gesture_hint)}"
        binding.tvCurrentFunction.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
    }

    private fun exitApp() {
        val exitText = getString(R.string.app_exit)
        ttsManager.speak(exitText, flush = true)
        AccessibilityUtils.announceForAccessibility(binding.root, exitText)
        binding.btnMainFunction.postDelayed({
            finish()
        }, 1500)
    }

    private fun initTts() {
        if (!ttsManager.isReady()) {
            ttsManager.init { success ->
                if (success) {
                    val welcomeText = "${getString(R.string.app_name)}已启动，${getString(functionNames[currentFunctionIndex])}"
                    ttsManager.speak(welcomeText, flush = true)
                    AccessibilityUtils.announceForAccessibility(binding.root, welcomeText)
                }
            }
        }
    }

    private fun adjustSpeed(delta: Float) {
        val currentRate = ttsManager.getSpeechRate()
        val newRate = (currentRate + delta).coerceIn(0.1f, 3.0f)
        ttsManager.setSpeechRate(newRate)
        val rateStr = "%.1f".format(newRate)
        val announceText = if (delta > 0) {
            "${getString(R.string.speed_up_announce)}，${getString(R.string.current_speed)}$rateStr${getString(R.string.speed_unit)}"
        } else {
            "${getString(R.string.speed_down_announce)}，${getString(R.string.current_speed)}$rateStr${getString(R.string.speed_unit)}"
        }
        ttsManager.speak(announceText, flush = true)
        AccessibilityUtils.announceForAccessibility(binding.root, announceText)
    }

    private fun stopTts() {
        ttsManager.stop()
    }

    private fun toggleSpeechRecognition() {
        if (SpeechRecognitionService.isServiceRunning) {
            stopSpeechRecognition()
        } else {
            startSpeechRecognition()
        }
    }

    private fun startSpeechRecognition() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val text = getString(R.string.permission_microphone)
            ttsManager.speak(text, flush = true)
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PermissionController.REQUEST_CODE_REQUIRED_PERMISSIONS
            )
            return
        }

        val intent = Intent(this, SpeechRecognitionService::class.java).apply {
            action = SpeechRecognitionService.ACTION_START
        }
        startForegroundService(intent)
        binding.btnToggleSpeech.text = getString(R.string.stop_speech)
        binding.btnToggleSpeech.contentDescription = getString(R.string.stop_speech_desc)
    }

    private fun stopSpeechRecognition() {
        val intent = Intent(this, SpeechRecognitionService::class.java).apply {
            action = SpeechRecognitionService.ACTION_STOP
        }
        startService(intent)
        binding.btnToggleSpeech.text = getString(R.string.start_speech)
        binding.btnToggleSpeech.contentDescription = getString(R.string.start_speech_desc)
    }

    fun handleSpeechCommand(command: SpeechCommandParser.ParsedCommand) {
        when (command.type) {
            SpeechCommandParser.CommandType.NAVIGATE -> {
                currentFunctionIndex = 0
                updateFunctionDisplay()
                executeCurrentFunction()
            }
            SpeechCommandParser.CommandType.RECOGNIZE -> {
                currentFunctionIndex = 1
                updateFunctionDisplay()
                executeCurrentFunction()
            }
            SpeechCommandParser.CommandType.SETTINGS -> {
                currentFunctionIndex = 2
                updateFunctionDisplay()
                executeCurrentFunction()
            }
            SpeechCommandParser.CommandType.SWITCH_CAMERA -> {
                ttsManager.speak(getString(R.string.switch_camera), flush = true)
            }
            SpeechCommandParser.CommandType.TOGGLE_TORCH -> {
                ttsManager.speak(getString(R.string.toggle_torch), flush = true)
            }
            SpeechCommandParser.CommandType.CLOSE -> {
                exitApp()
            }
            SpeechCommandParser.CommandType.STOP -> {
                ttsManager.stop()
            }
            SpeechCommandParser.CommandType.SPEED_UP -> {
                adjustSpeed(0.2f)
            }
            SpeechCommandParser.CommandType.SPEED_DOWN -> {
                adjustSpeed(-0.2f)
            }
            SpeechCommandParser.CommandType.REPEAT -> {
                val currentFunction = getString(functionNames[currentFunctionIndex])
                ttsManager.speak(currentFunction, flush = true)
            }
            SpeechCommandParser.CommandType.HELP -> {
                ttsManager.speak(getString(R.string.help_text), flush = true)
            }
            SpeechCommandParser.CommandType.DESTINATION -> {
                val destText = getString(R.string.command_destination, command.destination)
                ttsManager.speak(destText, flush = true)
            }
            SpeechCommandParser.CommandType.UNKNOWN -> {
                ttsManager.speak(getString(R.string.command_unknown), flush = true)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        if (!permissionController.areAllPermissionsGranted()) {
            val ungrantedPermissions = permissionController.getAllUngrantedPermissions()
            if (ungrantedPermissions.isNotEmpty()) {
                permissionController.requestPermissions(
                    this,
                    ungrantedPermissions.map { it.permission }.toTypedArray(),
                    PermissionController.REQUEST_CODE_REQUIRED_PERMISSIONS
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionController.REQUEST_CODE_REQUIRED_PERMISSIONS) {
            if (permissionController.areAllPermissionsGranted()) {
                val text = getString(R.string.permissions_all_granted)
                ttsManager.speak(text, flush = true)
                AccessibilityUtils.announceForAccessibility(binding.root, text)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (ttsManager.isReady()) {
            val speechStatus = if (SpeechRecognitionService.isServiceRunning) {
                getString(R.string.speech_started)
            } else {
                getString(R.string.speech_stopped)
            }
            val statusText = "${getString(R.string.app_name)}，${getString(R.string.gesture_hint)}，$speechStatus"
            AccessibilityUtils.announceForAccessibility(binding.root, statusText)
        }
        updateSpeechButtonState()
    }

    private fun updateSpeechButtonState() {
        if (SpeechRecognitionService.isServiceRunning) {
            binding.btnToggleSpeech.text = getString(R.string.stop_speech)
            binding.btnToggleSpeech.contentDescription = getString(R.string.stop_speech_desc)
        } else {
            binding.btnToggleSpeech.text = getString(R.string.start_speech)
            binding.btnToggleSpeech.contentDescription = getString(R.string.start_speech_desc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gestureDetector.destroy()
        ttsManager.stop()
        vibratorUtils.cancel()
    }
}
