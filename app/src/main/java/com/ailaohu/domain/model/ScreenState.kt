package com.ailaohu.domain.model

enum class ScreenState(val displayName: String) {
    WECHAT_HOME("微信主界面"),
    WECHAT_SEARCH("微信搜索界面"),
    WECHAT_SEARCH_RESULT("搜索结果列表"),
    WECHAT_CHAT("聊天界面"),
    WECHAT_CALL_MENU("通话菜单"),
    WECHAT_CALLING("通话中"),
    UNKNOWN("未知页面")
}
