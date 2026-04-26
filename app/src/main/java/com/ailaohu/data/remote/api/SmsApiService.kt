package com.ailaohu.data.remote.api

import retrofit2.http.POST
import retrofit2.http.Query

interface SmsApiService {
    @POST("/")
    suspend fun sendSms(
        @Query("phone") phone: String,
        @Query("message") message: String
    )
}
