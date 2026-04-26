package com.ailaohu.data.remote.api

import com.ailaohu.data.remote.dto.hermes.*
import retrofit2.http.*

interface HermesApiService {

    @POST("api/v1/chat")
    suspend fun chat(@Body request: HermesChatRequest): HermesChatResponse

    @POST("api/v1/action")
    suspend fun processAction(@Body request: HermesActionRequest): HermesActionResponse

    @GET("api/v1/habit/{userId}")
    suspend fun getHabitProfile(@Path("userId") userId: String): HermesHabitProfile

    @PUT("api/v1/habit/{userId}")
    suspend fun updateHabitProfile(
        @Path("userId") userId: String,
        @Body updates: Map<String, @JvmSuppressWildcards Any>
    ): HermesHabitProfile

    @GET("api/v1/habit/{userId}/report")
    suspend fun getDailyReport(@Path("userId") userId: String): HermesDailyReport

    @GET("api/v1/habit/{userId}/care")
    suspend fun checkProactiveCare(@Path("userId") userId: String): HermesCareMessage

    @POST("api/v1/sync-config")
    suspend fun syncConfig(@Body request: HermesSyncConfigRequest): HermesSyncConfigResponse

    @GET("health")
    suspend fun healthCheck(): HermesHealthResponse
}
