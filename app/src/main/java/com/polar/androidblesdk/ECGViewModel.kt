package com.polar.androidblesdk

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.model.EcgSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Environment
import kotlinx.coroutines.Job
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class ECGViewModel(application: Application) : AndroidViewModel(application) {

    // --- STATE UNTUK UI (Teks, Status) ---
    private val _uiState = MutableStateFlow<ECGUIState>(ECGUIState.Buffering(0, 40))
    val uiState: StateFlow<ECGUIState> = _uiState.asStateFlow()

    // --- CHANNEL UNTUK DATA GRAFIK (Streaming Cepat) ---
    // Kita pakai Channel agar Activity bisa terima per-chunk dan diolah GraphManager
    private val _newGraphData = Channel<DoubleArray>(Channel.BUFFERED)
    val newGraphData = _newGraphData.receiveAsFlow()

    // --- CHANNEL UNTUK MARKER ---
    private val _predictionMarkerEvent = Channel<PredictionMarker>(Channel.BUFFERED)
    val predictionMarkerEvent = _predictionMarkerEvent.receiveAsFlow()

    // --- Logic Components ---
    private val ecgFilter = ECGBandpassFilter()
    private val ecgClassifier: ECGClassifierTFLite? = try {
        ECGClassifierTFLite(application.applicationContext)
    } catch (e: Exception) { null }

    private val processingBuffer = ArrayDeque<Double>()
    private val classificationCounts = mutableMapOf<String, Int>()

    private val BUFFERING_DURATION_SECONDS = 40
    private var streamStartTime = 0L
    private var globalSampleCount: Long = 0 // INDEX X GLOBAL (PENTING!)

    private var streamingJob: Job? = null

    private var lastCpuTime: Long = 0
    private var lastAppTime: Long = 0
    private var numCores: Int = 1 // Default 1

    // Init block untuk deteksi jumlah core sekali saja
    init {
        numCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    }

    private val WINDOW_SIZE = 360
    private val WINDOW_SLIDE_STEP = 180

    data class PerformanceLog(
        val timestamp: Long,
        val inferenceTimeMs: Long,
        val memoryUsageKb: Long,
        val cpuUsagePercent: Double
    )
    private val performanceLogs = mutableListOf<PerformanceLog>()

    companion object {
        val SCALER_MEANS: DoubleArray = doubleArrayOf(
            5.970848412788726245e-02, 3.576352658466862011e-01, 1.533086060142666296e-01,
            2.315986284913017723e+00, 8.519990147522438662e+00, 1.533583027376864250e+00,
            -4.907076171816288634e-01, 2.024290645236258790e+00, 3.518787036117456246e+00,
            1.533583027376864250e+00, 5.148918753209811294e-01, 3.937600556162478682e+01,
            1.093777964583108400e+02, 1.938857437817049600e+01, 1.575273114422618415e-01,
            -8.009608696831266306e-02, 3.820916469172977781e+00, 1.607511502476304532e-01,
            -2.004090110693601268e-01, 8.729064951668895844e+00, 2.329455088691397280e-01,
            -3.546082845127158656e-01, 4.308479136685343749e-04, 5.558272415473220450e-03,
            9.268053762372960591e+01, 8.731946237627039409e+01, 2.893973357930734380e+01,
            1.607762976766668739e-01, 3.665815391478040586e-01, 1.242857615784420844e+01,
            2.592421198695114290e+05, 4.110187968087033834e+06, 1.940847980727283373e+00,
            7.814200899537544487e-01, 5.148918753209811294e-01, 7.932099057173612522e+00,
            1.263161766182973711e+00
        )

        val SCALER_STD_DEVS: DoubleArray = doubleArrayOf(
            6.247070181054798638e-02, 1.593914133629322494e-01, 1.532928939911017974e-01,
            1.471957890753428089e+00, 5.180556006327465823e+00, 6.505246305509362337e-01,
            3.849459150503709015e-01, 7.749992475885520982e-01, 2.190108046747942350e+00,
            6.505246305509362337e-01, 9.569497857276233754e-02, 3.235098634016438268e+00,
            8.986385892753688154e+00, 1.030071326279049515e+01, 1.192930409864375735e-01,
            8.972637804353833513e-02, 3.255169918620719027e+00, 2.419560024668111764e-01,
            2.170565759475965306e-01, 7.599432023420074955e+00, 1.035952464267802381e-01,
            1.546303733480451359e-01, 1.715559627819271361e-03, 4.317860077251029112e-03,
            1.722509648799882953e+01, 1.722509648799882953e+01, 2.882364326978737878e+01,
            1.601313516502389789e-01, 1.624631431256283176e-01, 6.518611172876429549e+00,
            5.687568367783658206e+06, 2.750301766991863027e+07, 3.993931893821370260e-01,
            2.415676128412637125e-01, 9.569497857276233754e-02,
            8.123723165703449212e+00, 9.133039402158265929e+00
        )
    }

    fun startStreaming(api: PolarBleApi, deviceId: String) {
        streamStartTime = System.currentTimeMillis()
        _uiState.value = ECGUIState.Buffering(0, BUFFERING_DURATION_SECONDS)

        ecgFilter.reset()
        processingBuffer.clear()
        classificationCounts.clear()
        globalSampleCount = 0

        streamingJob = viewModelScope.launch(Dispatchers.IO) {
            ECGRepository.startEcgStream(api, deviceId)
                .onEach { polarEcgData ->
                    val rawSamples = polarEcgData.samples.mapNotNull { if (it is EcgSample) it.voltage.toDouble() else null }
                    if (rawSamples.isEmpty()) return@onEach

                    val filteredSamples = ecgFilter.filterChunk(rawSamples)

                    // 1. Kirim data sinyal ke UI (untuk digambar GraphManager)
                    _newGraphData.send(filteredSamples.toDoubleArray())

                    // 2. Update Global Counter (agar kita tahu posisi X saat ini)
                    val currentChunkStartX = globalSampleCount
                    globalSampleCount += filteredSamples.size

                    // 3. Masukkan ke Buffer AI
                    processingBuffer.addAll(filteredSamples)

                    // Cek Fase Buffering
                    val elapsedTime = System.currentTimeMillis() - streamStartTime
                    if (elapsedTime < BUFFERING_DURATION_SECONDS * 1000) {
                        val progress = (elapsedTime / 1000).toInt()
                        withContext(Dispatchers.Main) {
                            _uiState.value = ECGUIState.Buffering(progress, BUFFERING_DURATION_SECONDS)
                        }
                    } else {
                        // Masuk Fase Streaming/Inferensi
                        while (processingBuffer.size >= WINDOW_SIZE) {
                            val windowToProcess = processingBuffer.take(WINDOW_SIZE)

                            // HITUNG POSISI X AWAL WINDOW INI
                            // Logic: Index Global sekarang - Sisa di buffer
                            // Ini estimasi paling akurat untuk sinkronisasi
                            val windowStartIndex = globalSampleCount - processingBuffer.size

                            processEcgWindow(windowToProcess, 130.0, windowStartIndex)

                            // Geser Window
                            repeat(WINDOW_SLIDE_STEP) { if (processingBuffer.isNotEmpty()) processingBuffer.removeFirst() }
                        }

                    }
                }
                .catch { e ->
                    withContext(Dispatchers.Main) { _uiState.value = ECGUIState.Error(e.message ?: "Error") }
                }
                .collect()
        }
    }

    private fun processEcgWindow(signalWindow: List<Double>, fs: Double, windowStartIndex: Long) {
        val rPeakIndices = ECGFeatureExtractor.findRPeaks(signalWindow, fs)
        var totalInferenceTimeMs = 0L

        for (rPeakIdx in rPeakIndices) {
            val ecgSegment = ECGFeatureExtractor.getEcgSegmentAroundRPeak(
                signalWindow, rPeakIdx, 180, 90
            )

            if (ecgSegment != null) {
                // ... (Ekstraksi Fitur & Scaling - SAMA SEPERTI SEBELUMNYA) ...
                val morphFeatures = ECGFeatureExtractor.extractKotlinMorphologyFeatures(ecgSegment, fs)
                val scaledFeatures = scaleMorphologyFeatures(morphFeatures, SCALER_MEANS, SCALER_STD_DEVS)

                if (scaledFeatures.isNotEmpty() && ecgClassifier?.isReady() == true) {
                    val inferenceStartTime = System.nanoTime()
                    val (predictedIndex, _) = ecgClassifier!!.classify(ecgSegment, scaledFeatures)
                    val inferenceEndTime = System.nanoTime()

                    val latencyPerBeat = (inferenceEndTime - inferenceStartTime) / 1_000_000
                    totalInferenceTimeMs += latencyPerBeat

                    val usedMem = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024
                    val cpuPercent = getCpuUsage()

                    performanceLogs.add(
                        PerformanceLog(
                            timestamp = System.currentTimeMillis(),
                            inferenceTimeMs = latencyPerBeat,
                            memoryUsageKb = usedMem,
                            cpuUsagePercent = cpuPercent // <-- Parameter ke-4
                        )
                    )

                    Log.d("PERFORMANCE_LOG", "Data: Latency=$latencyPerBeat ms, CPU=$cpuPercent %")

                    val allLatencies = performanceLogs.map { it.inferenceTimeMs }
                    val allMemories = performanceLogs.map { it.memoryUsageKb.toDouble() / 1024.0 }
                    val allCpus = performanceLogs.map { it.cpuUsagePercent }
                    val latInfo = "Rata²: ${String.format("%.2f", allLatencies.average())} | Min: ${allLatencies.minOrNull()} | Maks: ${allLatencies.maxOrNull()} (ms)"
                    val memInfo = "RAM Usage (MB):\nRata²: ${String.format("%.2f", allMemories.average())} | Min: ${String.format("%.2f", allMemories.minOrNull())} | Maks: ${String.format("%.2f", allMemories.maxOrNull())}"
                    val cpuInfo = "CPU Usage (%):\nRata²: ${String.format("%.2f", allCpus.average())} | Min: ${String.format("%.2f", allCpus.minOrNull())} | Maks: ${String.format("%.2f", allCpus.maxOrNull())}"

                    val summary = classificationCounts.entries.joinToString(" | ") { "${it.key}: ${it.value}" }
                    viewModelScope.launch(Dispatchers.Main) {
                        _uiState.value = ECGUIState.Streaming(
                            summaryText = summary,
                            latencyInfo = latInfo,
                            memoryInfo = memInfo,
                            cpuInfo = cpuInfo
                        )
                    }

                    val className = ecgClassifier!!.classNames.getOrElse(predictedIndex) { "?" }

                    // HITUNG POSISI MARKER YANG SEBENARNYA
                    val exactGlobalX = windowStartIndex + rPeakIdx
                    val voltageAtPeak = signalWindow[rPeakIdx].toFloat()

                    // Kirim Marker ke UI lewat Channel
                    viewModelScope.launch {
                        _predictionMarkerEvent.send(
                            PredictionMarker(
                                xIndex = exactGlobalX,
                                yValue = voltageAtPeak,
                                label = className
                            )
                        )
                    }

                    // Update Counter
                    classificationCounts[className] = (classificationCounts[className] ?: 0) + 1
                }
            }
        }
    }

    // ... (Fungsi scaleMorphologyFeatures & onCleared TETAP SAMA, copas aja) ...
    private fun scaleMorphologyFeatures(features: DoubleArray, means: DoubleArray, stdDevs: DoubleArray): FloatArray {
        val scaled = FloatArray(features.size)
        if (features.size != means.size || features.size != stdDevs.size) return FloatArray(0)
        for (i in features.indices) {
            scaled[i] = if (stdDevs[i] != 0.0) ((features[i] - means[i]) / stdDevs[i]).toFloat() else (features[i] - means[i]).toFloat()
        }
        return scaled
    }

    fun stopAndSaveData(): String {
        // 1. Stop Streaming (Cancel Job)
        streamingJob?.cancel()
        streamingJob = null

        if (performanceLogs.isEmpty()) return "Tidak ada data untuk disimpan."

        try {
            // Nama File Unik
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "ECG_Performance_$timeStamp.csv"

            // Lokasi: Folder Documents atau Downloads
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(dir, fileName)

            FileWriter(file).use { writer ->
                // Header CSV
                writer.append("Timestamp,Inference_Time_ms,Memory_Usage_KB,CPU_Usage_Percent\n")

                // Isi Data
                for (log in performanceLogs) {
                    writer.append("${log.timestamp},${log.inferenceTimeMs},${log.memoryUsageKb},${String.format(Locale.US, "%.2f", log.cpuUsagePercent)}\n")
                }
            }
            return "Data tersimpan di Downloads/$fileName"
        } catch (e: Exception) {
            Log.e("CSV_SAVE", "Gagal simpan: ${e.message}")
            return "Gagal menyimpan: ${e.message}"
        }
    }

    private fun getCpuUsage(): Double {
        return try {
            // Ambil waktu CPU total yang dipakai aplikasi ini (dalam milidetik)
            // Process.getElapsedCpuTime() mengembalikan waktu dalam ms
            val currentCpuTime = android.os.Process.getElapsedCpuTime()
            val currentAppTime = System.currentTimeMillis()

            // Jika ini pertama kali dipanggil, inisialisasi saja
            if (lastAppTime == 0L) {
                lastCpuTime = currentCpuTime
                lastAppTime = currentAppTime
                return 0.0
            }

            val cpuDiff = currentCpuTime - lastCpuTime
            val timeDiff = currentAppTime - lastAppTime

            // Jika selisih waktu terlalu kecil (misal < 10ms), return 0 biar ga bagi 0
            if (timeDiff < 10) {
                return 0.0
            }

            // Update state untuk perhitungan berikutnya
            lastCpuTime = currentCpuTime
            lastAppTime = currentAppTime

            // Rumus: (CPU Time Dipakai / Waktu Nyata Lewat) * 100
            // Dibagi jumlah core agar hasilnya 0-100% relatif terhadap total kapasitas HP
            // (Kalau tidak dibagi core, bisa > 100% di HP multi-core)
            val usage = (cpuDiff.toDouble() / timeDiff.toDouble()) * 100.0 / numCores

            // Cap di 100.0 biar rapi
            if (usage > 100.0) 100.0 else usage

        } catch (e: Exception) {
            0.0 // Fail-safe
        }
    }

    override fun onCleared() {
        super.onCleared()
        ecgClassifier?.close()
    }
}