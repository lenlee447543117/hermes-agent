package com.ailaohu.service.sms

import android.telephony.SmsManager
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsFallbackService @Inject constructor() {

    companion object {
        private const val TAG = "SmsFallback"
    }

    fun sendAlertToChild(phoneNumber: String, contactName: String, reason: String = "网络信号弱") {
        try {
            val message = "【AI沪老提醒】您的家人尝试联系$contactName，但因${reason}未能自动完成，请及时主动联系。"
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                SmsManager.getDefault()
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            Log.d(TAG, "告警短信已发送给 $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "发送告警短信失败", e)
        }
    }

    fun sendSms(phoneNumber: String, message: String) {
        try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                SmsManager.getDefault()
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            Log.d(TAG, "短信已发送给 $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "发送短信失败", e)
        }
    }
}
