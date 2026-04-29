package com.ailaohu.data.remote.dto.hermes

import com.google.gson.annotations.SerializedName

data class HermesChatRequest(
    val user_id: String,
    val message: String,
    val dialect: String = "shanghai",
    val context: List<HermesChatMessage>? = null
)

data class HermesChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis() / 1000
)

data class HermesChatResponse(
    val reply: String,
    val mode: String,
    val intent: String? = null,
    val action_payload: HermesActionPayload? = null,
    val dialect: String = "shanghai",
    val robot_ui_state: HermesRobotUiState? = null
)

data class HermesRobotUiState(
    val emotion: String = "IDLE",
    val status_text: String = "",
    val show_mask: Boolean = false
)

data class HermesActionPayload(
    val intent: String,
    val target_person: String? = null,
    val parameters: Map<String, @JvmSuppressWildcards Any>? = null,
    val medicine_name: String? = null,
    val dosage: String? = null,
    val time: String? = null
)

data class HermesActionRequest(
    val trace_id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis() / 1000,
    val type: String = "ACTION_REQUEST",
    val payload: HermesActionPayload
)

data class HermesActionResponse(
    val status: String,
    val trace_id: String
)

data class HermesHabitProfile(
    val user_id: String,
    val active_hours: List<Int> = emptyList(),
    val frequent_contacts: List<String> = emptyList(),
    val preferred_call_type: String? = null,
    val dialect_preference: String = "shanghai",
    val volume_preference: Int = 70,
    val font_scale: Float = 1.5f,
    val chat_frequency: Float = 0.0f
)

data class HermesDailyReport(
    val date: String,
    val sleep_status: String? = null,
    val device_usage_minutes: Int = 0,
    val operation_difficulties: List<String> = emptyList(),
    val emotion_tendency: String? = null,
    val anomaly_warnings: List<String> = emptyList()
)

data class HermesCareMessage(
    val user_id: String,
    val message: String,
    val dialect: String = "shanghai",
    val care_type: String = "routine",
    val priority: String = "normal"
)

data class HermesSyncConfigRequest(
    val user_id: String,
    val contacts: List<Map<String, @JvmSuppressWildcards String>>? = null,
    val app_whitelist: List<String>? = null,
    val care_rules: Map<String, @JvmSuppressWildcards Any>? = null,
    val dialect_mode: String? = null,
    val volume: Int? = null,
    val font_scale: Float? = null
)

data class HermesSyncConfigResponse(
    val success: Boolean,
    val message: String,
    val updated_fields: List<String> = emptyList()
)

data class HermesHealthResponse(
    val status: String,
    val service: String,
    val version: String,
    val user_id: String,
    val timestamp: Long
)
