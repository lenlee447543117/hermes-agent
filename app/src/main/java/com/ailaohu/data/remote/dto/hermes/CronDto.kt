package com.ailaohu.data.remote.dto.hermes

import com.google.gson.annotations.SerializedName

data class CronJobCreateRequest(
    val name: String,
    val schedule: String,
    val prompt: String = "",
    val deliver: String = "local",
    val skills: List<String>? = null,
    val repeat: Int? = null
)

data class CronJobDto(
    val id: String,
    val name: String,
    val prompt: String = "",
    val schedule: CronScheduleDto = CronScheduleDto(),
    val schedule_display: String = "",
    val skills: List<String> = emptyList(),
    val enabled: Boolean = true,
    val state: String = "scheduled",
    val deliver: String = "local",
    val repeat: CronRepeatDto = CronRepeatDto(),
    val created_at: String? = null,
    val next_run_at: String? = null,
    val last_run_at: String? = null,
    val last_status: String? = null,
    val last_error: String? = null
)

data class CronScheduleDto(
    val kind: String = "cron",
    val cron: String = "",
    val display: String = ""
)

data class CronRepeatDto(
    val times: Int? = null,
    val completed: Int = 0
)

data class CronJobListResponse(
    val jobs: List<CronJobDto> = emptyList()
)

data class CronJobSingleResponse(
    val job: CronJobDto
)

data class CronJobDeleteResponse(
    val success: Boolean = false,
    val error: String? = null
)
