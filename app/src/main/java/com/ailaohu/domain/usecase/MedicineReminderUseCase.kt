package com.ailaohu.domain.usecase

import android.util.Log
import com.ailaohu.data.remote.api.HermesCronApiService
import com.ailaohu.data.remote.dto.hermes.CronJobCreateRequest
import com.ailaohu.data.remote.dto.hermes.CronJobDto
import com.ailaohu.data.local.prefs.AppPreferences
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class MedicineReminderInfo(
    val medicineName: String,
    val time: String,
    val dosage: String = "",
    val jobId: String? = null,
    val confirmed: Boolean = false
)

@Singleton
class MedicineReminderUseCase @Inject constructor(
    private val cronApiService: HermesCronApiService,
    private val appPreferences: AppPreferences
) {
    companion object {
        private const val TAG = "MedicineReminderUseCase"
    }

    suspend fun createReminder(
        medicineName: String,
        time: String,
        dosage: String = ""
    ): Result<MedicineReminderInfo> {
        return try {
            val userId = appPreferences.userId.first()
            val cronExpression = parseTimeToCron(time)
            val prompt = buildMedicineReminderPrompt(medicineName, dosage, time, userId)

            val request = CronJobCreateRequest(
                name = "吃药提醒-${medicineName}",
                schedule = cronExpression,
                prompt = prompt,
                deliver = "local",
                repeat = null
            )

            val response = cronApiService.createJob(request)
            val jobId = response.job.id
            Log.d(TAG, "Cron job created: $jobId, schedule: $cronExpression")

            Result.success(
                MedicineReminderInfo(
                    medicineName = medicineName,
                    time = time,
                    dosage = dosage,
                    jobId = jobId,
                    confirmed = true
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Create reminder failed, using local fallback", e)
            Result.success(
                MedicineReminderInfo(
                    medicineName = medicineName,
                    time = time,
                    dosage = dosage,
                    confirmed = false
                )
            )
        }
    }

    suspend fun deleteReminder(jobId: String): Result<Boolean> {
        return try {
            cronApiService.deleteJob(jobId)
            Log.d(TAG, "Cron job deleted: $jobId")
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Delete reminder failed", e)
            Result.failure(e)
        }
    }

    suspend fun listReminders(): Result<List<CronJobDto>> {
        return try {
            val response = cronApiService.listJobs()
            val reminderJobs = response.jobs.filter {
                it.name.startsWith("吃药提醒-") || it.prompt.contains("吃药")
            }
            Result.success(reminderJobs)
        } catch (e: Exception) {
            Log.e(TAG, "List reminders failed", e)
            Result.failure(e)
        }
    }

    private fun buildMedicineReminderPrompt(
        medicineName: String,
        dosage: String,
        time: String,
        userId: String
    ): String {
        val dosageText = if (dosage.isNotEmpty()) "，剂量$dosage" else ""
        return "提醒用户吃$medicineName$dosageText。用温暖关心的语气提醒，像家人一样。比如：'该吃${medicineName}了哦，别忘了~'。用户ID: $userId，提醒时间: $time"
    }

    private fun parseTimeToCron(time: String): String {
        if (time.isEmpty()) return "0 8 * * *"

        val timePatterns = listOf(
            Regex("(\\d+)点(\\d+)?分?") to { m: MatchResult ->
                val hour = m.groupValues[1].toInt().coerceIn(0, 23)
                val minute = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
                "$minute $hour * * *"
            },
            Regex("(\\d+):(\\d+)") to { m: MatchResult ->
                val hour = m.groupValues[1].toInt().coerceIn(0, 23)
                val minute = m.groupValues[2].toInt().coerceIn(0, 59)
                "$minute $hour * * *"
            },
            Regex("(早上|上午)(\\d+)点") to { m: MatchResult ->
                val hour = m.groupValues[2].toInt().coerceIn(0, 12)
                "0 $hour * * *"
            },
            Regex("(下午|晚上)(\\d+)点") to { m: MatchResult ->
                val hour = (m.groupValues[2].toInt() + 12).coerceIn(0, 23)
                "0 $hour * * *"
            },
            Regex("(\\d+)点半") to { m: MatchResult ->
                val hour = m.groupValues[1].toInt().coerceIn(0, 23)
                "30 $hour * * *"
            }
        )

        for ((pattern, converter) in timePatterns) {
            val match = pattern.find(time)
            if (match != null) return converter(match)
        }

        return "0 8 * * *"
    }
}
