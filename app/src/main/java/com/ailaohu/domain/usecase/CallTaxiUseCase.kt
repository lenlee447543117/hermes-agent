package com.ailaohu.domain.usecase

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.ailaohu.service.tts.TTSManager
import com.ailaohu.util.Constants
import com.ailaohu.util.HapticManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class CallTaxiUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ttsManager: TTSManager,
    private val hapticManager: HapticManager
) {
    suspend fun execute(): Boolean {
        hapticManager.mediumFeedback()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            ttsManager.speak("请先允许拨打电话权限")
            return false
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_CALL
        audioManager.isSpeakerphoneOn = true

        ttsManager.speak("正在为您拨打助老打车电话")

        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:${Constants.ELDERLY_CARE_TAXI_NUMBER}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return true
    }
}
