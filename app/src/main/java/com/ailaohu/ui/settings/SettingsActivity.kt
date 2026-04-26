package com.ailaohu.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    private lateinit var viewModel: SettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        enableEdgeToEdge()

        setContent {
            val uiState by viewModel.uiState.collectAsState()

            SettingsScreen(
                uiState = uiState,
                onBack = { finish() },
                onVoiceFeedbackToggle = viewModel::setVoiceFeedbackEnabled,
                onVoiceLanguageChange = viewModel::setVoiceLanguage,
                onTtsSpeechRateChange = viewModel::setTtsSpeechRate,
                onTtsPreview = viewModel::previewTts,
                onAiModelChange = viewModel::setAiModel,
                onCheckVoicePackage = viewModel::checkVoicePackage,
                onUseCloudVoice = viewModel::useCloudVoice,
                onContinuousDialogToggle = viewModel::setContinuousDialogEnabled,
                onFeedbackToneToggle = viewModel::setVoiceFeedbackToneEnabled,
                onWakeWordToggle = viewModel::setWakeWordEnabled,
                onFloatingButtonToggle = viewModel::setFloatingButtonEnabled
            )
        }
    }
}
