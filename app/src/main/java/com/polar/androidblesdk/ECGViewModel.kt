package com.polar.androidblesdk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polar.androidblesdk.* // Import semua kelas helper-mu
import com.polar.sdk.api.PolarBleApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ECGViewModel {
    // Ini adalah 'state' yang akan diamati oleh UI
    private val _uiState = MutableStateFlow<EcgUiState>(EcgUiState.Idle)
    val uiState: StateFlow<EcgUiState> = _uiState.asStateFlow()

    // Pindahkan semua logika pemrosesan ke sini
    private val ecgFilter = EcgBandpassFilter()
    private val processingBuffer = ArrayDeque<Double>()
    private val WINDOW_SIZE = 360
    private val WINDOW_SLIDE_STEP = 180

    fun startEcgStreaming(api: PolarBleApi, deviceId: String) {
        _uiState.value = EcgUiState.Streaming("Menerima data...")
        ecgFilter.reset()
        processingBuffer.clear()

        viewModelScope.launch {
            EcgRepository.startEcgStream(api, deviceId)
                .onEach { polarEcgData -> // Setiap kali ada data baru dari Flow
                    // 1. Filter dan tambahkan ke buffer
                    val rawSamples = polarEcgData.samples.map { it.voltage.toDouble() }
                    val filteredSamples = ecgFilter.filterChunk(rawSamples)
                    processingBuffer.addAll(filteredSamples)

                    // 2. Cek jika buffer cukup
                    while (processingBuffer.size >= WINDOW_SIZE) {
                        val windowToProcess = processingBuffer.take(WINDOW_SIZE)
                        repeat(WINDOW_SLIDE_STEP) { if (processingBuffer.isNotEmpty()) processingBuffer.removeFirst() }

                        // 3. Proses window
                        // Logika dari processEcgWindow() kita pindah ke sini
                        val (summary, latency) = processWindow(windowToProcess)
                        if (summary.isNotEmpty()) {
                            _uiState.value = EcgUiState.Streaming(summary, latency)
                        }
                    }
                }
                .flowOn(Dispatchers.Default) // Pastikan semua proses berat ini di background
                .catch { e -> _uiState.value = EcgUiState.Error(e.message ?: "Unknown Error") }
                .collect() // Mulai mengoleksi Flow
        }
    }

    private fun processWindow(signalWindow: List<Double>): Pair<String, Long> {
        // ... Salin-tempel seluruh isi fungsi processEcgWindow dari Activity ke sini ...
        // ... Satu-satunya perbedaan: kita tidak lagi butuh parameter 'fs' jika sudah jadi properti class ...
        // Dan pada akhirnya, return Pair(summaryText, totalInferenceTimeMs)
        return Pair("Contoh Hasil", 15L) // Ganti dengan logika aslimu
    }
}

// Data class untuk merepresentasikan state UI
sealed class EcgUiState {
    object Idle : EcgUiState()
    data class Streaming(val message: String, val latency: Long? = null) : EcgUiState()
    data class Error(val errorMessage: String) : EcgUiState()
}
}