package com.ailaohu.ui.setup

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ailaohu.data.local.prefs.AppPreferences
import com.ailaohu.service.accessibility.AutoPilotService
import com.ailaohu.service.capture.ScreenCaptureManager
import com.ailaohu.service.tts.TTSManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PermissionGuideActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var ttsManager: TTSManager
    @Inject lateinit var screenCaptureManager: ScreenCaptureManager

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    
    // 保存屏幕录制的Intent
    private var screenCaptureData: Intent? = null
    
    // 状态管理
    private var missingPermissions by mutableStateOf<List<String>>(emptyList())
    private var currentPermissionIndex by mutableStateOf(0)
    private var isAuthorizing by mutableStateOf(false)
    private var permissionGrantState by mutableStateOf<Map<String, Boolean>>(emptyMap())

    // 权限请求 Launcher
    private val singlePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        handlePermissionResult(isGranted)
    }

    private val multiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        handleMultiPermissionResult(results)
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        screenCaptureData = it.data
        handleScreenCaptureResult(it.resultCode == Activity.RESULT_OK && it.data != null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 获取传入的缺失权限列表
        val passedMissingList = intent.getStringArrayExtra("MISSING_LIST")?.toList() ?: getAllPermissions()
        missingPermissions = passedMissingList

        tts = TextToSpeech(this, this)

        setContent {
            OptimizedPermissionGuideScreen(
                missingPermissions = missingPermissions,
                currentPermissionIndex = currentPermissionIndex,
                isAuthorizing = isAuthorizing,
                permissionGrantState = permissionGrantState,
                onStartAuthorization = {
                    startAuthorization()
                },
                onOpenSettings = { permissionType ->
                    openSettingsForPermission(permissionType)
                }
            )
        }
    }

    private fun getAllPermissions(): List<String> {
        return listOf(
            "MICROPHONE",
            "ACCESSIBILITY",
            "SCREEN_CAPTURE",
            "CALL_PHONE",
            "SEND_SMS"
        )
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            tts?.language = java.util.Locale.CHINESE
            tts?.setSpeechRate(1.0f)
            
            lifecycleScope.launch {
                delay(500)
                val permissionText = if (missingPermissions.size == 1) {
                    "需要 ${missingPermissions.size} 个权限"
                } else {
                    "需要 ${missingPermissions.size} 个权限"
                }
                ttsManager.speak("欢迎使用沪老助手，为了更好地为您服务，$permissionText")
            }
        }
    }

    private fun startAuthorization() {
        isAuthorizing = true
        currentPermissionIndex = 0
        permissionGrantState = emptyMap()
        requestNextPermission()
    }

    private fun requestNextPermission() {
        if (currentPermissionIndex >= missingPermissions.size) {
            checkAllPermissionsAndFinish()
            return
        }

        val permissionType = missingPermissions[currentPermissionIndex]
        
        when (permissionType) {
            "MICROPHONE" -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    handlePermissionResult(true)
                } else {
                    ttsManager.speak("现在请允许麦克风权限，这样我就能听到您的声音了")
                    singlePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            "ACCESSIBILITY" -> {
                if (isAccessibilityServiceEnabled()) {
                    handlePermissionResult(true)
                } else {
                    ttsManager.speak("现在需要开启无障碍权限，这是最重要的权限")
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                }
            }
            "SCREEN_CAPTURE" -> {
                if (screenCaptureManager.hasPermission()) {
                    handlePermissionResult(true)
                } else {
                    val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    ttsManager.speak("现在请允许屏幕录制权限，这样我就能看到屏幕上的内容了")
                    screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                }
            }
            "CALL_PHONE", "SEND_SMS" -> {
                // 处理电话和短信权限
                val permissionsToRequest = mutableListOf<String>()
                
                if ("CALL_PHONE" in missingPermissions && 
                    ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.CALL_PHONE)
                }
                
                if ("SEND_SMS" in missingPermissions && 
                    ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.SEND_SMS)
                }
                
                if (permissionsToRequest.isEmpty()) {
                    // 如果都已授权，直接处理结果
                    val newState = permissionGrantState.toMutableMap()
                    if ("CALL_PHONE" in missingPermissions) newState["CALL_PHONE"] = true
                    if ("SEND_SMS" in missingPermissions) newState["SEND_SMS"] = true
                    permissionGrantState = newState
                    
                    // 跳过这些权限
                    currentPermissionIndex += if ("CALL_PHONE" in missingPermissions && "SEND_SMS" in missingPermissions) {
                        2
                    } else {
                        1
                    }
                    requestNextPermission()
                } else {
                    ttsManager.speak("现在请允许电话和短信权限，这样我就能帮您拨打电话和发送短信了")
                    multiPermissionLauncher.launch(permissionsToRequest.toTypedArray())
                }
            }
        }
    }

    private fun handlePermissionResult(isGranted: Boolean) {
        val currentPermission = missingPermissions[currentPermissionIndex]
        val newState = permissionGrantState.toMutableMap()
        newState[currentPermission] = isGranted
        permissionGrantState = newState

        lifecycleScope.launch {
            if (isGranted) {
                ttsManager.speak(getSuccessMessage(currentPermission))
            } else {
                ttsManager.speak(getFailureMessage(currentPermission))
            }
            
            delay(1000)
            currentPermissionIndex++
            requestNextPermission()
        }
    }

    private fun handleMultiPermissionResult(results: Map<String, Boolean>) {
        val newState = permissionGrantState.toMutableMap()
        
        if (Manifest.permission.CALL_PHONE in results) {
            newState["CALL_PHONE"] = results[Manifest.permission.CALL_PHONE] == true
        }
        if (Manifest.permission.SEND_SMS in results) {
            newState["SEND_SMS"] = results[Manifest.permission.SEND_SMS] == true
        }
        
        permissionGrantState = newState

        lifecycleScope.launch {
            val allGranted = results.all { it.value }
            if (allGranted) {
                ttsManager.speak("电话和短信权限已开启，我可以帮您拨打电话和发送短信了")
            } else {
                ttsManager.speak("部分电话或短信权限未开启，相关功能可能无法使用")
            }
            
            delay(1000)
            
            // 计算需要跳过的数量
            var skipCount = 0
            if ("CALL_PHONE" in missingPermissions) skipCount++
            if ("SEND_SMS" in missingPermissions) skipCount++
            
            currentPermissionIndex += skipCount
            requestNextPermission()
        }
    }

    private fun handleScreenCaptureResult(isGranted: Boolean) {
        if (isGranted) {
            // 保存屏幕录制权限数据，不启动持续运行的服务
            screenCaptureData?.let { data ->
                screenCaptureManager.savePermissionData(Activity.RESULT_OK, data)
            }
        }
        handlePermissionResult(isGranted)
    }

    private fun getSuccessMessage(permissionType: String): String {
        return when (permissionType) {
            "MICROPHONE" -> "麦克风权限已开启，这样我就能听到您的声音了"
            "ACCESSIBILITY" -> "无障碍权限已开启，太好了"
            "SCREEN_CAPTURE" -> "屏幕录制权限已开启，这样我就能看到屏幕上的内容了"
            "CALL_PHONE" -> "电话权限已开启"
            "SEND_SMS" -> "短信权限已开启"
            else -> "权限已开启"
        }
    }

    private fun getFailureMessage(permissionType: String): String {
        return when (permissionType) {
            "MICROPHONE" -> "麦克风权限未开启，我将无法识别您的语音指令"
            "ACCESSIBILITY" -> "无障碍权限未开启，核心功能将无法使用"
            "SCREEN_CAPTURE" -> "屏幕录制权限未开启，部分功能可能无法使用"
            "CALL_PHONE" -> "电话权限未开启，无法帮您拨打电话"
            "SEND_SMS" -> "短信权限未开启，无法帮您发送短信"
            else -> "权限未开启"
        }
    }

    private fun openSettingsForPermission(permissionType: String) {
        when (permissionType) {
            "ACCESSIBILITY" -> {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
            else -> {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = android.net.Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        
        // 如果正在授权过程中，检查当前权限状态
        if (isAuthorizing && currentPermissionIndex < missingPermissions.size) {
            val currentPermission = missingPermissions[currentPermissionIndex]
            
            if (currentPermission == "ACCESSIBILITY") {
                if (isAccessibilityServiceEnabled()) {
                    handlePermissionResult(true)
                }
            }
        }
    }

    private fun checkAllPermissionsAndFinish() {
        isAuthorizing = false
        
        lifecycleScope.launch {
            appPreferences.setPermissionGuided()
            ttsManager.speak("太好了！所有权限都已就绪，我现在可以帮您操作手机了。")
            delay(2000)
            
            // 直接进入 HomeActivity
            val intent = Intent(this@PermissionGuideActivity, com.ailaohu.ui.home.HomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = ComponentName(this, AutoPilotService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(serviceName.flattenToString())
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
