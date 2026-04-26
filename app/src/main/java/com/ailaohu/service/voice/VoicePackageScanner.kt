package com.ailaohu.service.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognitionService
import android.speech.tts.TextToSpeech
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class VoicePackageInfo(
    val packageName: String,
    val className: String,
    val label: String,
    val vendor: String,
    val type: VoicePackageType,
    val isAvailable: Boolean
)

enum class VoicePackageType {
    ASR, TTS, BOTH
}

@Singleton
class VoicePackageScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "VoicePkgScanner"

        private val VENDOR_MAP = mapOf(
            "com.samsung" to "三星",
            "com.huawei" to "华为",
            "com.hihonor" to "荣耀",
            "com.xiaomi" to "小米",
            "com.miui" to "小米",
            "com.oppo" to "OPPO",
            "com.heytap" to "OPPO",
            "com.coloros" to "OPPO",
            "com.vivo" to "VIVO",
            "com.iflytek" to "科大讯飞",
            "com.baidu" to "百度",
            "com.tencent" to "腾讯",
            "com.google" to "Google",
            "com.android" to "Android系统"
        )

        private val KNOWN_ASR_PACKAGES = mapOf(
            "com.samsung.android.bixby.agent" to "Bixby语音识别",
            "com.samsung.android.intellivoiceservice" to "三星智能语音",
            "com.huawei.vassistant" to "华为语音助手",
            "com.huawei.intelligent" to "华为智能语音",
            "com.xiaomi.voice" to "小爱同学",
            "com.miui.voiceassist" to "小米语音助手",
            "com.vivo.voice" to "Jovi语音",
            "com.coloros.speechassist" to "OPPO语音助手",
            "com.iflytek.speechcloud" to "讯飞语音云",
            "com.baidu.voicesearch" to "百度语音搜索"
        )

        private val KNOWN_TTS_PACKAGES = mapOf(
            "com.samsung.android.tts" to "三星TTS",
            "com.samsung.android.vttsservice" to "三星语音TTS",
            "com.huawei.hiviewtts" to "华为TTS",
            "com.xiaomi.mibrain.speech" to "小爱TTS",
            "com.iflytek.speechcloud" to "讯飞TTS",
            "com.google.android.tts" to "Google TTS",
            "com.baidu.duersdk_sv" to "百度TTS"
        )
    }

    fun scanAsrServices(): List<VoicePackageInfo> {
        val pm = context.packageManager
        val results = mutableListOf<VoicePackageInfo>()

        val services = pm.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE), 0
        )

        Log.d(TAG, "扫描到 ${services.size} 个ASR服务")

        for (service in services) {
            val pkgName = service.serviceInfo.packageName
            val clsName = service.serviceInfo.name
            val vendor = identifyVendor(pkgName)
            val label = KNOWN_ASR_PACKAGES[pkgName] ?: getServiceLabel(pm, pkgName, clsName)

            results.add(
                VoicePackageInfo(
                    packageName = pkgName,
                    className = clsName,
                    label = label,
                    vendor = vendor,
                    type = if (KNOWN_TTS_PACKAGES.containsKey(pkgName)) VoicePackageType.BOTH else VoicePackageType.ASR,
                    isAvailable = true
                )
            )
            Log.d(TAG, "ASR引擎: $pkgName / $clsName ($vendor - $label)")
        }

        return results.sortedWith(compareByDescending<VoicePackageInfo> {
            it.vendor != "Google" && it.vendor != "Android系统"
        }.thenBy { it.label })
    }

    fun scanTtsEngines(): List<VoicePackageInfo> {
        val results = mutableListOf<VoicePackageInfo>()
        var tts: TextToSpeech? = null

        try {
            // 避免同步等待导致的主线程阻塞，只扫描已知包名
            Log.d(TAG, "快速扫描已知TTS引擎，不阻塞主线程")
            
            // 检查常见包名是否已安装
            for ((pkgName, label) in KNOWN_TTS_PACKAGES) {
                try {
                    context.packageManager.getPackageInfo(pkgName, 0)
                    val vendor = identifyVendor(pkgName)
                    results.add(
                        VoicePackageInfo(
                            packageName = pkgName,
                            className = "",
                            label = label,
                            vendor = vendor,
                            type = if (KNOWN_ASR_PACKAGES.containsKey(pkgName)) VoicePackageType.BOTH else VoicePackageType.TTS,
                            isAvailable = true
                        )
                    )
                    Log.d(TAG, "已安装TTS引擎: $pkgName ($vendor - $label)")
                } catch (e: Exception) {
                    // 包未安装，忽略
                }
            }
            
            // 至少添加默认TTS提示
            if (results.isEmpty()) {
                results.add(
                    VoicePackageInfo(
                        packageName = "com.google.android.tts",
                        className = "",
                        label = "Google TTS",
                        vendor = "Google",
                        type = VoicePackageType.TTS,
                        isAvailable = false
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "扫描TTS引擎失败", e)
        }

        return results.sortedWith(compareByDescending<VoicePackageInfo> {
            it.vendor != "Google" && it.vendor != "Android系统"
        }.thenBy { it.label })
    }

    fun scanAllVoicePackages(): List<VoicePackageInfo> {
        val asrServices = scanAsrServices().toMutableList()
        val ttsEngines = scanTtsEngines()

        val mergedMap = mutableMapOf<String, VoicePackageInfo>()
        for (asr in asrServices) {
            mergedMap[asr.packageName] = asr
        }
        for (tts in ttsEngines) {
            val existing = mergedMap[tts.packageName]
            if (existing != null) {
                mergedMap[tts.packageName] = existing.copy(type = VoicePackageType.BOTH)
            } else {
                mergedMap[tts.packageName] = tts
            }
        }

        return mergedMap.values.sortedWith(compareByDescending<VoicePackageInfo> {
            it.vendor != "Google" && it.vendor != "Android系统"
        }.thenBy { it.label })
    }

    private fun identifyVendor(packageName: String): String {
        for ((prefix, vendor) in VENDOR_MAP) {
            if (packageName.startsWith(prefix)) {
                return vendor
            }
        }
        return packageName.substringBefore(".").removePrefix("com.").replaceFirstChar { it.uppercase() }
    }

    private fun getServiceLabel(pm: PackageManager, packageName: String, className: String): String {
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            className.substringAfterLast(".")
        }
    }
}
