package com.polar.androidblesdk

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.model.EcgSample
import com.github.mikephil.charting.data.Entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Gunakan AndroidViewModel untuk mendapatkan applicationContext saat inisialisasi Classifier
class ECGViewModel(application: Application) : AndroidViewModel(application) {

    // Ini adalah 'state' yang akan diamati oleh UI
    private val _uiState = MutableStateFlow<ECGUIState>(ECGUIState.Buffering(progressSeconds = 0, totalSeconds = 40))
    val uiState: StateFlow<ECGUIState> = _uiState.asStateFlow()

    private val ecgFilter = ECGBandpassFilter()
    private val ecgClassifier: ECGClassifierTFLite? = try {
        ECGClassifierTFLite(application.applicationContext)
    } catch (e: Exception) {
        Log.e("EcgViewModel", "FATAL: Gagal memuat model TFLite!", e)
        _uiState.value = ECGUIState.Error("Gagal memuat model klasifikasi.")
        null
    }

    private val processingBuffer = ArrayDeque<Double>() // Sliding window
    private val chartDataBuffer = ArrayDeque<Double>() // Data poin grafik
    private val classificationCounts = mutableMapOf<String, Int>()

    // Konfigurasi
    private val BUFFERING_DURATION_SECONDS = 40 // Ubah ke Int
    private var streamStartTime = 0L

    private val WINDOW_SIZE = 360
    private val WINDOW_SLIDE_STEP = 180
    private val MAX_CHART_POINTS = 500

    fun startStreaming(api: PolarBleApi, deviceId: String) {
        Log.d("ECG_FLOW_DEBUG", "VIEWMODEL: Fungsi startStreaming dipanggil.")

        streamStartTime = System.currentTimeMillis()
        _uiState.value = ECGUIState.Buffering(0, BUFFERING_DURATION_SECONDS)
        ecgFilter.reset()
        processingBuffer.clear()
        chartDataBuffer.clear()
        classificationCounts.clear()

        viewModelScope.launch(Dispatchers.IO) {
            ECGRepository.startEcgStream(api, deviceId)
                .onEach { polarEcgData ->
                    // --- PEMROSESAN SELALU BERJALAN DI LATAR ---
                    val rawSamples = polarEcgData.samples.mapNotNull { if (it is EcgSample) it.voltage.toDouble() else null }
                    if (rawSamples.isEmpty()) return@onEach
                    val filteredSamples = ecgFilter.filterChunk(rawSamples)

                    processingBuffer.addAll(filteredSamples)
                    chartDataBuffer.addAll(filteredSamples)

                    while (chartDataBuffer.size > (MAX_CHART_POINTS + WINDOW_SIZE)) {
                        chartDataBuffer.removeFirst()
                    }

                    val elapsedTime = System.currentTimeMillis() - streamStartTime

                    if (elapsedTime < BUFFERING_DURATION_SECONDS * 1000) {
                        // --- KITA MASIH DALAM FASE BUFFERING ---
                        val progress = (elapsedTime / 1000).toInt()
                        withContext(Dispatchers.Main) {
                            _uiState.value = ECGUIState.Buffering(progress, BUFFERING_DURATION_SECONDS)
                        }

                    } else {
                        // --- KITA SUDAH MASUK FASE STREAMING ---

                        while (processingBuffer.size >= WINDOW_SIZE) {
                            val windowToProcess = processingBuffer.take(WINDOW_SIZE)
                            repeat(WINDOW_SLIDE_STEP) { if (processingBuffer.isNotEmpty()) processingBuffer.removeFirst() }

                            // Proses window, yang akan meng-update 'classificationCounts'
                            val latency = processEcgWindow(windowToProcess, 130.0)
                            Log.i("InferenceLatency", "Latensi window: $latency ms")
                        }

                        // Buat data untuk dikirim ke UI
                        val summary = buildSummaryText()
                        val chartEntries = chartDataBuffer.mapIndexed { index, voltage ->
                            Entry(index.toFloat(), voltage.toFloat())
                        }

                        // Kirim update ke UI
                        withContext(Dispatchers.Main) {
                            _uiState.value = ECGUIState.Streaming(
                                summaryText = summary,
                                ecgDataPoints = chartEntries
                            )
                        }
                    }
                }
                .catch { e ->
                    withContext(Dispatchers.Main) {
                        _uiState.value = ECGUIState.Error(e.message ?: "Error")
                    }
                }
                .collect()
        }
    }

    private fun processEcgWindow(signalWindow: List<Double>, fs: Double): Long {
        var totalInferenceTimeMs = 0L

        val rPeakIndices = ECGFeatureExtractor.findRPeaks(signalWindow, fs)

        for (rPeakIdx in rPeakIndices) {
            val ecgSegment = ECGFeatureExtractor.getEcgSegmentAroundRPeak(
                fullSignal = signalWindow, rPeakIndex = rPeakIdx, segmentLength = 180, samplesBeforeR = 90
            )
            if (ecgSegment != null) {
                val morphFeatures = ECGFeatureExtractor.extractKotlinMorphologyFeatures(ecgSegment, fs)
                val scaledFeatures = scaleMorphologyFeatures(morphFeatures, EcgGraphActivity.SCALER_MEANS, EcgGraphActivity.SCALER_STD_DEVS)

                if (scaledFeatures.isNotEmpty() && ecgClassifier?.isReady() == true) {
                    val inferenceStartTime = System.nanoTime()
                    val (predictedIndex, _) = ecgClassifier!!.classify(ecgSegment, scaledFeatures)

                    totalInferenceTimeMs += (System.nanoTime() - inferenceStartTime) / 1_000_000

                    val className = ecgClassifier!!.classNames.getOrElse(predictedIndex) { "Unknown" }

                    // Update counter global
                    classificationCounts[className] = (classificationCounts[className] ?: 0) + 1
                }
            }
        }
        return totalInferenceTimeMs
    }

    private fun buildSummaryText(): String {
        if (classificationCounts.isEmpty()) {
            return "Menganalisis..."
        }
        return classificationCounts.entries.sortedBy { it.key }.joinToString(" | ") {
            "${it.key}: ${it.value}"
        }
    }


    // --- Fungsi Helper ---
    private fun scaleMorphologyFeatures(
        features: DoubleArray,
        means: DoubleArray,
        stdDevs: DoubleArray
    ): FloatArray {
        // ... (Logika scaling sama persis, tidak perlu diubah) ...
        val scaled = FloatArray(features.size)
        if (features.size != means.size || features.size != stdDevs.size) {
            return FloatArray(0)
        }
        for (i in features.indices) {
            if (stdDevs[i] != 0.0 && !stdDevs[i].isNaN() && !stdDevs[i].isInfinite()) {
                scaled[i] = ((features[i] - means[i]) / stdDevs[i]).toFloat()
            } else {
                scaled[i] = (features[i] - means[i]).toFloat()
            }
        }
        return scaled
    }

    override fun onCleared() {
        super.onCleared()
        ecgClassifier?.close()
        Log.d("EcgViewModel", "ViewModel cleared and TFLite interpreter closed.")
    }
}