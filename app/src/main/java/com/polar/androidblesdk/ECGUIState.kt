package com.polar.androidblesdk
import com.github.mikephil.charting.data.Entry

sealed class ECGUIState {
    data class Buffering(
        val progressSeconds: Int,
        val totalSeconds: Int
    ) : ECGUIState()

    data class Streaming(
        val summaryText: String,
        val ecgDataPoints: List<Entry>? = null,
        val markers: List<ClassificationMarker>? = null
    ) : ECGUIState()

    data class ClassificationMarker(
        val location: Entry, // Lokasi (x,y)
        val label: String // Hasil klasifikasi
    )

    data class Error(
        val message: String
    ) : ECGUIState()
}