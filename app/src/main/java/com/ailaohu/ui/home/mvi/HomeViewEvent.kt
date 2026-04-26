package com.ailaohu.ui.home.mvi

sealed class HomeViewEvent {
    data class OnVoiceInput(val text: String) : HomeViewEvent()
    data class OnLongPressButton(val isPressed: Boolean) : HomeViewEvent()
    object OnClickNotification : HomeViewEvent()
    object OnClickSettings : HomeViewEvent()
    object OnClickProfile : HomeViewEvent()
    object OnClickEmergency : HomeViewEvent()
    object OnDismissEmergency : HomeViewEvent()
    data class OnDialectChanged(val dialect: String) : HomeViewEvent()
    data class OnProactiveCareReceived(val message: String) : HomeViewEvent()
    object OnStartListening : HomeViewEvent()
    object OnStopListening : HomeViewEvent()
}
