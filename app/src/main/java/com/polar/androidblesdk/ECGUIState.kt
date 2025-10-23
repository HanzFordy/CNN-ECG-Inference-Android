package com.polar.androidblesdk

sealed class ECGUIState {
    object Idle : EcgUiState()
    data class Streaming(val message: String, val latency: Long? = null) : EcgUiState()
    data class Error(val errorMessage: String) : EcgUiState()
}