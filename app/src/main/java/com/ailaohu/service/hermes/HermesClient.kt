package com.ailaohu.service.hermes

import android.util.Log
import com.ailaohu.BuildConfig
import com.ailaohu.service.termux.TermuxBridge
import kotlinx.coroutines.Dispatchers
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

data class VoiceTaskResult(
    val taskId: String,
    val status: String,
    val reply: String = "",
    val intent: String = "",
    val source: String = "",
    val clarification: JSONObject? = null
)

data class AgentStatusInfo(
    val state: AgentState = AgentState.IDLE,
    val deviceConnected: Boolean = false,
    val currentTaskId: String = "",
    val memoryContacts: Int = 0,
    val cachedActions: Int = 0,
    val offlineTemplates: Int = 0,
    val version: String = ""
)

@Singleton
class HermesClient @Inject constructor(
    private val termuxBridge: TermuxBridge
) {
    companion object {
        private const val TAG = "HermesClient"
        private const val LOCAL_AGENT_URL = "http://127.0.0.1:8000"
        private const val CLOUD_HERMES_URL = "http://192.168.3.34:8642"
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .readTimeout(java.time.Duration.ofSeconds(60))
            .build()
    }

    suspend fun sendVoiceCommand(text: String, userId: String = "default", dialect: String = "shanghai"): VoiceTaskResult =
        withContext(Dispatchers.IO) {
            try {
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

                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val status = json.optString("status", "")
                    val taskId = json.optString("task_id", "")

                    if (status == "accepted") {
                        return@withContext VoiceTaskResult(
                            taskId = taskId,
                            status = "accepted"
                        )
                    }

                    val reply = json.optString("reply", "")
                    val intent = json.optString("intent", "")
                    val source = json.optString("source", "")
                    val clarification = json.optJSONObject("clarification")
                    VoiceTaskResult(taskId, status, reply, intent, source, clarification)
                } else {
                    VoiceTaskResult("", "error", "Agent returned ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Voice command failed: ${e.message}")
                VoiceTaskResult("", "error", e.message ?: "Unknown error")
            }
        }

    suspend fun cancelTask(taskId: String = ""): Boolean = withContext(Dispatchers.IO) {
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
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Cancel failed: ${e.message}")
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
                    memoryContacts = json.optInt("memory_contacts", 0),
                    cachedActions = json.optInt("cached_actions", 0),
                    offlineTemplates = json.optInt("offline_templates", 0),
                    version = json.optString("version", "")
                )
            } else {
                AgentStatusInfo()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Get status failed: ${e.message}")
            AgentStatusInfo()
        }
    }

    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$LOCAL_AGENT_URL/health")
                .build()
            val response = httpClient.newCall(request).execute()
            response.isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getTaskResult(taskId: String): VoiceTaskResult? = withContext(Dispatchers.IO) {
        try {
            val status = getAgentStatus()
            if (status.currentTaskId == taskId) {
                return@withContext VoiceTaskResult(taskId, "RUNNING")
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
