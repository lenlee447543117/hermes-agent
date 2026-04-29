package com.ailaohu.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ailaohu_prefs")

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val IS_FIRST_LAUNCH = booleanPreferencesKey("is_first_launch")
        val IS_PERMISSION_GUIDED = booleanPreferencesKey("is_permission_guided")
        val IS_CONTACT_BOUND = booleanPreferencesKey("is_contact_bound")
        val CHILD_PHONE_NUMBER = stringPreferencesKey("child_phone_number")

        val USER_ID = stringPreferencesKey("user_id")
        val HERMES_URL = stringPreferencesKey("hermes_url")
        val DIALECT_MODE = stringPreferencesKey("dialect_mode")

        val VOICE_FEEDBACK_ENABLED = booleanPreferencesKey("voice_feedback_enabled")
        val VOICE_LANGUAGE = stringPreferencesKey("voice_language")
        val VOICE_BACKEND = stringPreferencesKey("voice_backend")
        val SELECTED_ASR_PACKAGE = stringPreferencesKey("selected_asr_package")
        val SELECTED_ASR_CLASS = stringPreferencesKey("selected_asr_class")

        val TTS_SPEECH_RATE = floatPreferencesKey("tts_speech_rate")

        val AI_MODEL = stringPreferencesKey("ai_model")

        val CONTINUOUS_DIALOG_ENABLED = booleanPreferencesKey("continuous_dialog_enabled")
        val VOICE_FEEDBACK_TONE_ENABLED = booleanPreferencesKey("voice_feedback_tone_enabled")
        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val FLOATING_BUTTON_ENABLED = booleanPreferencesKey("floating_button_enabled")
    }

    val isFirstLaunch: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_FIRST_LAUNCH] ?: true }
    val isPermissionGuided: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_PERMISSION_GUIDED] ?: false }
    val isContactBound: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_CONTACT_BOUND] ?: false }
    val childPhoneNumber: Flow<String> = context.dataStore.data.map { it[Keys.CHILD_PHONE_NUMBER] ?: "" }

    val userId: Flow<String> = context.dataStore.data.map { it[Keys.USER_ID] ?: "default" }
    val hermesUrl: Flow<String> = context.dataStore.data.map { it[Keys.HERMES_URL] ?: "http://10.0.2.2:8642/" }
    val dialectMode: Flow<String> = context.dataStore.data.map { it[Keys.DIALECT_MODE] ?: "mandarin" }

    val voiceFeedbackEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.VOICE_FEEDBACK_ENABLED] ?: true }
    val voiceLanguage: Flow<String> = context.dataStore.data.map { it[Keys.VOICE_LANGUAGE] ?: "zh-CN" }
    val voiceBackend: Flow<String> = context.dataStore.data.map { it[Keys.VOICE_BACKEND] ?: "local" }
    val selectedAsrPackage: Flow<String> = context.dataStore.data.map { it[Keys.SELECTED_ASR_PACKAGE] ?: "" }
    val selectedAsrClass: Flow<String> = context.dataStore.data.map { it[Keys.SELECTED_ASR_CLASS] ?: "" }

    val ttsSpeechRate: Flow<Float> = context.dataStore.data.map { it[Keys.TTS_SPEECH_RATE] ?: 0.85f }

    val aiModel: Flow<String> = context.dataStore.data.map { it[Keys.AI_MODEL] ?: "autoglm-phone" }

    val continuousDialogEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.CONTINUOUS_DIALOG_ENABLED] ?: true }
    val voiceFeedbackToneEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.VOICE_FEEDBACK_TONE_ENABLED] ?: true }
    val wakeWordEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.WAKE_WORD_ENABLED] ?: false }
    val floatingButtonEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.FLOATING_BUTTON_ENABLED] ?: true }

    suspend fun setFirstLaunchCompleted() { context.dataStore.edit { it[Keys.IS_FIRST_LAUNCH] = false } }
    suspend fun setPermissionGuided() { context.dataStore.edit { it[Keys.IS_PERMISSION_GUIDED] = true } }
    suspend fun setContactBound() { context.dataStore.edit { it[Keys.IS_CONTACT_BOUND] = true } }
    suspend fun setChildPhoneNumber(phone: String) { context.dataStore.edit { it[Keys.CHILD_PHONE_NUMBER] = phone } }

    suspend fun setUserId(id: String) { context.dataStore.edit { it[Keys.USER_ID] = id } }
    suspend fun setHermesUrl(url: String) { context.dataStore.edit { it[Keys.HERMES_URL] = url } }
    suspend fun setDialectMode(mode: String) { context.dataStore.edit { it[Keys.DIALECT_MODE] = mode } }

    suspend fun setVoiceFeedbackEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.VOICE_FEEDBACK_ENABLED] = enabled } }
    suspend fun setVoiceLanguage(language: String) { context.dataStore.edit { it[Keys.VOICE_LANGUAGE] = language } }
    suspend fun setVoiceBackend(backend: String) { context.dataStore.edit { it[Keys.VOICE_BACKEND] = backend } }
    suspend fun setSelectedAsrPackage(pkg: String) { context.dataStore.edit { it[Keys.SELECTED_ASR_PACKAGE] = pkg } }
    suspend fun setSelectedAsrClass(cls: String) { context.dataStore.edit { it[Keys.SELECTED_ASR_CLASS] = cls } }

    suspend fun setTtsSpeechRate(rate: Float) { context.dataStore.edit { it[Keys.TTS_SPEECH_RATE] = rate } }

    suspend fun setAiModel(model: String) { context.dataStore.edit { it[Keys.AI_MODEL] = model } }

    suspend fun setContinuousDialogEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.CONTINUOUS_DIALOG_ENABLED] = enabled } }
    suspend fun setVoiceFeedbackToneEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.VOICE_FEEDBACK_TONE_ENABLED] = enabled } }
    suspend fun setWakeWordEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.WAKE_WORD_ENABLED] = enabled } }
    suspend fun setFloatingButtonEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.FLOATING_BUTTON_ENABLED] = enabled } }
}
