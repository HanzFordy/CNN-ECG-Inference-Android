package com.polar.androidblesdk
import com.github.mikephil.charting.data.Entry

sealed class ECGUIState {
    object Idle : ECGUIState()

    data class Streaming(
        val message: String,
        val latency: Long? = null,
        val ecgDataPoints: List<Entry>? = null,
        val markers: List<ClassificationMarker>? = null
    ) : ECGUIState()

    data class ClassificationMarker(
        val location: Entry, // Lokasi (x,y)
        val lebel: String // Hasil klasifikasi
    )

    data class Error(
        val errorMessage: String
    ) : ECGUIState()
}