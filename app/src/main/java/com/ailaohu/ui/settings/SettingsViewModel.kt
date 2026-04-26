package com.ailaohu.ui.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ailaohu.data.local.prefs.AppPreferences
import com.ailaohu.service.tts.TTSManager
import com.ailaohu.service.voice.VoiceRecognitionEngine
import com.ailaohu.service.voice.WakeUpService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val voiceFeedbackEnabled: Boolean = true,
    val voiceLanguage: String = "zh-CN",
    val voicePackageStatus: String = "checking",
    val currentBackend: String = "local",
    val ttsSpeechRate: Float = 0.85f,
    val aiModel: String = "autoglm-phone",
    val continuousDialogEnabled: Boolean = true,
    val voiceFeedbackToneEnabled: Boolean = true,
    val wakeWordEnabled: Boolean = false,
    val floatingButtonEnabled: Boolean = true
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val appPreferences: AppPreferences,
    private val ttsManager: TTSManager,
    private val voiceRecognitionEngine: VoiceRecognitionEngine
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                appPreferences.voiceFeedbackEnabled,
                appPreferences.voiceLanguage,
                appPreferences.ttsSpeechRate,
                appPreferences.aiModel,
                appPreferences.voiceBackend,
                appPreferences.continuousDialogEnabled,
                appPreferences.voiceFeedbackToneEnabled,
                appPreferences.wakeWordEnabled,
                appPreferences.floatingButtonEnabled
            ) { values ->
                SettingsUiState(
                    voiceFeedbackEnabled = values[0] as Boolean,
                    voiceLanguage = values[1] as String,
                    ttsSpeechRate = values[2] as Float,
                    aiModel = values[3] as String,
                    currentBackend = values[4] as String,
                    continuousDialogEnabled = values[5] as Boolean,
                    voiceFeedbackToneEnabled = values[6] as Boolean,
                    wakeWordEnabled = values[7] as Boolean,
                    floatingButtonEnabled = values[8] as Boolean
                )
            }.collect { _uiState.value = it }
        }
        checkVoicePackage()
    }

    fun checkVoicePackage() {
        viewModelScope.launch {
            _uiState.update { it.copy(voicePackageStatus = "checking") }
            val isAvailable = voiceRecognitionEngine.isAvailable()
            val status = if (isAvailable) "available" else "missing"
            _uiState.update { it.copy(voicePackageStatus = status) }
        }
    }

    fun useCloudVoice() {
        viewModelScope.launch {
            voiceRecognitionEngine.setBackend(VoiceRecognitionEngine.Backend.ZHIPU_CLOUD)
            appPreferences.setVoiceBackend("cloud")
            _uiState.update { it.copy(currentBackend = "cloud") }
            ttsManager.speak("已切换到云端语音识别")
        }
    }

    fun setVoiceFeedbackEnabled(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setVoiceFeedbackEnabled(enabled) }
    }

    fun setVoiceLanguage(language: String) {
        viewModelScope.launch {
            appPreferences.setVoiceLanguage(language)
            voiceRecognitionEngine.updateLanguage(language)
        }
    }

    fun setTtsSpeechRate(rate: Float) {
        viewModelScope.launch {
            appPreferences.setTtsSpeechRate(rate)
            ttsManager.updateSpeechRate(rate)
        }
    }

    fun setAiModel(model: String) {
        viewModelScope.launch { appPreferences.setAiModel(model) }
    }

    fun setContinuousDialogEnabled(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setContinuousDialogEnabled(enabled) }
    }

    fun setVoiceFeedbackToneEnabled(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setVoiceFeedbackToneEnabled(enabled) }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.setWakeWordEnabled(enabled)
            if (enabled) {
                WakeUpService.start(appContext)
            } else {
                WakeUpService.stop(appContext)
            }
        }
    }

    fun setFloatingButtonEnabled(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setFloatingButtonEnabled(enabled) }
    }

    fun previewTts() {
        ttsManager.speak("这是语音预览，当前语速设置已生效。")
    }
}
