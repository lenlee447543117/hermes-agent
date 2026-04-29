package com.ailaohu.service.voice

import com.ailaohu.service.accessibility.AccessibilityHelper
import javax.inject.Inject
import javax.inject.Singleton

sealed class VoiceCommand {
    data class LaunchApp(val appName: String, val packageName: String) : VoiceCommand()
    data class WeChatCall(val contactName: String, val isVideo: Boolean) : VoiceCommand()
    data class PhoneCall(val phoneNumber: String) : VoiceCommand()
    data class CallTaxi(val description: String = "") : VoiceCommand()
    data class SendMessage(val target: String, val message: String) : VoiceCommand()
    data class PlayMusic(val action: String) : VoiceCommand()
    data class ControlVolume(val action: String) : VoiceCommand()
    data class SystemSetting(val setting: String, val action: String) : VoiceCommand()
    data class SetAlarm(val time: String, val label: String) : VoiceCommand()
    data class SearchWeb(val query: String) : VoiceCommand()
    data class TakePhoto(val action: String = "photo") : VoiceCommand()
    data class AutoGLMAction(val command: String) : VoiceCommand()
    data class Chat(val message: String) : VoiceCommand()
    // 新增：紧急求助指令
    data class EmergencySOS(val reason: String = "") : VoiceCommand()
    // 新增：吃药提醒指令
    data class MedicineReminder(val medicineName: String = "", val time: String = "") : VoiceCommand()
    // 新增：位置分享指令
    data class ShareLocation(val target: String = "") : VoiceCommand()
    // 新增：读新闻/故事指令
    data class ReadNews(val topic: String = "") : VoiceCommand()
    // 新增：查询天气详情
    data class QueryWeather(val city: String = "") : VoiceCommand()
    // 新增：确认操作
    data object ConfirmAction : VoiceCommand()
    // 新增：取消操作
    data object CancelAction : VoiceCommand()
    // 新增：重复上一条指令
    data object RepeatLast : VoiceCommand()
    // 新增：帮助指令
    data object Help : VoiceCommand()
    data object Unknown : VoiceCommand()
}

@Singleton
class VoiceCommandParser @Inject constructor(
    private val accessibilityHelper: AccessibilityHelper
) {

    companion object {
        private const val TAG = "VoiceCmdParser"
        private val WAKE_WORDS = listOf("沪沪", "呼呼", "护护", "花花", "小沪", "助手")
        private val GREETINGS = listOf("你好", "在吗", "嗨", "早上好", "下午好", "晚上好")
        private val TAXI_KEYWORDS = listOf("打车", "叫车", "用车", "出门", "出租车", "网约车", "助老打车")
        private val CALL_KEYWORDS = listOf("打电话", "联系", "通话", "拨号", "呼叫")
        private val WECHAT_KEYWORDS = listOf("微信", "视频电话", "视频通话", "语音电话", "语音通话", "微信电话")
        private val VIDEO_KEYWORDS = listOf("视频", "面对面", "看见")
        private val VOICE_CALL_KEYWORDS = listOf("语音", "打电话", "声音")
        private val OPEN_KEYWORDS = listOf("打开", "启动", "开", "运行", "进入", "去")
        private val CLOSE_KEYWORDS = listOf("关闭", "关掉", "退出", "关")
        private val MUSIC_KEYWORDS = listOf("音乐", "歌", "播放", "听歌")
        private val MUSIC_ACTIONS = mapOf(
            "播放" to "play", "听" to "play", "开始" to "play",
            "暂停" to "pause", "停止" to "pause", "停" to "pause",
            "下一首" to "next", "切歌" to "next", "换歌" to "next",
            "上一首" to "previous"
        )
        private val VOLUME_KEYWORDS = listOf("音量", "声音", "大声", "小声")
        private val VOLUME_UP_KEYWORDS = listOf("大", "高", "响", "调大", "加大")
        private val VOLUME_DOWN_KEYWORDS = listOf("小", "低", "轻", "调小", "减小")
        private val FLASHLIGHT_KEYWORDS = listOf("手电筒", "闪光灯", "灯")
        private val BLUETOOTH_KEYWORDS = listOf("蓝牙")
        private val WIFI_KEYWORDS = listOf("wifi", "无线网", "网络", "WiFi")
        private val ALARM_KEYWORDS = listOf("闹钟", "提醒", "定时", "起床")
        private val PHOTO_KEYWORDS = listOf("拍照", "照相", "相机", "录像")
        private val SEARCH_KEYWORDS = listOf("搜索", "搜", "查", "百度", "查一下")
        private val MESSAGE_KEYWORDS = listOf("发短信", "发消息", "发微信", "告诉", "通知")
        private val TIME_KEYWORDS = listOf("几点", "时间", "现在")
        private val WEATHER_KEYWORDS = listOf("天气", "温度", "下雨")
        // 新增：紧急求助关键词
        private val EMERGENCY_KEYWORDS = listOf("救命", "紧急", "求救", "帮帮我", "不好了", "出事了", "摔倒", "摔了", "疼", "不舒服", "急救", "120")
        private val NOISE_PATTERNS = listOf(
            "这段音频", "这段录音", "这个录音", "白噪音", "运行声音",
            "没有语言", "没有语音", "机器的运行", "音频是一段",
            "背景噪音", "环境噪音", "无法识别", "听不清",
            "静音", "嗡嗡声", "嘶嘶声"
        )
        // 新增：吃药提醒关键词
        private val MEDICINE_KEYWORDS = listOf("吃药", "药", "服药", "降压药", "降糖药", "感冒药", "止痛药", "维生素", "钙片", "胰岛素")
        // 新增：位置分享关键词
        private val LOCATION_KEYWORDS = listOf("位置", "在哪", "定位", "分享位置", "我的位置", "发位置")
        // 新增：读新闻/故事关键词
        private val NEWS_KEYWORDS = listOf("新闻", "故事", "头条", "今天发生了什么", "说说新闻")
        // 新增：确认/取消关键词
        private val CONFIRM_KEYWORDS = listOf("是的", "对", "确认", "好的", "没错", "确定", "是")
        private val CANCEL_KEYWORDS = listOf("取消", "不对", "算了", "不要", "不是", "停", "别")
        // 新增：帮助关键词
        private val HELP_KEYWORDS = listOf("帮助", "怎么用", "你会什么", "能做什么", "教我", "使用说明")
        // 新增：重复关键词
        private val REPEAT_KEYWORDS = listOf("再说一次", "重复", "再来一次", "什么", "你刚说什么")

        private val APP_MAPPINGS = mapOf(
            "微信" to "com.tencent.mm",
            "支付宝" to "com.eg.android.AlipayGphone",
            "淘宝" to "com.taobao.taobao",
            "抖音" to "com.ss.android.ugc.aweme",
            "小红书" to "com.xingin.xhs",
            "京东" to "com.jingdong.app.mall",
            "拼多多" to "com.xunmeng.pinduoduo",
            "美团" to "com.sankuai.meituan",
            "饿了么" to "me.ele",
            "高德地图" to "com.autonavi.minimap",
            "地图" to "com.autonavi.minimap",
            "百度地图" to "com.baidu.BaiduMap",
            "网易云音乐" to "com.netease.cloudmusic",
            "QQ音乐" to "com.tencent.qqmusic",
            "音乐" to "com.google.android.apps.youtube.music",
            "相机" to "com.android.camera",
            "相册" to "com.google.android.apps.photos",
            "照片" to "com.google.android.apps.photos",
            "设置" to "com.android.settings",
            "电话" to "com.android.dialer",
            "短信" to "com.android.mms",
            "日历" to "com.android.calendar",
            "时钟" to "com.android.deskclock",
            "闹钟" to "com.android.deskclock",
            "计算器" to "com.android.calculator2",
            "天气" to "com.weather.android",
            "浏览器" to "com.android.browser",
            "QQ" to "com.tencent.mobileqq",
            "qq" to "com.tencent.mobileqq",
            "钉钉" to "com.alibaba.android.rimet",
            "飞书" to "com.ss.android.lark",
            "快手" to "com.smile.gifmaker",
            "微博" to "com.sina.weibo",
            "知乎" to "com.zhihu.android",
            "腾讯视频" to "com.tencent.qqlive",
            "爱奇艺" to "com.qiyi.video",
            "优酷" to "com.youku.phone",
            "微信读书" to "com.tencent.weread",
            "keep" to "com.gotokeep.keep",
            "Keep" to "com.gotokeep.keep"
        )
    }

    fun parse(text: String): VoiceCommand {
        val t = text.trim()
        android.util.Log.d(TAG, "开始解析指令: '$t'")

        if (t.isEmpty()) return VoiceCommand.Unknown

        if (NOISE_PATTERNS.any { t.contains(it) }) {
            android.util.Log.d(TAG, "检测到噪音误识别，忽略: '$t'")
            return VoiceCommand.Unknown
        }

        // 优先检查紧急求助（最高优先级）
        if (EMERGENCY_KEYWORDS.any { t.contains(it) }) {
            val reason = EMERGENCY_KEYWORDS.firstOrNull { t.contains(it) } ?: ""
            android.util.Log.d(TAG, "识别为紧急求助指令")
            return VoiceCommand.EmergencySOS(reason)
        }

        if (isWakeWord(t)) {
            val command = stripWakeWord(t)
            if (command.isEmpty()) return VoiceCommand.Chat("你好，我在呢，有什么可以帮您的？")
            return parse(command)
        }

        // 确认/取消指令
        if (CONFIRM_KEYWORDS.any { t.trim() == it }) {
            android.util.Log.d(TAG, "识别为确认指令")
            return VoiceCommand.ConfirmAction
        }
        if (CANCEL_KEYWORDS.any { t.trim() == it }) {
            android.util.Log.d(TAG, "识别为取消指令")
            return VoiceCommand.CancelAction
        }

        // 帮助指令
        if (HELP_KEYWORDS.any { t.contains(it) }) {
            android.util.Log.d(TAG, "识别为帮助指令")
            return VoiceCommand.Help
        }

        // 重复上一条
        if (REPEAT_KEYWORDS.any { t.contains(it) }) {
            android.util.Log.d(TAG, "识别为重复指令")
            return VoiceCommand.RepeatLast
        }

        // 吃药提醒
        if (MEDICINE_KEYWORDS.any { t.contains(it) }) {
            val medicineName = MEDICINE_KEYWORDS.firstOrNull { t.contains(it) } ?: ""
            val time = extractTime(t)
            android.util.Log.d(TAG, "识别为吃药提醒指令: $medicineName, $time")
            return VoiceCommand.MedicineReminder(medicineName, time)
        }

        // 位置分享
        if (LOCATION_KEYWORDS.any { t.contains(it) }) {
            val target = extractContactName(t)
            android.util.Log.d(TAG, "识别为位置分享指令: $target")
            return VoiceCommand.ShareLocation(target)
        }

        // 读新闻
        if (NEWS_KEYWORDS.any { t.contains(it) }) {
            val topic = extractSearchQuery(t)
            android.util.Log.d(TAG, "识别为读新闻指令: $topic")
            return VoiceCommand.ReadNews(topic)
        }

        if (TAXI_KEYWORDS.any { t.contains(it) }) {
            android.util.Log.d(TAG, "识别为打车指令")
            return VoiceCommand.CallTaxi(t)
        }

        if (isWeChatCall(t)) {
            val contactName = extractContactName(t)
            val isVideo = VIDEO_KEYWORDS.any { t.contains(it) } || t.contains("视频")
            android.util.Log.d(TAG, "识别为微信通话指令: $contactName, video=$isVideo")
            return VoiceCommand.WeChatCall(contactName, isVideo)
        }

        if (CALL_KEYWORDS.any { t.contains(it) } && !WECHAT_KEYWORDS.any { t.contains(it) }) {
            val contactName = extractContactName(t)
            android.util.Log.d(TAG, "识别为电话指令: $contactName")
            return VoiceCommand.WeChatCall(contactName, true)
        }

        if (MESSAGE_KEYWORDS.any { t.contains(it) }) {
            val target = extractTarget(t)
            val message = extractMessage(t)
            android.util.Log.d(TAG, "识别为发送消息指令: $target, $message")
            return VoiceCommand.SendMessage(target, message)
        }

        if (isLaunchApp(t)) {
            val appName = extractAppName(t)
            android.util.Log.d(TAG, "识别为打开应用指令，appName=$appName")
            val packageName = APP_MAPPINGS[appName]
                ?: accessibilityHelper.findPackageName(appName)
                ?: run {
                    android.util.Log.w(TAG, "未找到应用 $appName 的包名")
                    return VoiceCommand.Chat("抱歉，我没有找到${appName}这个应用")
                }
            android.util.Log.d(TAG, "找到应用包名: $packageName")
            return VoiceCommand.LaunchApp(appName, packageName)
        }

        if (MUSIC_KEYWORDS.any { t.contains(it) }) {
            val action = MUSIC_ACTIONS.entries.firstOrNull { (keyword, _) ->
                t.contains(keyword)
            }?.value ?: "play"
            return VoiceCommand.PlayMusic(action)
        }

        if (VOLUME_KEYWORDS.any { t.contains(it) }) {
            val action = when {
                VOLUME_UP_KEYWORDS.any { t.contains(it) } -> "up"
                VOLUME_DOWN_KEYWORDS.any { t.contains(it) } -> "down"
                t.contains("静音") -> "mute"
                t.contains("取消静音") -> "unmute"
                else -> "up"
            }
            return VoiceCommand.ControlVolume(action)
        }

        if (FLASHLIGHT_KEYWORDS.any { t.contains(it) }) {
            val action = if (CLOSE_KEYWORDS.any { t.contains(it) }) "off" else "on"
            return VoiceCommand.SystemSetting("flashlight", action)
        }

        if (BLUETOOTH_KEYWORDS.any { t.contains(it) }) {
            val action = if (CLOSE_KEYWORDS.any { t.contains(it) }) "off" else "on"
            return VoiceCommand.SystemSetting("bluetooth", action)
        }

        if (WIFI_KEYWORDS.any { t.contains(it) }) {
            val action = if (CLOSE_KEYWORDS.any { t.contains(it) }) "off" else "on"
            return VoiceCommand.SystemSetting("wifi", action)
        }

        if (ALARM_KEYWORDS.any { t.contains(it) }) {
            val time = extractTime(t)
            val label = extractLabel(t)
            return VoiceCommand.SetAlarm(time, label)
        }

        if (PHOTO_KEYWORDS.any { t.contains(it) }) {
            val action = if (t.contains("录像") || t.contains("视频")) "video" else "photo"
            return VoiceCommand.TakePhoto(action)
        }

        if (SEARCH_KEYWORDS.any { t.contains(it) }) {
            val query = extractSearchQuery(t)
            return VoiceCommand.SearchWeb(query)
        }

        if (TIME_KEYWORDS.any { t.contains(it) }) {
            return VoiceCommand.Chat("现在是${java.text.SimpleDateFormat("HH点mm分", java.util.Locale.CHINESE).format(java.util.Date())}")
        }

        if (WEATHER_KEYWORDS.any { t.contains(it) }) {
            val city = extractCityName(t)
            return VoiceCommand.QueryWeather(city)
        }

        if (GREETINGS.any { t.contains(it) }) {
            return VoiceCommand.Chat("你好呀，有什么可以帮您的？您可以说\"打车\"、\"给儿子打视频\"、\"打开微信\"、\"救命\"等")
        }

        if (t.contains("再见") || t.contains("拜拜")) {
            return VoiceCommand.Chat("好的，有需要随时叫我~")
        }

        if (t.contains("谢谢") || t.contains("感谢")) {
            return VoiceCommand.Chat("不客气，随时为您服务~")
        }

        // 无法识别的指令，交给AutoGLM处理
        return VoiceCommand.AutoGLMAction(t)
    }

    private fun isWakeWord(text: String): Boolean {
        return WAKE_WORDS.any { text.startsWith(it) }
    }

    private fun stripWakeWord(text: String): String {
        var result = text
        for (word in WAKE_WORDS) {
            if (result.startsWith(word)) {
                result = result.removePrefix(word).trim()
                break
            }
        }
        return result
    }

    private fun isWeChatCall(text: String): Boolean {
        val hasCallIntent = CALL_KEYWORDS.any { text.contains(it) }
        val hasWeChat = WECHAT_KEYWORDS.any { text.contains(it) }
        val hasContact = extractContactName(text).isNotEmpty()
        return (hasWeChat && hasCallIntent) || (hasWeChat && hasContact) || (hasCallIntent && hasContact)
    }

    private fun isLaunchApp(text: String): Boolean {
        val result = OPEN_KEYWORDS.any { keyword ->
            text.startsWith(keyword) && APP_MAPPINGS.keys.any { app ->
                text.contains(app)
            }
        }
        android.util.Log.d(TAG, "isLaunchApp('$text') = $result")
        return result
    }

    private fun extractAppName(text: String): String {
        android.util.Log.d(TAG, "extractAppName('$text') 开始")
        for (keyword in OPEN_KEYWORDS) {
            if (text.startsWith(keyword)) {
                val remaining = text.removePrefix(keyword).trim()
                android.util.Log.d(TAG, "关键词 '$keyword' 匹配，剩余内容: '$remaining'")
                for (appName in APP_MAPPINGS.keys) {
                    if (remaining.contains(appName)) {
                        android.util.Log.d(TAG, "匹配到应用名: '$appName'")
                        return appName
                    }
                }
                android.util.Log.d(TAG, "未匹配到已知应用，返回剩余内容: '$remaining'")
                return remaining
            }
        }
        android.util.Log.d(TAG, "未匹配到打开关键词，返回原始文本")
        return text
    }

    private fun extractContactName(text: String): String {
        val patterns = listOf(
            Regex("给(\\S+?)(打|发|联系)"),
            Regex("跟(\\S+?)(打|发|联系)"),
            Regex("和(\\S+?)(打|发|联系)"),
            Regex("联系(\\S+)"),
            Regex("呼叫(\\S+)"),
            Regex("找(\\S+?)(打|聊)"),
            Regex("(\\S+?)(打|发)"),
            Regex("给(\\S+)"),
            Regex("跟(\\S+)"),
            Regex("和(\\S+)")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null && match.groupValues.size > 1) {
                val name = match.groupValues[1]
                if (name.length in 1..6 && name !in listOf("我", "你", "他", "她")) {
                    return name
                }
            }
        }
        return ""
    }

    private fun extractTarget(text: String): String {
        val patterns = listOf(
            Regex("给(\\S+?)(发|告诉|通知)"),
            Regex("跟(\\S+?)(发|告诉|通知)"),
            Regex("和(\\S+?)(发|告诉|通知)")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }
        return ""
    }

    private fun extractMessage(text: String): String {
        val patterns = listOf(
            Regex("(说|告诉|通知)(.+)$"),
            Regex("(内容|消息)(是|:)(.+)$")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues.last().trim()
            }
        }
        return ""
    }

    private fun extractTime(text: String): String {
        return extractTimeFromText(text)
    }

    fun extractTimeFromText(text: String): String {
        val patterns = listOf(
            Regex("(\\d+)点(\\d+)?分?"),
            Regex("(\\d+):(\\d+)"),
            Regex("(早上|上午|下午|晚上|中午)(\\d+)点"),
            Regex("(\\d+)点半")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) return match.value
        }
        return ""
    }

    private fun extractLabel(text: String): String {
        val patterns = listOf(
            Regex("(提醒我|闹钟|叫醒我)(.+?)(的|$)"),
            Regex("(标签|备注)(是|:)(.+)$")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues.last().trim()
            }
        }
        return "闹钟"
    }

    private fun extractSearchQuery(text: String): String {
        for (keyword in SEARCH_KEYWORDS) {
            if (text.contains(keyword)) {
                return text.substringAfter(keyword).trim()
            }
        }
        return text
    }

    private fun extractCityName(text: String): String {
        val patterns = listOf(
            Regex("(北京|上海|广州|深圳|杭州|成都|武汉|南京|重庆|西安|天津|苏州|长沙|郑州|青岛|大连|宁波|厦门|福州|合肥|昆明|哈尔滨|沈阳|济南|太原|贵阳|南宁|兰州|海口|石家庄|呼和浩特|乌鲁木齐|银川|西宁|拉萨)"),
            Regex("(.+?)(天气|温度)")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }
        return ""
    }

    fun getCommandDescription(command: VoiceCommand): String {
        return when (command) {
            is VoiceCommand.LaunchApp -> "正在打开${command.appName}"
            is VoiceCommand.WeChatCall -> {
                val callType = if (command.isVideo) "视频" else "语音"
                "正在给${command.contactName}打${callType}电话"
            }
            is VoiceCommand.PhoneCall -> "正在拨打电话"
            is VoiceCommand.CallTaxi -> "正在为您叫车"
            is VoiceCommand.SendMessage -> "正在发送消息给${command.target}"
            is VoiceCommand.PlayMusic -> when (command.action) {
                "play" -> "正在播放音乐"
                "pause" -> "音乐已暂停"
                "next" -> "切换下一首"
                "previous" -> "切换上一首"
                else -> "正在操作音乐"
            }
            is VoiceCommand.ControlVolume -> when (command.action) {
                "up" -> "正在调大音量"
                "down" -> "正在调小音量"
                "mute" -> "已静音"
                "unmute" -> "已取消静音"
                else -> "正在调整音量"
            }
            is VoiceCommand.SystemSetting -> {
                val settingName = when (command.setting) {
                    "flashlight" -> "手电筒"
                    "bluetooth" -> "蓝牙"
                    "wifi" -> "WiFi"
                    else -> command.setting
                }
                val actionName = if (command.action == "on") "打开" else "关闭"
                "正在${actionName}${settingName}"
            }
            is VoiceCommand.SetAlarm -> "正在设置闹钟"
            is VoiceCommand.SearchWeb -> "正在搜索${command.query}"
            is VoiceCommand.TakePhoto -> if (command.action == "video") "正在打开录像" else "正在打开相机"
            is VoiceCommand.AutoGLMAction -> "正在执行${command.command}"
            is VoiceCommand.Chat -> command.message
            is VoiceCommand.EmergencySOS -> "紧急求助"
            is VoiceCommand.MedicineReminder -> "吃药提醒"
            is VoiceCommand.ShareLocation -> "分享位置"
            is VoiceCommand.ReadNews -> "读新闻"
            is VoiceCommand.QueryWeather -> "查询天气"
            is VoiceCommand.ConfirmAction -> "确认"
            is VoiceCommand.CancelAction -> "取消"
            is VoiceCommand.RepeatLast -> "重复"
            is VoiceCommand.Help -> "帮助"
            is VoiceCommand.Unknown -> "抱歉，我没有听懂，请再说一次"
        }
    }

    /**
     * 获取帮助文本，列出所有可用指令
     */
    fun getHelpText(): String {
        return "我可以帮您做这些事情：\n" +
                "1. 打电话：说\"给儿子打视频\"、\"给女儿打电话\"\n" +
                "2. 叫车：说\"打车\"、\"叫车\"\n" +
                "3. 打开应用：说\"打开微信\"、\"打开抖音\"\n" +
                "4. 发消息：说\"给儿子发消息\"\n" +
                "5. 听音乐：说\"播放音乐\"、\"下一首\"\n" +
                "6. 调音量：说\"音量大一点\"、\"音量小一点\"\n" +
                "7. 开关灯：说\"打开手电筒\"、\"关灯\"\n" +
                "8. 设闹钟：说\"设个闹钟\"、\"明天7点叫我\"\n" +
                "9. 拍照：说\"拍照\"、\"录像\"\n" +
                "10. 查天气：说\"今天天气怎么样\"\n" +
                "11. 问时间：说\"现在几点了\"\n" +
                "12. 吃药提醒：说\"提醒我吃药\"\n" +
                "13. 紧急求助：说\"救命\"、\"帮帮我\"\n" +
                "14. 分享位置：说\"发我的位置\"\n" +
                "15. 听新闻：说\"说说新闻\"\n" +
                "16. 搜索：说\"搜索...\"\n" +
                "您随时可以说\"帮助\"查看这些指令。"
    }
}
