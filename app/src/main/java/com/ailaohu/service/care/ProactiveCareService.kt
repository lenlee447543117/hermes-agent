package com.ailaohu.service.care

import android.util.Log
import com.ailaohu.data.local.prefs.AppPreferences
import com.ailaohu.data.remote.dto.hermes.HermesCareMessage
import com.ailaohu.data.repository.HermesRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProactiveCareService @Inject constructor(
    private val hermesRepository: HermesRepository,
    private val appPreferences: AppPreferences
) {
    companion object {
        private const val TAG = "ProactiveCare"
        private const val CHECK_INTERVAL_MINUTES = 30L
        private const val INACTIVITY_THRESHOLD_HOURS = 48
    }

    private var careJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastUserInteractionTime: Long = System.currentTimeMillis()
    private var lastCareMessageTime: Long = 0

    fun startMonitoring() {
        if (careJob?.isActive == true) return

        careJob = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MINUTES * 60 * 1000)
                checkAndSendCare()
            }
        }
        Log.i(TAG, "Proactive care monitoring started")
    }

    fun stopMonitoring() {
        careJob?.cancel()
        careJob = null
        Log.i(TAG, "Proactive care monitoring stopped")
    }

    fun recordUserInteraction() {
        lastUserInteractionTime = System.currentTimeMillis()
    }

    private suspend fun checkAndSendCare() {
        val now = System.currentTimeMillis()
        val inactiveHours = (now - lastUserInteractionTime) / (1000 * 60 * 60)

        if (inactiveHours < INACTIVITY_THRESHOLD_HOURS) {
            Log.d(TAG, "User active within $inactiveHours hours, no care needed")
            return
        }

        if (now - lastCareMessageTime < 4 * 60 * 60 * 1000) {
            Log.d(TAG, "Last care message sent less than 4 hours ago, skipping")
            return
        }

        try {
            val userId = appPreferences.userId.first()
            val result = hermesRepository.checkProactiveCare()

            result.fold(
                onSuccess = { care ->
                    if (care.message.isNotEmpty() && care.care_type != "none") {
                        Log.i(TAG, "Sending proactive care: ${care.message}")
                        lastCareMessageTime = now
                        _careMessageFlow.emit(care)
                    }
                },
                onFailure = { e ->
                    Log.w(TAG, "Proactive care check failed: ${e.message}")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Proactive care error: ${e.message}", e)
        }
    }

    private val _careMessageFlow = MutableSharedFlow<HermesCareMessage>(replay = 0)
    val careMessageFlow: SharedFlow<HermesCareMessage> = _careMessageFlow.asSharedFlow()
}
