package com.ailaohu.service.termux

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ailaohu.BuildConfig
import com.ailaohu.data.local.prefs.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class TermuxCommandResult(
    val success: Boolean,
    val output: String = "",
    val error: String? = null
)

data class TermuxCommand(
    val action: String,
    val params: Map<String, String> = emptyMap()
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
        private const val HERMES_AGENT_URL = "http://127.0.0.1:8642"

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
    }

    suspend fun isTermuxAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val intent = Intent()
            intent.setClassName(TERMUX_API_PACKAGE, TERMUX_API_RECEIVER)
            context.packageManager.getReceiverInfo(
                android.content.ComponentName(TERMUX_API_PACKAGE, TERMUX_API_RECEIVER),
                0
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Termux:API not available: ${e.message}")
            false
        }
    }

    suspend fun isHermesAgentRunning(): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofSeconds(3))
                .build()
            val request = Request.Builder()
                .url("$HERMES_AGENT_URL/health")
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    fun executeCommand(command: TermuxCommand): TermuxCommandResult {
        if (!ALLOWED_COMMANDS.contains(command.action)) {
            return TermuxCommandResult(
                success = false,
                error = "Command not allowed: ${command.action}"
            )
        }

        for (param in command.params.values) {
            for (pattern in DANGEROUS_PATTERNS) {
                if (param.contains(pattern, ignoreCase = true)) {
                    return TermuxCommandResult(
                        success = false,
                        error = "Dangerous pattern detected: $pattern"
                    )
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

    suspend fun executeViaAgent(prompt: String): TermuxCommandResult =
        withContext(Dispatchers.IO) {
            try {
                val userId = appPreferences.userId.first()
                val client = OkHttpClient.Builder()
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .readTimeout(java.time.Duration.ofSeconds(60))
                    .build()

                val requestBody = JSONObject().apply {
                    put("user_id", userId)
                    put("message", prompt)
                    put("dialect", "shanghai")
                }

                val request = Request.Builder()
                    .url("$HERMES_AGENT_URL/api/v1/chat")
                    .addHeader("Authorization", "Bearer ${BuildConfig.HERMES_API_KEY}")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody())
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val reply = json.optString("reply", "")
                    TermuxCommandResult(success = true, output = reply)
                } else {
                    TermuxCommandResult(
                        success = false,
                        error = "Agent returned ${response.code}: $body"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Agent execution failed", e)
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
                if (command.params.containsKey("sound")) {
                    intent.putExtra("com.termux.api.extra.NOTIFICATION_SOUND", true)
                }
                if (command.params.containsKey("ongoing")) {
                    intent.putExtra("com.termux.api.extra.NOTIFICATION_ONGOING", true)
                }
            }
            "TTS_SPEAK" -> {
                intent.setAction("com.termux.api.command.TTS_SPEAK")
                intent.putExtra("com.termux.api.extra.TTS_TEXT", command.params["text"] ?: "")
                if (command.params.containsKey("language")) {
                    intent.putExtra("com.termux.api.extra.TTS_LANGUAGE", command.params["language"])
                }
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
