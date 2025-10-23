package com.polar.androidblesdk

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polar.sdk.api.PolarBleApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// Gunakan AndroidViewModel untuk mendapatkan applicationContext saat inisialisasi Classifier
class EcgViewModel(application: Application) : AndroidViewModel(application) {

    // Ini adalah 'state' yang akan diamati oleh UI
    private val _uiState = MutableStateFlow<ECGUIState>(ECGUIState.Idle)
    val uiState: StateFlow<ECGUIState> = _uiState.asStateFlow()

    // --- SEMUA LOGIKA DAN STATE PINDAH KE SINI ---
    private val ecgFilter = EcgBandpassFilter()
    private val processingBuffer = ArrayDeque<Double>()
    private val ecgClassifier: EcgClassifierTFLite = EcgClassifierTFLite(application.applicationContext)

    // Konfigurasi dan konstanta
    private val WINDOW_SIZE = 360
    private val WINDOW_SLIDE_STEP = 180
    private val classNames = arrayOf("Normal", "SVEB", "VEB", "Fusion", "Unknown")
    private val SCALER_MEANS = EcgGraphActivity.SCALER_MEANS // Akses dari companion object
    private val SCALER_STD_DEVS = EcgGraphActivity.SCALER_STD_DEVS // Akses dari companion object

    fun startStreaming(api: PolarBleApi, deviceId: String) {
        _uiState.value = ECGUIState.Streaming("Mempersiapkan stream...")
        ecgFilter.reset()
        processingBuffer.clear()

        viewModelScope.launch {
            ECGRepository.startEcgStream(api, deviceId)
                .onEach { polarEcgData -> // Setiap ada data baru dari Flow
                    val rawSamples = polarEcgData.samples.map { it.voltage.toDouble() }
                    val filteredSamples = ecgFilter.filterChunk(rawSamples)
                    processingBuffer.addAll(filteredSamples)

                    while (processingBuffer.size >= WINDOW_SIZE) {
                        val windowToProcess = processingBuffer.take(WINDOW_SIZE)
                        repeat(WINDOW_SLIDE_STEP) { if (processingBuffer.isNotEmpty()) processingBuffer.removeFirst() }

                        // Proses window dan update state
                        val (summary, latency) = processEcgWindow(windowToProcess, 130.0) // Asumsi Fs
                        if (summary.isNotEmpty()) {
                            _uiState.value = ECGUIState.Streaming(summary, latency)
                        }
                    }
                }
                .flowOn(Dispatchers.Default) // Pastikan semua proses berat (onEach) berjalan di background
                .catch { e -> _uiState.value = ECGUIState.Error(e.message ?: "Unknown Error") }
                .collect()
        }
    }

    // --- FUNGSI-FUNGSI LOGIKA PINDAH KE SINI ---
    private fun processEcgWindow(signalWindow: List<Double>, fs: Double): Pair<String, Long> {
        val rPeakIndices = EcgFeatureExtractor.findRPeaks(signalWindow, fs)
        if (rPeakIndices.isEmpty()) return Pair("", 0L)

        val predictionCounts = IntArray(classNames.size) { 0 }
        var totalInferenceTimeMs = 0L

        for (rPeakIdx in rPeakIndices) {
            val ecgSegment = EcgFeatureExtractor.getEcgSegmentAroundRPeak(
                fullSignal = signalWindow, rPeakIndex = rPeakIdx, segmentLength = 180, samplesBeforeR = 90
            )
            if (ecgSegment != null) {
                val morphFeatures = EcgFeatureExtractor.extractKotlinMorphologyFeatures(ecgSegment, fs)
                val scaledFeatures = scaleMorphologyFeatures(morphFeatures, SCALER_MEANS, SCALER_STD_DEVS)

                if (scaledFeatures.isNotEmpty() && ecgClassifier.isReady()) {
                    val inferenceStartTime = System.nanoTime()
                    val (predictedIndex, _) = ecgClassifier.classify(ecgSegment, scaledFeatures)
                    totalInferenceTimeMs += (System.nanoTime() - inferenceStartTime) / 1_000_000

                    if (predictedIndex in predictionCounts.indices) {
                        predictionCounts[predictedIndex]++
                    }
                }
            }
        }

        val detectedClasses = predictionCounts.mapIndexed { index, count ->
            if (count > 0) "${classNames[index]}($count)" else null
        }.filterNotNull()

        val summaryText = if (detectedClasses.isNotEmpty()) "Deteksi: ${detectedClasses.joinToString(", ")}" else "Detak Jantung Normal"
        return Pair(summaryText, totalInferenceTimeMs)
    }

    private fun scaleMorphologyFeatures(features: DoubleArray, means: DoubleArray, stdDevs: DoubleArray): FloatArray {
        // ... (Logika scaling sama persis, tidak perlu diubah) ...
        val scaled = FloatArray(features.size)
        if (features.size != means.size || features.size != stdDevs.size) { return FloatArray(0) }
        for (i in features.indices) {
            if (stdDevs[i] != 0.0 && !stdDevs[i].isNaN() && !stdDevs[i].isInfinite()) {
                scaled[i] = ((features[i] - means[i]) / stdDevs[i]).toFloat()
            } else {
                scaled[i] = (features[i] - means[i]).toFloat()
            }
        }
        return scaled
    }

    // Dipanggil otomatis saat ViewModel dihancurkan, cocok untuk membersihkan resource
    override fun onCleared() {
        super.onCleared()
        ecgClassifier.close()
        Log.d("EcgViewModel", "ViewModel cleared and TFLite interpreter closed.")
    }
}