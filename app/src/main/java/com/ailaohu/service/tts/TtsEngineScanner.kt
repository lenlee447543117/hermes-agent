package com.ailaohu.service.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.*

class TtsEngineScanner(private val context: Context) {

    private var tempTts: TextToSpeech? = null

    interface OnEngineFoundListener {
        fun onFound(enginePackageName: String, locale: Locale)
        fun onNoneAvailable()
    }

    fun findBestChineseEngine(listener: OnEngineFoundListener) {
        tempTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engines = tempTts?.engines ?: emptyList()
                var targetEngine: String? = null
                var targetLocale: Locale = Locale.CHINESE

                Log.d(TAG, "扫描到 ${engines.size} 个TTS引擎")

                for (info in engines) {
                    Log.d(TAG, "检测引擎: ${info.name} (${info.label})")

                    var checkTts: TextToSpeech? = null
                    try {
                        checkTts = TextToSpeech(context, { _ -> }, info.name)
                        val availableLocales = mutableListOf<Locale>()

                        val localesToCheck = listOf(
                            Locale.CHINESE,
                            Locale.SIMPLIFIED_CHINESE,
                            Locale.TRADITIONAL_CHINESE,
                            Locale("zh", "CN"),
                            Locale("zh", "TW"),
                            Locale("zh", "HK")
                        )

                        for (locale in localesToCheck) {
                            val result = checkTts.isLanguageAvailable(locale)
                            if (result >= TextToSpeech.LANG_AVAILABLE) {
                                Log.d(TAG, "引擎 ${info.name} 支持 $locale")
                                availableLocales.add(locale)
                            }
                        }

                        if (availableLocales.isNotEmpty()) {
                            Log.d(TAG, "引擎 ${info.name} 支持 ${availableLocales.size} 个中文语言包")
                            targetEngine = info.name
                            targetLocale = availableLocales.firstOrNull() ?: Locale.CHINESE
                            break
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "检测引擎 ${info.name} 失败", e)
                    } finally {
                        checkTts?.shutdown()
                    }
                }

                tempTts?.shutdown()

                if (targetEngine != null) {
                    Log.d(TAG, "找到最佳引擎: $targetEngine, 语言: $targetLocale")
                    listener.onFound(targetEngine, targetLocale)
                } else {
                    Log.w(TAG, "没有找到支持中文的引擎")
                    listener.onNoneAvailable()
                }
            } else {
                Log.e(TAG, "TTS初始化失败，状态: $status")
                tempTts?.shutdown()
                listener.onNoneAvailable()
            }
        }
    }

    companion object {
        private const val TAG = "TtsEngineScanner"
    }
}
