package com.ailaohu.ui.home.mvi

sealed class HomeViewEffect {
    data class Speak(val text: String, val dialect: String = "shanghai") : HomeViewEffect()
    data class ShowToast(val message: String) : HomeViewEffect()
    data class NavigateTo(val route: String) : HomeViewEffect()
    data class Vibrate(val duration: Long = 100) : HomeViewEffect()
    data class SendSms(val phone: String, val message: String) : HomeViewEffect()
    object StartVoiceRecognition : HomeViewEffect()
    object StopVoiceRecognition : HomeViewEffect()
}
