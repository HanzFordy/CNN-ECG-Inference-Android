package com.polar.androidblesdk

sealed class ECGUIState {
    object Idle : ECGUIState()
    data class Streaming(val message: String, val latency: Long? = null) : ECGUIState()
    data class Error(val errorMessage: String) : ECGUIState()
}