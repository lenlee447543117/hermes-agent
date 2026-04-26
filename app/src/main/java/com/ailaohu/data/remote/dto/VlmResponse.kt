package com.ailaohu.data.remote.dto

import com.google.gson.annotations.SerializedName

data class VlmResponse(
    @SerializedName("output") val output: VlmOutput?,
    @SerializedName("usage") val usage: VlmUsage?,
    @SerializedName("request_id") val requestId: String?
)

data class VlmOutput(
    @SerializedName("choices") val choices: List<VlmChoice>?
)

data class VlmChoice(
    @SerializedName("message") val message: VlmResponseMessage?
)

data class VlmResponseMessage(
    @SerializedName("content") val content: List<VlmResponseContent>?
)

data class VlmResponseContent(
    @SerializedName("text") val text: String?
)

data class VlmUsage(
    @SerializedName("total_tokens") val totalTokens: Int?
)
