package com.ailaohu.service.termux

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ailaohu.BuildConfig
import com.ailaohu.data.local.prefs.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

enum class AgentState {
    IDLE,
    LISTENING,
    THINKING,
    EXECUTING,
    SUCCESS,
    ERROR
}

data class TermuxCommandResult(
    val success: Boolean,
    val output: String = "",
    val error: String? = null,
    val status: String = "",
    val intent: String = "",
    val source: String = "",
    val taskId: String = "",
    val clarification: JSONObject? = null
)

data class TermuxCommand(
    val action: String,
    val params: Map<String, String> = emptyMap()
)

data class AgentStatusInfo(
    val state: AgentState = AgentState.IDLE,
    val deviceConnected: Boolean = false,
    val currentTaskId: String = "",
    val voskAvailable: Boolean = false,
    val autoglmAvailable: Boolean = false,
    val privacyFilterAvailable: Boolean = false,
    val memoryContacts: Int = 0,
    val cachedActions: Int = 0,
    val offlineTemplates: Int = 0,
    val version: String = ""
)

@Singleton
class TermuxBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences
) {
    companion object {
        private const val TAG = "TermuxBridge"
        private const val TERMUX_API_PACKAGE = "com.termux.api"
        private const val TERMUX_API_RECEIVER = "com.termux.api.TermuxApiReceiver"
        private const val LOCAL_AGENT_URL = "http://127.0.0.1:8000"

        private val ALLOWED_COMMANDS = setOf(
            "VOLUME", "CAMERA", "NOTIFICATION", "TTS_SPEAK", "TTS_STOP",
            "LOCATION", "MEDIA_PLAYER", "BATTERY_STATUS", "WIFI",
            "SPEECH_TO_TEXT", "VIBRATE", "CLIPBOARD", "SENSOR"
        )

        private val DANGEROUS_PATTERNS = listOf(
            "rm -rf", "reboot", "su ", "sudo ", "chmod 777",
            "dd if=", "mkfs", "format", "factory", "reset",
            "/system/", "/data/", "pm uninstall", "pm clear"
        )

        private const val TASK_POLL_INTERVAL_MS = 500L
        private const val TASK_POLL_MAX_ATTEMPTS = 60
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .readTimeout(java.time.Duration.ofSeconds(60))
            .build()
    }

    suspend fun isTermuxAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val componentName = android.content.ComponentName(TERMUX_API_PACKAGE, TERMUX_API_RECEIVER)
            context.packageManager.getReceiverInfo(componentName, 0)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Termux:API not available: ${e.message}")
            false
        }
    }

    suspend fun isAgentRunning(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$LOCAL_AGENT_URL/health").build()
            httpClient.newCall(request).execute().isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getAgentStatus(): AgentStatusInfo = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$LOCAL_AGENT_URL/api/v1/status")
                .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "")
                AgentStatusInfo(
                    state = try {
                        AgentState.valueOf(json.optString("state", "IDLE"))
                    } catch (_: Exception) {
                        AgentState.IDLE
                    },
                    deviceConnected = json.optBoolean("device_connected", false),
                    currentTaskId = json.optString("current_task_id", ""),
                    voskAvailable = json.optBoolean("vosk_available", false),
                    autoglmAvailable = json.optBoolean("autoglm_available", false),
                    privacyFilterAvailable = json.optBoolean("privacy_filter_available", false),
                    memoryContacts = json.optInt("memory_contacts", 0),
                    cachedActions = json.optInt("cached_actions", 0),
                    offlineTemplates = json.optInt("offline_templates", 0),
                    version = json.optString("version", "")
                )
            } else {
                AgentStatusInfo()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Get agent status failed: ${e.message}")
            AgentStatusInfo()
        }
    }

    suspend fun sendVoiceCommand(text: String, dialect: String = "shanghai"): TermuxCommandResult =
        withContext(Dispatchers.IO) {
            try {
                val userId = appPreferences.userId.first()
                val requestBody = JSONObject().apply {
                    put("text", text)
                    put("user_id", userId)
                    put("dialect", dialect)
                }

                val request = Request.Builder()
                    .url("$LOCAL_AGENT_URL/api/v1/voice")
                    .addHeader("Authorization", "Bearer ${BuildConfig.HERMES_API_KEY}")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody())
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext TermuxCommandResult(
                        success = false,
                        error = "Voice API returned ${response.code}: $body"
                    )
                }

                val json = JSONObject(body)
                val status = json.optString("status", "")
                val taskId = json.optString("task_id", "")

                if (status == "accepted" && taskId.isNotEmpty()) {
                    val taskResult = pollTaskResult(taskId)
                    taskResult
                } else {
                    val reply = json.optString("reply", "")
                    TermuxCommandResult(
                        success = true, output = reply, status = status,
                        intent = json.optString("intent", ""),
                        source = json.optString("source", ""),
                        clarification = json.optJSONObject("clarification")
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Voice command failed", e)
                TermuxCommandResult(success = false, error = e.message)
            }
        }

    private suspend fun pollTaskResult(taskId: String): TermuxCommandResult =
        withContext(Dispatchers.IO) {
            repeat(TASK_POLL_MAX_ATTEMPTS) {
                try {
                    val request = Request.Builder()
                        .url("$LOCAL_AGENT_URL/api/v1/task/$taskId")
                        .build()
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val json = JSONObject(response.body?.string() ?: "")
                        val taskStatus = json.optString("status", "")

                        if (taskStatus == "RUNNING" || taskStatus == "UNKNOWN") {
                            delay(TASK_POLL_INTERVAL_MS)
                            return@repeat
                        }

                        val reply = json.optString("reply", "")
                        return@withContext TermuxCommandResult(
                            success = taskStatus != "ERROR",
                            output = reply,
                            status = taskStatus,
                            intent = json.optString("intent", ""),
                            source = json.optString("source", ""),
                            taskId = taskId,
                            clarification = json.optJSONObject("clarification")
                        )
                    }
                } catch (_: Exception) {}
                delay(TASK_POLL_INTERVAL_MS)
            }
            TermuxCommandResult(
                success = false, error = "Task polling timed out",
                taskId = taskId, status = "TIMEOUT"
            )
        }

    suspend fun cancelTask(taskId: String = ""): TermuxCommandResult = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                if (taskId.isNotEmpty()) put("task_id", taskId)
            }

            val request = Request.Builder()
                .url("$LOCAL_AGENT_URL/api/v1/cancel")
                .addHeader("Authorization", "Bearer ${BuildConfig.HERMES_API_KEY}")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody())
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "")
                TermuxCommandResult(
                    success = true,
                    output = "已停止操作",
                    status = "cancelled",
                    taskId = json.optString("task_id", taskId)
                )
            } else {
                TermuxCommandResult(success = false, error = "Cancel failed: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cancel failed", e)
            TermuxCommandResult(success = false, error = e.message)
        }
    }

    suspend fun executeAutoGLMAction(action: String, params: Map<String, Any> = emptyMap()): TermuxCommandResult =
        withContext(Dispatchers.IO) {
            try {
                val requestBody = JSONObject().apply {
                    put("action", action)
                    put("params", JSONObject(params))
                }

                val request = Request.Builder()
                    .url("$LOCAL_AGENT_URL/api/v1/execute/autoglm")
                    .addHeader("Authorization", "Bearer ${BuildConfig.HERMES_API_KEY}")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody())
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    TermuxCommandResult(
                        success = json.optBoolean("success", false),
                        output = json.optString("description", ""),
                        source = json.optString("source", "autoglm")
                    )
                } else {
                    TermuxCommandResult(
                        success = false,
                        error = "AutoGLM execute returned ${response.code}: $body"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "AutoGLM execute failed", e)
                TermuxCommandResult(success = false, error = e.message)
            }
        }

    suspend fun saveContact(nickname: String, realName: String, phone: String? = null, relation: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val requestBody = JSONObject().apply {
                    put("nickname", nickname)
                    put("real_name", realName)
                    phone?.let { put("phone", it) }
                    relation?.let { put("relation", it) }
                }

                val request = Request.Builder()
                    .url("$LOCAL_AGENT_URL/api/v1/memory/contacts")
                    .addHeader("Authorization", "Bearer ${BuildConfig.HERMES_API_KEY}")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody())
                    .build()

                httpClient.newCall(request).execute().isSuccessful
            } catch (e: Exception) {
                Log.e(TAG, "Save contact failed", e)
                false
            }
        }

    fun executeCommand(command: TermuxCommand): TermuxCommandResult {
        if (!ALLOWED_COMMANDS.contains(command.action)) {
            return TermuxCommandResult(success = false, error = "Command not allowed: ${command.action}")
        }

        for (param in command.params.values) {
            for (pattern in DANGEROUS_PATTERNS) {
                if (param.contains(pattern, ignoreCase = true)) {
                    return TermuxCommandResult(success = false, error = "Dangerous pattern detected: $pattern")
                }
            }
        }

        return try {
            val intent = buildTermuxIntent(command)
            context.sendBroadcast(intent)
            Log.d(TAG, "Command executed: ${command.action}")
            TermuxCommandResult(success = true, output = "Command sent")
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: ${command.action}", e)
            TermuxCommandResult(success = false, error = e.message)
        }
    }

    private fun buildTermuxIntent(command: TermuxCommand): Intent {
        val intent = Intent()
        intent.setClassName(TERMUX_API_PACKAGE, TERMUX_API_RECEIVER)

        when (command.action) {
            "VOLUME" -> {
                intent.setAction("com.termux.api.command.VOLUME")
                intent.putExtra("com.termux.api.extra.VOLUME_STREAM", command.params["stream"] ?: "music")
                intent.putExtra("com.termux.api.extra.VOLUME_LEVEL", (command.params["level"] ?: "10").toInt())
            }
            "CAMERA" -> {
                intent.setAction("com.termux.api.command.CAMERA_PHOTO")
                intent.putExtra("com.termux.api.extra.CAMERA_ID", (command.params["camera_id"] ?: "0").toInt())
                intent.putExtra("com.termux.api.extra.FILEPATH", command.params["path"] ?: "/sdcard/DCIM/hulao_photo_${System.currentTimeMillis()}.jpg")
            }
            "NOTIFICATION" -> {
                intent.setAction("com.termux.api.command.NOTIFICATION")
                intent.putExtra("com.termux.api.extra.NOTIFICATION_TITLE", command.params["title"] ?: "AI沪老提醒")
                intent.putExtra("com.termux.api.extra.NOTIFICATION_CONTENT", command.params["content"] ?: "")
                intent.putExtra("com.termux.api.extra.NOTIFICATION_ID", command.params["id"] ?: "hulao_default")
            }
            "TTS_SPEAK" -> {
                intent.setAction("com.termux.api.command.TTS_SPEAK")
                intent.putExtra("com.termux.api.extra.TTS_TEXT", command.params["text"] ?: "")
            }
            "TTS_STOP" -> {
                intent.setAction("com.termux.api.command.TTS_STOP")
            }
            "LOCATION" -> {
                intent.setAction("com.termux.api.command.LOCATION")
                intent.putExtra("com.termux.api.extra.LOCATION_PROVIDER", command.params["provider"] ?: "network")
                intent.putExtra("com.termux.api.extra.LOCATION_REQUEST_ONCE", true)
            }
            "MEDIA_PLAYER" -> {
                intent.setAction("com.termux.api.command.MEDIA_PLAYER")
                val subCommand = command.params["command"] ?: "play"
                intent.putExtra("com.termux.api.extra.MEDIA_PLAYER_COMMAND", subCommand)
                if (subCommand == "play" && command.params.containsKey("path")) {
                    intent.putExtra("com.termux.api.extra.MEDIA_PLAYER_FILE", command.params["path"])
                }
            }
            "SPEECH_TO_TEXT" -> {
                intent.setAction("com.termux.api.command.SPEECH_TO_TEXT")
            }
            "VIBRATE" -> {
                intent.setAction("com.termux.api.command.VIBRATE")
                intent.putExtra("com.termux.api.extra.VIBRATE_DURATION", (command.params["duration"] ?: "200").toInt())
            }
            "CLIPBOARD" -> {
                intent.setAction("com.termux.api.command.CLIPBOARD")
                if (command.params.containsKey("set")) {
                    intent.putExtra("com.termux.api.extra.CLIPBOARD_TEXT", command.params["set"])
                }
            }
            "BATTERY_STATUS" -> {
                intent.setAction("com.termux.api.command.BATTERY_STATUS")
            }
            "WIFI" -> {
                intent.setAction("com.termux.api.command.WIFI")
                if (command.params.containsKey("enable")) {
                    intent.putExtra("com.termux.api.extra.WIFI_ENABLE", command.params["enable"] == "true")
                }
            }
            "SENSOR" -> {
                intent.setAction("com.termux.api.command.SENSOR")
                intent.putExtra("com.termux.api.extra.SENSOR_TYPE", command.params["type"] ?: "all")
                intent.putExtra("com.termux.api.extra.SENSOR_DELAY", (command.params["delay"] ?: "1000").toInt())
                intent.putExtra("com.termux.api.extra.SENSOR_LIMIT", 1)
            }
        }

        return intent
    }
}
