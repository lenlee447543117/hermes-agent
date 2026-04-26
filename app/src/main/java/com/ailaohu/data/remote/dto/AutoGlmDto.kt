package com.ailaohu.data.remote.dto

import com.google.gson.annotations.SerializedName

data class AutoGlmRequest(
    @SerializedName("model") val model: String = "GLM-4.6V-Flash",
    @SerializedName("messages") val messages: List<Message>,
    @SerializedName("max_tokens") val maxTokens: Int = 512,
    @SerializedName("temperature") val temperature: Float = 0.7f,
    @SerializedName("stream") val stream: Boolean = false
) {
    data class Message(
        @SerializedName("role") val role: String,
        @SerializedName("content") val content: Any, // 可以是String或List<ContentPart>
        @SerializedName("images") val images: List<String>? = null
    )

    data class ContentPart(
        @SerializedName("type") val type: String, // "text", "image_url", "input_audio"
        @SerializedName("text") val text: String? = null,
        @SerializedName("image_url") val imageUrl: ImageUrl? = null,
        @SerializedName("input_audio") val inputAudio: InputAudio? = null
    )

    data class ImageUrl(
        @SerializedName("url") val url: String // base64图片或图片URL
    )

    data class InputAudio(
        @SerializedName("data") val data: String, // base64编码的音频数据
        @SerializedName("format") val format: String = "wav" // 音频格式
    )
}

data class AutoGlmResponse(
    @SerializedName("id") val id: String? = null,
    @SerializedName("request_id") val requestId: String? = null,
    @SerializedName("created") val created: Long? = null,
    @SerializedName("model") val model: String? = null,
    @SerializedName("choices") val choices: List<Choice>? = null,
    @SerializedName("usage") val usage: Usage? = null,
    @SerializedName("video_result") val videoResult: List<VideoResult>? = null,
    @SerializedName("web_search") val webSearch: List<WebSearchResult>? = null
) {
    data class Choice(
        @SerializedName("index") val index: Int = 0,
        @SerializedName("message") val message: Message? = null,
        @SerializedName("finish_reason") val finishReason: String? = null
    )

    data class Message(
        @SerializedName("role") val role: String? = null,
        @SerializedName("content") val content: String? = null,
        @SerializedName("reasoning_content") val reasoningContent: String? = null
    )

    data class Usage(
        @SerializedName("prompt_tokens") val promptTokens: Int? = null,
        @SerializedName("completion_tokens") val completionTokens: Int? = null,
        @SerializedName("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null,
        @SerializedName("total_tokens") val totalTokens: Int? = null
    )

    data class PromptTokensDetails(
        @SerializedName("cached_tokens") val cachedTokens: Int? = null
    )

    data class VideoResult(
        @SerializedName("url") val url: String? = null,
        @SerializedName("cover_image_url") val coverImageUrl: String? = null
    )

    data class WebSearchResult(
        @SerializedName("icon") val icon: String? = null,
        @SerializedName("title") val title: String? = null,
        @SerializedName("link") val link: String? = null,
        @SerializedName("media") val media: String? = null,
        @SerializedName("publish_date") val publishDate: String? = null,
        @SerializedName("content") val content: String? = null,
        @SerializedName("refer") val refer: String? = null
    )
}
