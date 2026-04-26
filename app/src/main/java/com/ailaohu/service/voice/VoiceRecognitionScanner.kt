package com.ailaohu.service.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognitionService
import android.util.Log

object VoiceRecognitionScanner {
    private const val TAG = "VoiceScanner"

    /**
     * 获取系统中可用的语音识别服务列表，并优先选择国产手机自带引擎
     */
    fun getBestRecognizerComponent(context: Context): ComponentName? {
        val pm = context.packageManager
        val services = pm.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE), 0
        )

        Log.d(TAG, "扫描到 ${services.size} 个语音识别服务")

        val vendorSpecificPackages = mutableListOf<ComponentName>()
        val otherPackages = mutableListOf<ComponentName>()

        for (service in services) {
            val packageName = service.serviceInfo.packageName
            val className = service.serviceInfo.name

            Log.d(TAG, "发现识别引擎: $packageName / $className")

            if (!packageName.contains("com.google.android.tts") &&
                !packageName.contains("com.google.android.googlequicksearchbox") &&
                !packageName.contains("com.google.android.voicesearch")) {

                val isVendorEngine = packageName.contains("huawei") ||
                        packageName.contains("xiaomi") ||
                        packageName.contains("miui") ||
                        packageName.contains("oppo") ||
                        packageName.contains("vivo") ||
                        packageName.contains("iflytek") ||
                        packageName.contains("baidu") ||
                        packageName.contains("tencent") ||
                        packageName.contains("samsung") ||
                        packageName.contains("honor") ||
                        packageName.contains("com.samsung.android.bixby") ||
                        packageName.contains("com.samsung.android.svoice") ||
                        packageName.contains("com.samsung.android.tts") ||
                        packageName.contains("com.samsung.android.intellivoiceservice") ||
                        packageName.contains("com.samsung.android.vttsservice")

                if (isVendorEngine) {
                    vendorSpecificPackages.add(ComponentName(packageName, className))
                    Log.d(TAG, "发现厂商引擎: $packageName")
                } else {
                    otherPackages.add(ComponentName(packageName, className))
                }
            }
        }

        if (vendorSpecificPackages.isNotEmpty()) {
            Log.i(TAG, "选择厂商引擎: ${vendorSpecificPackages[0]}")
            return vendorSpecificPackages[0]
        } else if (otherPackages.isNotEmpty()) {
            Log.i(TAG, "选择第三方引擎: ${otherPackages[0]}")
            return otherPackages[0]
        } else {
            Log.w(TAG, "未找到优质引擎，返回null，将使用系统默认引擎")
            return null
        }
    }
}
