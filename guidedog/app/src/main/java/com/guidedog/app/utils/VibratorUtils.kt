package com.guidedog.app.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class VibratorUtils private constructor(private val context: Context) {

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun vibrateClick() {
        vibrate(50)
    }

    fun vibrateDoubleClick() {
        vibratePattern(longArrayOf(0, 50, 80, 50))
    }

    fun vibrateLongPress() {
        vibrate(200)
    }

    fun vibrateSuccess() {
        vibratePattern(longArrayOf(0, 100, 100, 100))
    }

    fun vibrateError() {
        vibratePattern(longArrayOf(0, 200, 100, 200, 100, 200))
    }

    fun vibrate(milliseconds: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(milliseconds)
            }
        } catch (_: Exception) {
        }
    }

    fun vibratePattern(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
        }
    }

    fun cancel() {
        try {
            vibrator.cancel()
        } catch (_: Exception) {
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: VibratorUtils? = null

        fun getInstance(context: Context): VibratorUtils {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VibratorUtils(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
