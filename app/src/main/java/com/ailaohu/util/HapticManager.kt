package com.ailaohu.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HapticManager @Inject constructor(@ApplicationContext private val context: Context) {

    enum class Pattern(val delays: LongArray) {
        SUCCESS(longArrayOf(0, 100, 50, 100)),
        ALERT(longArrayOf(0, 200, 100, 200)),
        ERROR(longArrayOf(0, 300))
    }

    companion object {
        fun vibrate(context: Context, pattern: Pattern) {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern.delays, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern.delays, -1)
            }
        }
    }

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun lightClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(30, 80))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(30)
        }
    }

    fun mediumFeedback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(50, 120))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(50)
        }
    }

    fun heavyFeedback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(100, 200))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(100)
        }
    }
}
