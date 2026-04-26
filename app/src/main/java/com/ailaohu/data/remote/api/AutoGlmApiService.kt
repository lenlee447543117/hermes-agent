package com.ailaohu.data.remote.api

import com.ailaohu.data.remote.dto.AutoGlmRequest
import com.ailaohu.data.remote.dto.AutoGlmResponse
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface AutoGlmApiService {
    @POST("chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: AutoGlmRequest
    ): AutoGlmResponse
}
