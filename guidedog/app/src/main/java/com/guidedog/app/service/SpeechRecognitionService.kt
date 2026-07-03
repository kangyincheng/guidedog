package com.guidedog.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.guidedog.app.R
import com.guidedog.app.utils.SpeechCommandParser
import com.guidedog.app.utils.TtsManager
import com.guidedog.app.utils.VibratorUtils
import com.guidedog.app.utils.VoskManager

class SpeechRecognitionService : Service() {

    private lateinit var voskManager: VoskManager
    private lateinit var ttsManager: TtsManager
    private lateinit var vibratorUtils: VibratorUtils

    private var isRunning = false
    private var commandListener: ((SpeechCommandParser.ParsedCommand) -> Unit)? = null

    companion object {
        const val CHANNEL_ID = "guidedog_speech_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START = "com.guidedog.app.action.START_RECOGNITION"
        const val ACTION_STOP = "com.guidedog.app.action.STOP_RECOGNITION"
        const val EXTRA_WAKE_WORD_MODE = "wake_word_mode"
        private const val TAG = "SpeechRecognitionSvc"

        @Volatile
        var isServiceRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        voskManager = VoskManager.getInstance(this)
        ttsManager = TtsManager.getInstance(this)
        vibratorUtils = VibratorUtils.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecognition()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startRecognition()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecognition() {
        if (isRunning) return

        val notification = buildNotification(getString(R.string.speech_listening))
        startForeground(NOTIFICATION_ID, notification)
        isServiceRunning = true
        isRunning = true

        voskManager.startListening(
            onResult = { text ->
                handleRecognitionResult(text)
            },
            onPartialResult = { partial ->
                Log.d(TAG, "部分识别: $partial")
            },
            onStateChanged = { listening ->
                if (!listening && isRunning) {
                    restartListening()
                }
            },
            onError = { error ->
                Log.e(TAG, "识别错误: $error")
                ttsManager.speak(getString(R.string.speech_error), flush = true)
                restartListening()
            }
        )

        ttsManager.speak(getString(R.string.speech_started), flush = true)
        vibratorUtils.vibrateSuccess()
    }

    private fun restartListening() {
        if (!isRunning) return

        voskManager.startListening(
            onResult = { text ->
                handleRecognitionResult(text)
            },
            onPartialResult = { partial ->
                Log.d(TAG, "部分识别: $partial")
            },
            onStateChanged = { listening ->
                if (!listening && isRunning) {
                    restartListening()
                }
            },
            onError = { error ->
                Log.e(TAG, "识别错误: $error")
                restartListening()
            }
        )
    }

    private fun handleRecognitionResult(text: String) {
        if (text.isBlank()) {
            vibratorUtils.vibrateClick()
            return
        }

        Log.i(TAG, "识别结果: $text")

        val command = SpeechCommandParser.parse(text)
        Log.i(TAG, "解析指令: ${command.type}, 原文: ${command.rawText}")

        vibratorUtils.vibrateDoubleClick()
        ttsManager.speak(command.rawText, flush = true)

        commandListener?.invoke(command)

        updateNotification(getString(R.string.speech_last_command, command.rawText))
    }

    fun setCommandListener(listener: (SpeechCommandParser.ParsedCommand) -> Unit) {
        commandListener = listener
    }

    private fun stopRecognition() {
        isRunning = false
        isServiceRunning = false
        voskManager.stopListening()
        ttsManager.speak(getString(R.string.speech_stopped), flush = true)
        vibratorUtils.vibrateClick()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.speech_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.speech_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecognition()
        voskManager.shutdown()
        isServiceRunning = false
    }
}
