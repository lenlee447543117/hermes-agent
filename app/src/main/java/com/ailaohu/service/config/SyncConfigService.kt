package com.ailaohu.service.config

import android.util.Log
import com.ailaohu.data.local.prefs.AppPreferences
import com.ailaohu.data.remote.dto.hermes.HermesSyncConfigRequest
import com.ailaohu.data.repository.HermesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

data class SyncConfig(
    val contacts: List<ContactConfig> = emptyList(),
    val appWhitelist: List<String> = emptyList(),
    val careRules: CareRulesConfig = CareRulesConfig(),
    val dialectMode: String = "shanghai",
    val volume: Int = 70,
    val fontScale: Float = 1.5f
)

data class ContactConfig(
    val name: String,
    val phone: String = "",
    val callType: String = "video",
    val isFavorite: Boolean = false
)

data class CareRulesConfig(
    val medicineReminderEnabled: Boolean = true,
    val weatherReminderEnabled: Boolean = true,
    val inactivityAlertHours: Int = 48,
    val dailyReportEnabled: Boolean = true,
    val customReminders: List<String> = emptyList()
)

@Singleton
class SyncConfigService @Inject constructor(
    private val hermesRepository: HermesRepository,
    private val appPreferences: AppPreferences
) {
    companion object {
        private const val TAG = "SyncConfig"
    }

    private var cachedConfig: SyncConfig? = null

    suspend fun fetchRemoteConfig(): Result<SyncConfig> {
        return try {
            val userId = appPreferences.userId.first()
            val profileResult = hermesRepository.getHabitProfile()

            profileResult.fold(
                onSuccess = { profile ->
                    val config = SyncConfig(
                        dialectMode = profile.dialect_preference,
                        volume = profile.volume_preference,
                        fontScale = profile.font_scale,
                        contacts = profile.frequent_contacts.map {
                            ContactConfig(name = it)
                        }
                    )
                    cachedConfig = config
                    applyConfig(config)
                    Result.success(config)
                },
                onFailure = { e ->
                    Log.e(TAG, "Failed to fetch remote config: ${e.message}")
                    Result.failure(e)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Sync config error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun pushConfig(config: SyncConfig): Result<Boolean> {
        return try {
            val userId = appPreferences.userId.first()
            val request = HermesSyncConfigRequest(
                user_id = userId,
                contacts = config.contacts.map { mapOf("name" to it.name, "phone" to it.phone, "callType" to it.callType) },
                app_whitelist = config.appWhitelist,
                care_rules = mapOf(
                    "medicineReminderEnabled" to config.careRules.medicineReminderEnabled,
                    "weatherReminderEnabled" to config.careRules.weatherReminderEnabled,
                    "inactivityAlertHours" to config.careRules.inactivityAlertHours,
                    "dailyReportEnabled" to config.careRules.dailyReportEnabled
                ),
                dialect_mode = config.dialectMode,
                volume = config.volume,
                font_scale = config.fontScale
            )

            val result = hermesRepository.syncConfig(request)
            result.fold(
                onSuccess = { response ->
                    Log.i(TAG, "Config synced: ${response.updated_fields}")
                    cachedConfig = config
                    applyConfig(config)
                    Result.success(true)
                },
                onFailure = { e ->
                    Log.e(TAG, "Push config failed: ${e.message}")
                    Result.failure(e)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Push config error: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun applyConfig(config: SyncConfig) {
        appPreferences.setDialectMode(config.dialectMode)

        Log.i(TAG, "Config applied: dialect=${config.dialectMode}, volume=${config.volume}, fontScale=${config.fontScale}")
    }

    fun getCachedConfig(): SyncConfig? = cachedConfig

    fun getDefaultConfig(): SyncConfig = SyncConfig(
        contacts = emptyList(),
        appWhitelist = listOf(
            "com.tencent.mm",
            "com.eg.android.AlipayGphone",
            "com.baidu.BaiduMap",
            "com.autonavi.minimap"
        ),
        careRules = CareRulesConfig(
            medicineReminderEnabled = true,
            weatherReminderEnabled = true,
            inactivityAlertHours = 48,
            dailyReportEnabled = true
        ),
        dialectMode = "shanghai",
        volume = 70,
        fontScale = 1.5f
    )
}
