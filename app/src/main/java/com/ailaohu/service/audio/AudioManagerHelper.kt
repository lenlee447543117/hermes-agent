package com.ailaohu.service.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioManagerHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AudioManagerHelper"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    fun requestExclusiveFocus() {
        if (hasFocus) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { _ ->
                        Log.d(TAG, "音频焦点变化")
                    }
                    .build()

                val result = audioManager.requestAudioFocus(focusRequest!!)
                hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                Log.d(TAG, "请求独占音频焦点: ${if (hasFocus) "成功" else "失败"}")
            } catch (e: Exception) {
                Log.e(TAG, "请求独占音频焦点失败", e)
            }
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                { _ -> Log.d(TAG, "音频焦点变化") },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
            hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.d(TAG, "请求音频焦点(legacy): ${if (hasFocus) "成功" else "失败"}")
        }
    }

    fun abandonFocus() {
        if (!hasFocus) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let {
                try {
                    audioManager.abandonAudioFocusRequest(it)
                } catch (e: Exception) {
                    Log.e(TAG, "释放音频焦点失败", e)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus { _ -> }
        }
        hasFocus = false
        focusRequest = null
        Log.d(TAG, "已释放音频焦点")
    }
}
