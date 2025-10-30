package com.polar.androidblesdk

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.model.EcgSample
import com.polar.sdk.api.model.PolarEcgDataSample
import com.github.mikephil.charting.data.Entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// Gunakan AndroidViewModel untuk mendapatkan applicationContext saat inisialisasi Classifier
class ECGViewModel(application: Application) : AndroidViewModel(application) {

    // Ini adalah 'state' yang akan diamati oleh UI
    private val _uiState = MutableStateFlow<ECGUIState>(ECGUIState.Idle)
    val uiState: StateFlow<ECGUIState> = _uiState.asStateFlow()

    // --- SEMUA LOGIKA DAN STATE PINDAH KE SINI ---
    private val ecgFilter = ECGBandpassFilter()
    private val processingBuffer = ArrayDeque<Double>()
    private val ecgClassifier: ECGClassifierTFLite = ECGClassifierTFLite(application.applicationContext)

    // Konfigurasi dan konstanta
    private val WINDOW_SIZE = 360
    private val WINDOW_SLIDE_STEP = 180
    private val classNames = arrayOf("Normal", "SVEB", "VEB", "Fusion", "Unknown")
    private val SCALER_MEANS = EcgGraphActivity.SCALER_MEANS // Akses dari companion object
    private val SCALER_STD_DEVS = EcgGraphActivity.SCALER_STD_DEVS // Akses dari companion object

    private val chartDataBuffer = ArrayDeque<Entry>()
    private var chartDataIndex = 0L // Untuk sumbu X

    fun startStreaming(api: PolarBleApi, deviceId: String) {
        Log.d("ECG_FLOW_DEBUG", "VIEWMODEL: Fungsi startStreaming dipanggil.")

        _uiState.value = ECGUIState.Streaming("Mempersiapkan stream...")
        ecgFilter.reset()
        processingBuffer.clear()

        chartDataBuffer.clear() // Reset buffer grafik juga
        chartDataIndex = 0L

        viewModelScope.launch {
            Log.d("ECG_FLOW_DEBUG", "VIEWMODEL: Coroutine dimulai, memanggil Repository.")

            try{
                ECGRepository.startEcgStream(api, deviceId)
                    .flowOn(Dispatchers.IO)
                    .onEach { polarEcgData ->
                        Log.d("ECG_FLOW_DEBUG", "VIEWMODEL: Menerima ${polarEcgData.samples.size} sampel dari sensor.")
                        // Gunakan mapNotNull untuk memfilter dan mengubah tipe secara aman
                        val rawSamples = polarEcgData.samples.mapNotNull { sample ->
                            if (sample is EcgSample) {
                                sample.voltage.toDouble() // Jika benar EcgSample, ambil voltage-nya
                            } else {
                                null // Jika bukan (mis. FecgSample), abaikan saja
                            }
                        }

                        val filteredSamples = ecgFilter.filterChunk(rawSamples)
                        processingBuffer.addAll(filteredSamples)
                        // --- LOGIKA BARU UNTUK MENGUMPULKAN DATA GRAFIK ---
                        filteredSamples.forEach { voltage ->
                            chartDataBuffer.add(Entry(chartDataIndex.toFloat(), voltage.toFloat()))
                            chartDataIndex++
                        }
                        // Batasi jumlah data di buffer agar tidak membebani memori
                        while (chartDataBuffer.size > 500) { // Tampilkan data 500 poin terakhir
                            chartDataBuffer.removeFirst()
                        }

                        var summary = ""
                        var latency: Long? = null

                        while (processingBuffer.size >= WINDOW_SIZE) {
                            // TAMBAHKAN KEMBALI DEKLARASI INI
                            val windowToProcess = processingBuffer.take(WINDOW_SIZE)
                            repeat(WINDOW_SLIDE_STEP) { if (processingBuffer.isNotEmpty()) processingBuffer.removeFirst() }

                            // Sekarang 'windowToProcess' sudah dikenal
                            val (s, l) = processEcgWindow(windowToProcess, 130.0)
                            summary = s
                            latency = l
                        }

                        _uiState.value = ECGUIState.Streaming(
                            message = if (summary.isNotEmpty()) summary else (_uiState.value as? ECGUIState.Streaming)?.message ?: "Menerima data...",
                            latency = latency,
                            ecgDataPoints = chartDataBuffer.toList() // Kirim salinan buffer grafik
                        )
                    }
                    .flowOn(Dispatchers.Default) // Pastikan semua proses berat (onEach) berjalan di background
                    .catch { e ->
                        Log.e("ECG_FLOW_DEBUG", "VIEWMODEL: Error di dalam stream!", e)
                        _uiState.value = ECGUIState.Error(e.message ?: "Unknown Stream Error")
                    }
                    .collect()
                Log.d("ECG_FLOW_DEBUG", "VIEWMODEL: Selesai mengoleksi stream (seharusnya tidak terjadi jika stream kontinu).")
            } catch (e: Exception) {
                Log.e("ECG_FLOW_DEBUG", "VIEWMODEL: Gagal memulai getEcgStream dari Repository!", e)
            }
        }
    }

    // --- FUNGSI-FUNGSI LOGIKA PINDAH KE SINI ---
    private fun processEcgWindow(signalWindow: List<Double>, fs: Double): Pair<String, Long> {
        val rPeakIndices = ECGFeatureExtractor.findRPeaks(signalWindow, fs)
        if (rPeakIndices.isEmpty()) return Pair("", 0L)

        val predictionCounts = IntArray(classNames.size) { 0 }
        var totalInferenceTimeMs = 0L

        for (rPeakIdx in rPeakIndices) {
            val ecgSegment = ECGFeatureExtractor.getEcgSegmentAroundRPeak(
                fullSignal = signalWindow, rPeakIndex = rPeakIdx, segmentLength = 180, samplesBeforeR = 90
            )
            if (ecgSegment != null) {
                val morphFeatures = ECGFeatureExtractor.extractKotlinMorphologyFeatures(ecgSegment, fs)
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