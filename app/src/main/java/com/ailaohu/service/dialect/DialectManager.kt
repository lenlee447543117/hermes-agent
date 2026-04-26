package com.ailaohu.service.dialect

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DialectManager @Inject constructor() {
    companion object {
        private const val TAG = "DialectManager"
    }

    private val shanghaiGreetings = listOf(
        "侬好呀", "早啊", "今朝好", "侬好啊"
    )
    private val shanghaiComfort = listOf(
        "勿要紧额", "慢慢来", "勿急勿急", "好额好额"
    )
    private val shanghaiConfirm = listOf(
        "好额", "晓得勒", "马上帮侬办", "来勒"
    )
    private val shanghaiError = listOf(
        "啊呀，出了一点小问题", "勿好意思，等一歇再试", "我脑子有点糊涂了"
    )
    private val shanghaiCare = listOf(
        "阿叔，今朝哪能啦？", "阿姨，有啥需要帮忙伐？",
        "今朝天气哪能？出去走走伐？", "饭吃过了伐？"
    )

    private val mandarinGreetings = listOf("您好", "早上好", "今天好")
    private val mandarinComfort = listOf("没关系", "慢慢来", "不着急")
    private val mandarinConfirm = listOf("好的", "知道了", "马上帮您办", "来了")
    private val mandarinError = listOf("抱歉，出了点问题", "不好意思，请稍后再试", "我暂时处理不了")
    private val mandarinCare = listOf("您好，今天怎么样？", "有什么需要帮忙的吗？", "今天天气不错，出去走走吗？")

    fun getGreeting(dialect: String = "shanghai"): String {
        return if (dialect == "shanghai") shanghaiGreetings.random() else mandarinGreetings.random()
    }

    fun getComfort(dialect: String = "shanghai"): String {
        return if (dialect == "shanghai") shanghaiComfort.random() else mandarinComfort.random()
    }

    fun getConfirm(dialect: String = "shanghai"): String {
        return if (dialect == "shanghai") shanghaiConfirm.random() else mandarinConfirm.random()
    }

    fun getError(dialect: String = "shanghai"): String {
        return if (dialect == "shanghai") shanghaiError.random() else mandarinError.random()
    }

    fun getCareMessage(dialect: String = "shanghai"): String {
        return if (dialect == "shanghai") shanghaiCare.random() else mandarinCare.random()
    }

    fun isShanghaiDialectExpression(text: String): Boolean {
        val shanghaiKeywords = listOf(
            "侬好", "哪能", "伐", "额", "勿", "啥", "今朝", "明朝", "阿叔", "阿姨",
            "囡恩", "小囡", "晓得", "勿要", "好额", "来勒", "呒没", "老好", "交关",
            "勿错", "灵额", "适意", "作啥", "啥人", "啥辰光", "哪能办", "帮帮忙"
        )
        return shanghaiKeywords.any { text.contains(it) }
    }

    fun detectDialect(text: String): String {
        return if (isShanghaiDialectExpression(text)) "shanghai" else "mandarin"
    }

    fun getDialectTtsLanguage(dialect: String): String {
        return if (dialect == "shanghai") "zh-CN-shanghai" else "zh-CN"
    }

    fun translateToDialect(text: String, targetDialect: String = "shanghai"): String {
        if (targetDialect != "shanghai") return text

        val replacements = mapOf(
            "你好" to "侬好",
            "好的" to "好额",
            "知道了" to "晓得勒",
            "没关系" to "勿要紧",
            "不用谢" to "勿客气",
            "再见" to "再会",
            "谢谢" to "谢谢侬",
            "对不起" to "勿好意思",
            "等一下" to "等一歇",
            "怎么办" to "哪能办",
            "什么" to "啥",
            "谁" to "啥人",
            "什么时候" to "啥辰光",
            "今天" to "今朝",
            "明天" to "明朝",
            "不行" to "勿来赛",
            "很好" to "老好额",
            "很多" to "交关多",
            "不要" to "勿要",
            "没有" to "呒没",
            "舒服" to "适意",
            "不错" to "勿错"
        )

        var result = text
        for ((mandarin, shanghai) in replacements) {
            result = result.replace(mandarin, shanghai)
        }
        return result
    }
}
