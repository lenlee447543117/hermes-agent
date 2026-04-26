
package com.ailaohu.service.accessibility

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccessibilityHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val appNameToPackageMap by lazy {
        buildAppMap()
    }

    private fun buildAppMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val packageManager = context.packageManager
            val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            
            apps.forEach { appInfo ->
                try {
                    val appName = packageManager.getApplicationLabel(appInfo)?.toString() ?: ""
                    map[appName.lowercase()] = appInfo.packageName
                    
                    // 添加常用应用的直接映射
                    when (appInfo.packageName) {
                        "com.tencent.mm" -> {
                            map["微信"] = appInfo.packageName
                            map["wechat"] = appInfo.packageName
                        }
                        "com.taobao.taobao" -> {
                            map["淘宝"] = appInfo.packageName
                        }
                        "com.xingin.xhs" -> {
                            map["小红书"] = appInfo.packageName
                        }
                        "com.ss.android.ugc.aweme" -> {
                            map["抖音"] = appInfo.packageName
                        }
                        "com.tencent.music" -> {
                            map["QQ音乐"] = appInfo.packageName
                        }
                        "com.netease.cloudmusic" -> {
                            map["网易云音乐"] = appInfo.packageName
                        }
                    }
                } catch (e: Exception) {
                    // 忽略
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    fun findPackageName(appName: String): String? {
        val searchName = appName.lowercase().trim()
        
        // 先直接查找
        if (appNameToPackageMap.containsKey(searchName)) {
            return appNameToPackageMap[searchName]
        }
        
        // 再模糊查找
        val matched = appNameToPackageMap.entries.firstOrNull { (name, _) ->
            name.contains(searchName, ignoreCase = true) || searchName.contains(name, ignoreCase = true)
        }
        
        if (matched != null) {
            return matched.value
        }
        
        // 常用应用兜底
        return when (searchName) {
            "微信", "wechat" -> "com.tencent.mm"
            "相机", "camera" -> "com.android.camera"
            "相册", "photos" -> "com.google.android.apps.photos"
            "设置", "settings" -> "com.android.settings"
            "短信", "sms" -> "com.android.mms"
            "日历", "calendar" -> "com.android.calendar"
            "联系人", "contacts" -> "com.android.contacts"
            "闹钟", "alarm" -> "com.android.deskclock"
            else -> null
        }
    }
    
    fun getAllInstalledApps(): List<Pair<String, String>> {
        return appNameToPackageMap.entries.map { it.key to it.value }
    }
}
