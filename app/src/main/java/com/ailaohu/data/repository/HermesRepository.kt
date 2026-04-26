package com.ailaohu.data.repository

import android.util.Log
import com.ailaohu.data.remote.api.HermesApiService
import com.ailaohu.data.remote.dto.hermes.*
import com.ailaohu.data.local.prefs.AppPreferences
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HermesRepository @Inject constructor(
    private val hermesApiService: HermesApiService,
    private val appPreferences: AppPreferences
) {
    companion object {
        private const val TAG = "HermesRepo"
    }

    private suspend fun getUserId(): String {
        return appPreferences.userId.first()
    }

    suspend fun chat(message: String, dialect: String = "shanghai"): Result<HermesChatResponse> {
        return try {
            val userId = getUserId()
            val request = HermesChatRequest(
                user_id = userId,
                message = message,
                dialect = dialect
            )
            val response = hermesApiService.chat(request)
            Log.d(TAG, "Chat response: mode=${response.mode}, intent=${response.intent}")
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "Chat failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getHabitProfile(): Result<HermesHabitProfile> {
        return try {
            val userId = getUserId()
            val profile = hermesApiService.getHabitProfile(userId)
            Result.success(profile)
        } catch (e: Exception) {
            Log.e(TAG, "Get habit profile failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getDailyReport(): Result<HermesDailyReport> {
        return try {
            val userId = getUserId()
            val report = hermesApiService.getDailyReport(userId)
            Result.success(report)
        } catch (e: Exception) {
            Log.e(TAG, "Get daily report failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun checkProactiveCare(): Result<HermesCareMessage> {
        return try {
            val userId = getUserId()
            val care = hermesApiService.checkProactiveCare(userId)
            Result.success(care)
        } catch (e: Exception) {
            Log.e(TAG, "Check proactive care failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun syncConfig(request: HermesSyncConfigRequest): Result<HermesSyncConfigResponse> {
        return try {
            val response = hermesApiService.syncConfig(request)
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "Sync config failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun healthCheck(): Result<HermesHealthResponse> {
        return try {
            val response = hermesApiService.healthCheck()
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed: ${e.message}", e)
            Result.failure(e)
        }
    }
}
