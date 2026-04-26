package com.ailaohu.service.habit

import android.util.Log
import com.ailaohu.data.local.prefs.AppPreferences
import com.ailaohu.data.repository.HermesRepository
import com.ailaohu.data.remote.dto.hermes.HermesDailyReport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class UserActionRecord(
    val intent: String,
    val target: String? = null,
    val success: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

@Singleton
class HabitTrackingService @Inject constructor(
    private val hermesRepository: HermesRepository,
    private val appPreferences: AppPreferences
) {
    companion object {
        private const val TAG = "HabitTracking"
        private const val SYNC_INTERVAL_MINUTES = 60L
        private const val LOCAL_BUFFER_SIZE = 100
    }

    private val actionBuffer = mutableListOf<UserActionRecord>()
    private var syncJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startTracking() {
        if (syncJob?.isActive == true) return

        syncJob = scope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL_MINUTES * 60 * 1000)
                syncActionsToCloud()
            }
        }
        Log.i(TAG, "Habit tracking started")
    }

    fun stopTracking() {
        syncJob?.cancel()
        syncJob = null
        scope.launch { syncActionsToCloud() }
        Log.i(TAG, "Habit tracking stopped")
    }

    @Synchronized
    fun recordAction(intent: String, target: String? = null, success: Boolean = true) {
        val record = UserActionRecord(intent = intent, target = target, success = success)
        actionBuffer.add(record)

        if (actionBuffer.size > LOCAL_BUFFER_SIZE) {
            actionBuffer.removeAt(0)
        }

        Log.d(TAG, "Action recorded: intent=$intent, target=$target, success=$success")
    }

    private suspend fun syncActionsToCloud() {
        val actionsToSync: List<UserActionRecord>
        synchronized(this) {
            if (actionBuffer.isEmpty()) return
            actionsToSync = actionBuffer.toList()
            actionBuffer.clear()
        }

        try {
            val userId = appPreferences.userId.first()
            Log.d(TAG, "Syncing ${actionsToSync.size} actions to cloud for user: $userId")

            for (action in actionsToSync) {
                hermesRepository.chat(
                    message = "[ACTION_LOG] intent=${action.intent}, target=${action.target}, success=${action.success}",
                    dialect = appPreferences.dialectMode.first()
                )
            }

            Log.i(TAG, "Actions synced successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync actions: ${e.message}")
            synchronized(this) {
                actionBuffer.addAll(0, actionsToSync)
            }
        }
    }

    suspend fun getDailyReport(): HermesDailyReport? {
        return try {
            val result = hermesRepository.getDailyReport()
            result.getOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get daily report: ${e.message}")
            null
        }
    }

    fun getRecentActionCount(): Int = actionBuffer.size
}
