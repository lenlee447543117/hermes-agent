package com.ailaohu.service.voice

import android.util.Log
import com.ailaohu.data.local.entity.ContactEntity
import com.ailaohu.data.repository.ContactRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommandNormalizer @Inject constructor(
    private val contactRepository: ContactRepository
) {
    companion object {
        private const val TAG = "CmdNormalizer"

        private val APP_ALIAS_MAP = linkedMapOf(
            "围信" to "微信", "威信" to "微信", "为信" to "微信",
            "抖声" to "抖音", "斗音" to "抖音",
            "农药" to "王者荣耀",
            "掏宝" to "淘宝", "淘包" to "淘宝",
            "支付保" to "支付宝", "之付宝" to "支付宝",
            "拼叨叨" to "拼多多",
            "高的" to "高德地图",
            "美图" to "美团", "美图团" to "美团",
            "饿了马" to "饿了么",
            "小红叔" to "小红书", "小红树" to "小红书",
            "京冬" to "京东", "惊动" to "京东",
            "快首" to "快手", "块手" to "快手",
            "百渡" to "百度", "摆渡" to "百度",
            "知呼" to "知乎", "吱呼" to "知乎",
            "微薄" to "微博", "围脖" to "微博",
            "钉丁" to "钉钉", "盯盯" to "钉钉",
            "飞输" to "飞书", "非书" to "飞书",
            "爱奇异" to "爱奇艺", "爱齐艺" to "爱奇艺",
            "油库" to "优酷",
            "腾迅" to "腾讯", "疼讯" to "腾讯"
        )

        private val ACTION_ALIAS_MAP = linkedMapOf(
            "打点话" to "打电话", "大电话" to "打电话",
            "打滴" to "打车", "打低" to "打车",
            "开灯" to "打开手电筒", "关灯" to "关闭手电筒",
            "开蓝呀" to "打开蓝牙", "关蓝呀" to "关闭蓝牙",
            "开网" to "打开WiFi", "连网" to "打开WiFi"
        )

        private val PINYIN_MAP = mapOf(
            "m" to listOf("妈", "明", "美", "梅", "敏", "马"),
            "b" to listOf("爸", "波", "斌", "兵", "宝"),
            "g" to listOf("哥", "刚", "国", "桂", "高"),
            "j" to listOf("姐", "军", "建", "静", "杰"),
            "d" to listOf("弟", "东", "大", "德", "丹"),
            "s" to listOf("叔", "婶", "嫂", "生", "顺"),
            "y" to listOf("爷", "姨", "英", "玉", "云"),
            "l" to listOf("老", "李", "刘", "林", "丽"),
            "w" to listOf("王", "吴", "魏", "武", "万"),
            "z" to listOf("张", "赵", "周", "郑", "朱"),
            "h" to listOf("黄", "胡", "何", "贺", "韩"),
            "x" to listOf("小", "许", "徐", "谢", "薛"),
            "c" to listOf("陈", "曹", "程", "蔡", "崔"),
            "f" to listOf("冯", "方", "付", "范", "樊")
        )
    }

    @Volatile
    private var cachedContacts: List<ContactEntity> = emptyList()
    @Volatile
    private var contactsLoaded = false

    suspend fun preloadContacts() {
        try {
            cachedContacts = contactRepository.getActiveContacts().first()
            contactsLoaded = true
            Log.d(TAG, "预加载${cachedContacts.size}个联系人")
        } catch (e: Exception) {
            Log.e(TAG, "预加载联系人失败", e)
        }
    }

    fun normalize(rawText: String): String {
        var result = rawText.trim()
        if (result.isEmpty()) return result

        result = applyAppAliases(result)
        result = applyActionAliases(result)
        result = resolveSingleLetterContact(result)
        result = resolveFuzzyContact(result)

        Log.d(TAG, "纠错: '$rawText' -> '$result'")
        return result
    }

    private fun applyAppAliases(text: String): String {
        var result = text
        for ((alias, correct) in APP_ALIAS_MAP) {
            if (result.contains(alias)) {
                result = result.replace(alias, correct)
            }
        }
        return result
    }

    private fun applyActionAliases(text: String): String {
        var result = text
        for ((alias, correct) in ACTION_ALIAS_MAP) {
            if (result.contains(alias)) {
                result = result.replace(alias, correct)
            }
        }
        return result
    }

    private fun resolveSingleLetterContact(text: String): String {
        val callPatterns = listOf(
            Regex("打电话给([a-zA-Z])"),
            Regex("给([a-zA-Z])打电话"),
            Regex("打视频给([a-zA-Z])"),
            Regex("给([a-zA-Z])打视频"),
            Regex("联系([a-zA-Z])"),
            Regex("呼叫([a-zA-Z])")
        )

        for (pattern in callPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                val letter = match.groupValues[1].lowercase()
                val candidates = PINYIN_MAP[letter] ?: continue
                val resolved = resolveFromContacts(candidates)
                if (resolved != null) {
                    return text.replace(match.groupValues[1], resolved)
                }
                return text.replace(match.groupValues[1], candidates.first())
            }
        }
        return text
    }

    private fun resolveFuzzyContact(text: String): String {
        if (!contactsLoaded || cachedContacts.isEmpty()) return text

        val contactPatterns = listOf(
            Regex("打电话给(.+?)(的|$)"),
            Regex("给(.+?)打电话"),
            Regex("打视频给(.+?)(的|$)"),
            Regex("给(.+?)打视频"),
            Regex("联系(.+?)(的|$)"),
            Regex("呼叫(.+?)(的|$)")
        )

        for (pattern in contactPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                val spokenName = match.groupValues[1].trim()
                if (spokenName.length > 1) {
                    val matched = fuzzyMatchContact(spokenName)
                    if (matched != null && matched != spokenName) {
                        return text.replace(spokenName, matched)
                    }
                }
            }
        }
        return text
    }

    private fun resolveFromContacts(candidates: List<String>): String? {
        if (!contactsLoaded) return null
        for (candidate in candidates) {
            val contact = cachedContacts.firstOrNull {
                it.displayName.contains(candidate)
            }
            if (contact != null) return contact.displayName
        }
        return null
    }

    private fun fuzzyMatchContact(spokenName: String): String? {
        if (!contactsLoaded) return null

        cachedContacts.firstOrNull {
            it.displayName.equals(spokenName, ignoreCase = true)
        }?.let { return it.displayName }

        cachedContacts.firstOrNull {
            it.displayName.contains(spokenName) || spokenName.contains(it.displayName)
        }?.let { return it.displayName }

        cachedContacts.firstOrNull {
            it.wechatRemarkPinyin.startsWith(spokenName, ignoreCase = true) ||
            spokenName.startsWith(it.wechatRemarkPinyin, ignoreCase = true)
        }?.let { return it.displayName }

        cachedContacts.minByOrNull {
            levenshteinDistance(it.displayName, spokenName)
        }?.let { similar ->
            if (levenshteinDistance(similar.displayName, spokenName) <= 1) {
                return similar.displayName
            }
        }

        return null
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[m][n]
    }
}
