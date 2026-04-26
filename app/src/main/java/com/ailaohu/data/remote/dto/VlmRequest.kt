package com.ailaohu.data.remote.dto

import com.google.gson.annotations.SerializedName

data class VlmRequest(
    @SerializedName("model") val model: String = "qwen-vl-max",
    @SerializedName("input") val input: VlmInput
)

data class VlmInput(
    @SerializedName("messages") val messages: List<VlmMessage>
)

data class VlmMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: List<VlmContent>
)

data class VlmContent(
    @SerializedName("type") val type: String,
    @SerializedName("image") val image: String? = null,
    @SerializedName("text") val text: String? = null
)
