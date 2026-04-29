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

data class WeatherInfo(
    val summary: String,
    val city: String,
    val temperature: String = "",
    val condition: String = "",
    val suggestion: String = ""
)

@Singleton
class WeatherInfoUseCase @Inject constructor(
    private val hermesRepository: HermesRepository,
    private val appPreferences: AppPreferences,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WeatherInfoUseCase"
    }

    suspend fun execute(city: String): Result<WeatherInfo> {
        return try {
            val cityName = if (city.isNotEmpty()) city else "上海"
            val dialect = appPreferences.dialectMode.first()
            val prompt = "请查询${cityName}今天的天气情况，包括温度、天气状况、是否需要带伞、穿衣建议。用简洁的口语化方式回答，不超过3句话。格式：温度XX度，天气XX，建议XX。"

            val result = hermesRepository.chat(prompt, dialect)

            result.fold(
                onSuccess = { response ->
                    val weatherInfo = WeatherInfo(
                        summary = response.reply,
                        city = cityName,
                        suggestion = extractSuggestion(response.reply)
                    )
                    Log.d(TAG, "Weather info obtained: $weatherInfo")
                    Result.success(weatherInfo)
                },
                onFailure = { e ->
                    Log.w(TAG, "Hermes weather query failed, using fallback: ${e.message}")
                    Result.success(fallbackWeather(cityName, dialect))
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Weather query error", e)
            Result.success(fallbackWeather(city, "mandarin"))
        }
    }

    fun navigateToWeatherApp(city: String) {
        try {
            val weatherIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("weather://${Uri.encode(city)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (weatherIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(weatherIntent)
                return
            }
        } catch (_: Exception) {}

        try {
            val fallbackIntent = context.packageManager.getLaunchIntentForPackage("com.weather.android")
            if (fallbackIntent != null) {
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallbackIntent)
                return
            }
        } catch (_: Exception) {}

        try {
            val searchUrl = "https://www.baidu.com/s?wd=${Uri.encode("${city}天气")}"
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to navigate to weather", e)
        }
    }

    private fun fallbackWeather(city: String, dialect: String): WeatherInfo {
        val summary = if (dialect == "shanghai") {
            "我帮侬查查${city}天气，稍等一下..."
        } else {
            "我帮您查查${city}的天气，请稍等..."
        }
        return WeatherInfo(summary = summary, city = city)
    }

    private fun extractSuggestion(reply: String): String {
        val patterns = listOf("建议", "记得", "注意", "带伞", "穿", "出门")
        for (pattern in patterns) {
            val index = reply.indexOf(pattern)
            if (index >= 0) {
                return reply.substring(index).take(30)
            }
        }
        return ""
    }
}
