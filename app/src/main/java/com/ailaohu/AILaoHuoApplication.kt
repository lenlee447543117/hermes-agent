package com.ailaohu

import android.app.Application
import com.ailaohu.service.tts.TTSManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AILaoHuoApplication : Application() {

    @Inject lateinit var ttsManager: TTSManager

    override fun onCreate() {
        super.onCreate()
        // 使用延迟初始化，避开三星系统启动时的音频参数加载高峰
        ttsManager.initializeWithDelay()
    }
}
