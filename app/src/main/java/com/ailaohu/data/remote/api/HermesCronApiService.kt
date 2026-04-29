package com.ailaohu.data.remote.api

import com.ailaohu.data.remote.dto.hermes.CronJobCreateRequest
import com.ailaohu.data.remote.dto.hermes.CronJobDeleteResponse
import com.ailaohu.data.remote.dto.hermes.CronJobListResponse
import com.ailaohu.data.remote.dto.hermes.CronJobSingleResponse
import retrofit2.http.*

interface HermesCronApiService {

    @GET("api/jobs")
    suspend fun listJobs(): CronJobListResponse

    @POST("api/jobs")
    suspend fun createJob(@Body request: CronJobCreateRequest): CronJobSingleResponse

    @GET("api/jobs/{jobId}")
    suspend fun getJob(@Path("jobId") jobId: String): CronJobSingleResponse

    @PATCH("api/jobs/{jobId}")
    suspend fun updateJob(
        @Path("jobId") jobId: String,
        @Body request: CronJobCreateRequest
    ): CronJobSingleResponse

    @DELETE("api/jobs/{jobId}")
    suspend fun deleteJob(@Path("jobId") jobId: String): CronJobDeleteResponse

    @POST("api/jobs/{jobId}/pause")
    suspend fun pauseJob(@Path("jobId") jobId: String): CronJobSingleResponse

    @POST("api/jobs/{jobId}/resume")
    suspend fun resumeJob(@Path("jobId") jobId: String): CronJobSingleResponse

    @POST("api/jobs/{jobId}/run")
    suspend fun runJob(@Path("jobId") jobId: String): CronJobSingleResponse
}
