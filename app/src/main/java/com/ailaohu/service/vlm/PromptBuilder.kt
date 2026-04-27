package com.ailaohu.service.vlm

object PromptBuilder {

    fun buildFindElementPrompt(targetDescription: String): String {
        return """
            你是一个专门用于Android UI自动化的视觉助手。
            请在提供的手机屏幕截图中找到【$targetDescription】。

            要求：
            1. 仔细观察截图中的所有UI元素（按钮、图标、文字标签等）
            2. 找到与描述最匹配的元素
            3. 返回该元素的中心点坐标

            返回格式严格为JSON（不要包含其他文字）：
            {"x": 0.5, "y": 0.5}

            如果未找到该元素，返回：
            {"error": "not_found"}

            注意：坐标必须是归一化坐标（0到1之间的小数），x为水平位置比例，y为垂直位置比例。左上角为(0,0)，右下角为(1,1)。
        """.trimIndent()
    }

    fun buildWeChatSearchButtonPrompt(): String {
        return buildFindElementPrompt("微信主界面右上角的搜索放大镜图标")
    }

    fun buildWeChatVideoCallPrompt(): String {
        return buildFindElementPrompt("聊天界面右下角的加号(+)按钮")
    }

    fun buildWeChatVideoCallOptionPrompt(): String {
        return buildFindElementPrompt("弹出菜单中的视频通话选项按钮")
    }

    fun buildWeChatVoiceCallOptionPrompt(): String {
        return buildFindElementPrompt("弹出菜单中的语音通话选项按钮")
    }

    fun buildPageStatePrompt(expectedPages: List<String>): String {
        val pageList = expectedPages.joinToString("、")
        return """
            你是一个Android UI状态判断助手。
            请判断当前屏幕截图处于以下哪个页面：$pageList。

            返回格式严格为JSON：
            {"page": "页面名称", "confidence": 0.95}

            如果都不匹配，返回：
            {"page": "unknown", "confidence": 0.0}
        """.trimIndent()
    }
}
