package com.ailaohu.domain.usecase

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.ailaohu.data.repository.HermesRepository
import com.ailaohu.data.local.prefs.AppPreferences
import kotlinx.coroutines.flow.first
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class NewsInfo(
    val summary: String,
    val topic: String,
    val headlines: List<String> = emptyList()
)

@Singleton
class NewsInfoUseCase @Inject constructor(
    private val hermesRepository: HermesRepository,
    private val appPreferences: AppPreferences,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "NewsInfoUseCase"
        private val NEWS_APPS = listOf(
            "com.ss.android.article.news",
            "com.tencent.news",
            "com.sohu.news",
            "com.netease.news"
        )
    }

    suspend fun execute(topic: String): Result<NewsInfo> {
        return try {
            val topicText = if (topic.isNotEmpty()) topic else "今日热点"
            val dialect = appPreferences.dialectMode.first()
            val prompt = "请简要播报${topicText}新闻，列出3条最重要的新闻标题，每条不超过20个字。用口语化方式播报，像跟老人聊天一样。格式：第一条：XX。第二条：XX。第三条：XX。"

            val result = hermesRepository.chat(prompt, dialect)

            result.fold(
                onSuccess = { response ->
                    val headlines = extractHeadlines(response.reply)
                    val newsInfo = NewsInfo(
                        summary = response.reply,
                        topic = topicText,
                        headlines = headlines
                    )
                    Log.d(TAG, "News info obtained: ${headlines.size} headlines")
                    Result.success(newsInfo)
                },
                onFailure = { e ->
                    Log.w(TAG, "Hermes news query failed, using fallback: ${e.message}")
                    Result.success(fallbackNews(topicText, dialect))
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "News query error", e)
            Result.success(fallbackNews(topic, "mandarin"))
        }
    }

    fun navigateToNewsApp() {
        for (packageName in NEWS_APPS) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return
                }
            } catch (_: Exception) {}
        }

        try {
            val searchUrl = "https://www.baidu.com/s?wd=${Uri.encode("今日新闻热点")}"
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to navigate to news", e)
        }
    }

    private fun extractHeadlines(reply: String): List<String> {
        val headlines = mutableListOf<String>()
        val patterns = listOf(
            Regex("第[一二三四五]条[：:](.+?)(?:。|第|$)"),
            Regex("(\\d+)[.、：:](.+?)(?:。|\\d|$)"),
            Regex("[•·]\\s*(.+?)(?:[•·]|$)")
        )
        for (pattern in patterns) {
            pattern.findAll(reply).forEach { match ->
                val headline = match.groupValues.last().trim()
                if (headline.length in 4..40) {
                    headlines.add(headline)
                }
            }
            if (headlines.isNotEmpty()) break
        }
        return headlines.take(5)
    }

    private fun fallbackNews(topic: String, dialect: String): NewsInfo {
        val summary = if (dialect == "shanghai") {
            "我帮侬看看${topic}新闻，稍等一下..."
        } else {
            "我帮您看看${topic}的新闻，请稍等..."
        }
        return NewsInfo(summary = summary, topic = topic)
    }
}
