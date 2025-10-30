package com.polar.androidblesdk
import com.github.mikephil.charting.data.Entry

sealed class ECGUIState {
    object Idle : ECGUIState()
    data class Streaming(val message: String, val latency: Long? = null, val ecgDataPoints: List<Entry>? = null) : ECGUIState()
    data class Error(val errorMessage: String) : ECGUIState()
}