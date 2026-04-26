package com.ailaohu.data.remote.api

import com.ailaohu.data.remote.dto.VlmRequest
import com.ailaohu.data.remote.dto.VlmResponse
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface VlmApiService {
    // 原来的API端点
    @POST("api/v1/services/aigc/multimodal-generation/generation")
    suspend fun analyzeScreen(
        @Header("Authorization") authorization: String,
        @Body request: VlmRequest
    ): VlmResponse
    
    // AutoGLM API端点
    @POST("v1/chat/completions")
    suspend fun analyzeScreenWithAutoGLM(
        @Header("Authorization") authorization: String,
        @Body request: VlmRequest
    ): VlmResponse
}
