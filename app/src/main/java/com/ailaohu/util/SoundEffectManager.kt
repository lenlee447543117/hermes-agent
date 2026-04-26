package com.ailaohu.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SoundEffectManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SoundEffectManager"
    }

    private var toneGenerator: ToneGenerator? = null

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_SYSTEM, 80)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ToneGenerator", e)
        }
    }

    fun playStartSound() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing start sound", e)
        }
    }

    fun playErrorSound() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing error sound", e)
        }
    }

    fun playSuccessSound() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing success sound", e)
        }
    }

    fun release() {
        toneGenerator?.release()
        toneGenerator = null
    }
}
