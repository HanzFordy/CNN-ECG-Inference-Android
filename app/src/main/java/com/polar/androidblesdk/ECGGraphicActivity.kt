package com.polar.androidblesdk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.github.mikephil.charting.data.LineDataSet.Mode
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.model.PolarSensorSetting
import com.polar.sdk.api.model.PolarEcgData
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

class EcgGraphActivity : AppCompatActivity() {
    private lateinit var ecgChart: LineChart
    private lateinit var morphologyChart: LineChart
    private var ecgDisposable: Disposable? = null
    private lateinit var api: PolarBleApi

    private var lastRawBatchVoltages: List<Int> = emptyList()
    private var lastFilteredBatchVoltages: List<Double> = emptyList()

    // private lateinit var panTompkinsDetector: DetectorPanTompkinsRealTime // Dihapus

    private val fullEcgSignalBuffer = mutableListOf<Double>()
    private var isCollectingData = false
    private val WARMUP_DURATION_SECONDS = 40 // Jeda 40 detik untuk stabilisasi
    private val COLLECTION_DURATION_SECONDS = 20 // Kumpulkan data selama 20 detik SETELAH jeda

    private var isWarmingUp = false // Flag baru untuk fase pemanasan

    private var collectionStartTime: Long = 0L
    private var currentFsForProcessing: Double = 130.0

    private val MIN_R_PEAKS_FOR_HR_CALCULATION = 2

    private val allEcgSegments = mutableListOf<List<Double>>()
    private val allMorphologyFeaturesScaled = mutableListOf<FloatArray>()

    private lateinit var ecgClassifier: EcgClassifierTFLite

    private lateinit var saveDataButton: Button // Deklarasi untuk tombol simpan


    companion object {
        private const val TAG = "EcgGraphActivity"
        const val LABEL_RAW = "ECG Raw"
        const val LABEL_BASELINE_FILTERED = "ECG Baseline Filtered"
        const val LABEL_DIFFERENTIATED = "Differentiated"
        const val LABEL_SQUARED = "Squared"
        const val LABEL_SMOOTHED = "Smoothed (MWI)"
        const val LABEL_R_PEAKS = "R-Peaks"

        // --- MASUKKAN NILAI DARI scaler_means.txt KE SINI (TOTAL 37 NILAI) ---
        private val SCALER_MEANS: DoubleArray = doubleArrayOf(
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
        // --- MASUKKAN NILAI DARI scaler_stddevs.txt KE SINI (TOTAL 37 NILAI) ---
        private val SCALER_STD_DEVS: DoubleArray = doubleArrayOf(
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ecg_graph)

        ecgChart = findViewById(R.id.ecg_chart)
        morphologyChart = findViewById(R.id.morphology_chart)
        val ecgDataText = findViewById<TextView>(R.id.ecg_data_text)
        val showBatchButton = findViewById<Button>(R.id.show_batch_button)


        // runFeatureComparisonTest() (buat komparasi klo butuh)

        try {
            ecgClassifier = EcgClassifierTFLite(applicationContext) // Menggunakan applicationContext
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menginisialisasi EcgClassifierTFLite di onCreate", e)
            Toast.makeText(this, "Error memuat model klasifikasi! Aplikasi mungkin tidak berfungsi dengan benar.", Toast.LENGTH_LONG).show()
            // Anda bisa menonaktifkan tombol inferensi atau fungsionalitas terkait di sini
        }

        ecgChart.setTouchEnabled(true); ecgChart.description.isEnabled = false; ecgChart.setDrawGridBackground(false)
        ecgChart.xAxis.apply { position = XAxis.XAxisPosition.BOTTOM; setDrawGridLines(false); textColor = Color.parseColor("#EBE6E0"); axisLineColor = Color.parseColor("#EBE6E0") }
        ecgChart.axisLeft.apply { textColor = Color.parseColor("#EBE6E0"); axisLineColor = Color.parseColor("#EBE6E0"); gridColor = Color.DKGRAY }
        ecgChart.axisRight.isEnabled = false
        ecgChart.setTouchEnabled(true); ecgChart.description.isEnabled = false; ecgChart.setDrawGridBackground(false)
        ecgChart.xAxis.apply { position = XAxis.XAxisPosition.BOTTOM; setDrawGridLines(false); textColor = Color.parseColor("#EBE6E0"); axisLineColor = Color.parseColor("#EBE6E0") }
        morphologyChart.axisLeft.apply { textColor = Color.parseColor("#EBE6E0"); axisLineColor = Color.parseColor("#EBE6E0"); gridColor = Color.DKGRAY }
        morphologyChart.axisRight.isEnabled = false

        setupEcgChartData()
        setupMorphologyChartData()

        val deviceId = intent.getStringExtra("DEVICE_ID") ?: return
        api = PolarApiSingleton.getApi(this)
        startEcgStream(deviceId, ecgDataText)

        showBatchButton.setOnClickListener {
            showBatchDialog()
        }
    }

    private fun setupEcgChartData() {
        val rawDataSet = LineDataSet(null, LABEL_RAW).apply {
            lineWidth = 1.5f
            setDrawCircles(false); setDrawValues(false); color = Color.CYAN
        }
        val baselineFilteredDataSet = LineDataSet(null, LABEL_BASELINE_FILTERED).apply {
            lineWidth = 1.5f
            setDrawCircles(false); setDrawValues(false); color = Color.MAGENTA
        }

        val rPeakDataSet = LineDataSet(null, LABEL_R_PEAKS).apply {
            color = Color.TRANSPARENT // Warna penanda R-peak
            lineWidth = 0f // Tidak menggambar garis antar R-peak
            setDrawCircles(true) // Aktifkan lingkaran
            setCircleColor(Color.YELLOW)
            circleRadius = 5f // Ukuran lingkaran
            setDrawCircleHole(false)
            setDrawValues(false) // Tidak menampilkan nilai di atas titik
            mode = LineDataSet.Mode.CUBIC_BEZIER // Ini hanya agar tidak error, karena tidak ada garis
        }

        val dataSets = ArrayList<ILineDataSet>();
        dataSets.add(rawDataSet);
        dataSets.add(baselineFilteredDataSet)
        dataSets.add(rPeakDataSet)

        ecgChart.data = LineData(dataSets);
        ecgChart.invalidate()
    }

    private fun setupMorphologyChartData() {
        val differentiatedDataSet = LineDataSet(null, LABEL_DIFFERENTIATED).apply { color = Color.RED; lineWidth = 1.0f; setDrawCircles(false); setDrawValues(false) }
        val squaredDataSet = LineDataSet(null, LABEL_SQUARED).apply { color = Color.rgb(255,165,0) ; lineWidth = 1.0f; setDrawCircles(false); setDrawValues(false) } // Oranye
        val smoothedDataSet = LineDataSet(null, LABEL_SMOOTHED).apply { color = Color.YELLOW; lineWidth = 1.5f; setDrawCircles(false); setDrawValues(false) }
        val dataSets = ArrayList<ILineDataSet>(); dataSets.add(differentiatedDataSet); dataSets.add(squaredDataSet); dataSets.add(smoothedDataSet)
        morphologyChart.data = LineData(dataSets); morphologyChart.invalidate()
    }


    @SuppressLint("CheckResult")
    private fun startEcgStream(deviceId: String, ecgDataText: TextView) {
        api.requestStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.ECG)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ settings ->
                val sensorSetting = settings.maxSettings()
                currentFsForProcessing = sensorSetting.settings[PolarSensorSetting.SettingType.SAMPLE_RATE]
                    ?.firstOrNull()?.toDouble() ?: 130.0
                Log.d(TAG, "Fs: $currentFsForProcessing Hz. Memulai fase pemanasan ($WARMUP_DURATION_SECONDS detik)...")

                // Reset untuk sesi baru
                fullEcgSignalBuffer.clear()
                allEcgSegments.clear()
                allMorphologyFeaturesScaled.clear()

                isWarmingUp = true // Mulai dengan fase pemanasan
                isCollectingData = false // Belum mulai koleksi data
                collectionStartTime = System.currentTimeMillis() // Mulai timer utama sekarang

                // ... (Reset grafik) ...
                ecgDataText.text = "Pemanasan sensor ($WARMUP_DURATION_SECONDS detik)..."
                var samplesCollectedInStream = 0L

                ecgDisposable = api.startEcgStreaming(deviceId, sensorSetting)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ ecgData: PolarEcgData ->
                        val currentTime = System.currentTimeMillis()
                        val elapsedTimeSeconds = (currentTime - collectionStartTime) / 1000

                        // --- LOGIKA BARU DENGAN FASE PEMANASAN ---
                        if (isWarmingUp) {
                            if (elapsedTimeSeconds < WARMUP_DURATION_SECONDS) {
                                // Masih dalam fase pemanasan, hanya update grafik mentah (jika mau) dan UI
                                val rawSamples = ecgData.samples
                                val rawEcgDs = ecgChart.data?.getDataSetByIndex(0) as? LineDataSet
                                val batchStartIndexForPlot = samplesCollectedInStream
                                rawSamples.forEachIndexed { index, sample ->
                                    rawEcgDs?.addEntry(Entry((batchStartIndexForPlot + index).toFloat(), sample.voltage.toFloat()))
                                }
                                samplesCollectedInStream += rawSamples.size
                                val maxEntriesRealTime = 1000
                                while (rawEcgDs != null && rawEcgDs.entryCount > maxEntriesRealTime) rawEcgDs.removeFirst()
                                ecgChart.data?.notifyDataChanged(); ecgChart.notifyDataSetChanged()
                                if (rawSamples.isNotEmpty()) ecgChart.moveViewToX((samplesCollectedInStream -1).toFloat())

                                ecgDataText.text = "Pemanasan... (${elapsedTimeSeconds}/${WARMUP_DURATION_SECONDS}s)"
                                return@subscribe // Keluar dari subscribe, jangan proses lebih lanjut
                            } else {
                                // Fase pemanasan selesai, mulai fase koleksi
                                isWarmingUp = false
                                isCollectingData = true
                                fullEcgSignalBuffer.clear() // Kosongkan buffer dari data pemanasan
                                collectionStartTime = currentTime // Reset timer untuk durasi koleksi
                                Log.i(TAG, "Pemanasan selesai. Mulai mengumpulkan data untuk $COLLECTION_DURATION_SECONDS detik.")
                                ecgDataText.text = "Mengumpulkan data... (0/${COLLECTION_DURATION_SECONDS}s)"
                            }
                        }

                        if (isCollectingData) {
                            // Fase koleksi data sedang berlangsung
                            val rawSamples = ecgData.samples
                            rawSamples.forEach { fullEcgSignalBuffer.add(it.voltage.toDouble()) }

                            // Update grafik mentah (opsional, bisa dinonaktifkan saat koleksi)
                            // ...

                            // Update status
                            val collectionTimeElapsed = (currentTime - collectionStartTime) / 1000
                            ecgDataText.text = "Mengumpulkan... (${collectionTimeElapsed}/${COLLECTION_DURATION_SECONDS}s)"

                            // Cek apakah durasi koleksi sudah tercapai
                            if (collectionTimeElapsed >= COLLECTION_DURATION_SECONDS) {
                                isCollectingData = false // Hentikan koleksi
                                if (! (ecgDisposable?.isDisposed ?: true) ) ecgDisposable?.dispose()
                                Log.i(TAG, "Pengumpulan data selesai. Total ${fullEcgSignalBuffer.size} sampel.")
                                ecgDataText.text = "Memproses ${fullEcgSignalBuffer.size} sampel..."
                                runOnUiThread {
                                    processCollectedEcgDataWithOwnExtractor(ArrayList(fullEcgSignalBuffer), currentFsForProcessing, ecgDataText)
                                }
                            }
                        }

                    },
                        { error -> Log.e(TAG, "ECG stream error: ${error.message}"); isCollectingData = false; ecgDataText.text = "Error: ${error.message}" },
                        { Log.d(TAG, "ECG stream complete (disposed or finished).") }
                    )
            }, { error -> Log.e(TAG, "Failed to get ECG settings: ${error.message}") })
    }

    private fun processCollectedEcgDataWithOwnExtractor(signalData: List<Double>, fs: Double, statusTextView: TextView) {
        if (signalData.isEmpty()) {
            Log.w(TAG, "Tidak ada data ECG untuk diproses.")
            statusTextView.text = "Tidak ada data."
            return
        }
        val processingFs = 360.0
        Log.i(TAG, "Memulai pemrosesan ${signalData.size} sampel. Mengasumsikan Fs = $processingFs Hz.")

        // --- Langkah 1 & 2: Filter Baseline dan Pra-pemrosesan Pan-Tompkins ---
        val filteredBaselineSignal = EcgFeatureExtractor.removeBaselineMedian(signalData, processingFs)
        Log.d(TAG, "Baseline filtering selesai.")
        lastFilteredBatchVoltages = filteredBaselineSignal // Untuk dialog

        val differentiatedSignal = EcgFeatureExtractor.differentiate(filteredBaselineSignal)
        val squaredSignal = EcgFeatureExtractor.square(differentiatedSignal)
        val mwiWindowSize = (0.150 * fs).roundToInt().coerceAtLeast(1)
        val smoothedSignal = EcgFeatureExtractor.movingWindowAverage(squaredSignal, mwiWindowSize)

        // --- Langkah 3: Deteksi R-peak menggunakan findRPeaks (metode statis) ---
        Log.d(TAG, "Memanggil findRPeaks...")
        val rPeakIndices = EcgFeatureExtractor.findRPeaks(
            originalSignal = filteredBaselineSignal, // Ini juga jadi input `originalSignal` di dalam fungsi
            fs = processingFs
        )
        Log.i(TAG, "EcgFeatureExtractor (Article-Based) mendeteksi ${rPeakIndices.size} R-peaks.")
        statusTextView.text = "Terdeteksi: ${rPeakIndices.size} R-peaks (Extractor)"

        // Hitung dan Log RR & HR
        if (rPeakIndices.size >= MIN_R_PEAKS_FOR_HR_CALCULATION) {
            val rrIntervals = EcgFeatureExtractor.calculateRRIntervals(rPeakIndices, processingFs)
            val heartRates = EcgFeatureExtractor.calculateHeartRates(rrIntervals)
            Log.d(TAG, "Extractor (Asumsi Fs 360Hz) RR: $rrIntervals")
            Log.d(TAG, "Extractor (Asumsi Fs 360Hz) HR: $heartRates")
            if (heartRates.isNotEmpty()) {
                statusTextView.text = "Total ${rPeakIndices.size} R-peaks. Avg HR (Extractor): ${heartRates.average().roundToInt()} BPM"
            }
        }

        // --- VISUALISASI PADA GRAFIK ---

        // A. Tampilkan data mentah dan hasil filter baseline di Grafik ECG Atas
        val rawEcgDs = ecgChart.data?.getDataSetByLabel(LABEL_RAW, true) as? LineDataSet
        val filteredBaselineDs = ecgChart.data?.getDataSetByLabel(LABEL_BASELINE_FILTERED, true) as? LineDataSet
        rawEcgDs?.clear()
        filteredBaselineDs?.clear()
        signalData.forEachIndexed { index, value -> rawEcgDs?.addEntry(Entry(index.toFloat(), value.toFloat())) }
        filteredBaselineSignal.forEachIndexed { index, value -> filteredBaselineDs?.addEntry(Entry(index.toFloat(), value.toFloat())) }

        // B. Tampilkan penanda R-peak di Grafik ECG Atas
        // Pastikan dataset untuk R-Peaks sudah ditambahkan di setupEcgChartData()
        val rPeakDs = ecgChart.data?.getDataSetByLabel(LABEL_R_PEAKS, true) as? LineDataSet
        rPeakDs?.clear()
        if (rPeakDs != null && rPeakIndices.isNotEmpty()) {
            Log.d(TAG, "Menambahkan ${rPeakIndices.size} penanda R-peak ke grafik...")
            for (rPeakIndex in rPeakIndices) {
                if (rPeakIndex >= 0 && rPeakIndex < filteredBaselineSignal.size) {
                    val yValue = filteredBaselineSignal[rPeakIndex].toFloat()
                    rPeakDs.addEntry(Entry(rPeakIndex.toFloat(), yValue))
                }
            }
        } else if (rPeakDs == null) {
            Log.e(TAG, "Dataset untuk R-Peaks tidak ditemukan! Pastikan sudah dibuat di setupEcgChartData()")
        }

        // C. Tampilkan langkah pra-pemrosesan di Grafik Morfologi Bawah
        val diffDs = morphologyChart.data?.getDataSetByLabel(LABEL_DIFFERENTIATED, true) as? LineDataSet
        val sqDs = morphologyChart.data?.getDataSetByLabel(LABEL_SQUARED, true) as? LineDataSet
        val smoothDs = morphologyChart.data?.getDataSetByLabel(LABEL_SMOOTHED, true) as? LineDataSet
        diffDs?.clear(); sqDs?.clear(); smoothDs?.clear()
        val morphSignalLength = minOf(differentiatedSignal.size, squaredSignal.size, smoothedSignal.size)
        for (i in 0 until morphSignalLength) {
            if (i < differentiatedSignal.size) diffDs?.addEntry(Entry(i.toFloat(), differentiatedSignal[i].toFloat()))
            if (i < squaredSignal.size) sqDs?.addEntry(Entry(i.toFloat(), squaredSignal[i].toFloat()))
            if (i < smoothedSignal.size) smoothDs?.addEntry(Entry(i.toFloat(), smoothedSignal[i].toFloat()))
        }

        // D. Refresh dan atur tampilan kedua grafik
        val maxDisplayPoints = signalData.size.coerceAtMost(2000)
        ecgChart.data?.notifyDataChanged(); ecgChart.notifyDataSetChanged()
        ecgChart.setVisibleXRangeMaximum(maxDisplayPoints.toFloat()); ecgChart.moveViewToX(0f)
        ecgChart.invalidate()
        morphologyChart.data?.notifyDataChanged(); morphologyChart.notifyDataSetChanged()
        morphologyChart.setVisibleXRangeMaximum(maxDisplayPoints.toFloat()); morphologyChart.moveViewToX(0f)
        morphologyChart.invalidate()

        // --- LANJUT KE PERSIAPAN DATA UNTUK INFERENSI ---
        allEcgSegments.clear()
        allMorphologyFeaturesScaled.clear()
        val validRPeakIndicesForInference = mutableListOf<Int>()

        for (rPeakIdx in rPeakIndices) {
            val ecgSegment180 = EcgFeatureExtractor.getEcgSegmentAroundRPeak(
                fullSignal = filteredBaselineSignal, rPeakIndex = rPeakIdx,
                segmentLength = 180, samplesBeforeR = 90
            )
            if (ecgSegment180 != null) {
                val morphFeaturesUnscaledArray = EcgFeatureExtractor.extractKotlinMorphologyFeatures(ecgSegment180, processingFs)
                if (morphFeaturesUnscaledArray.size == 37) {
                    val scaledFeatures = scaleMorphologyFeatures(morphFeaturesUnscaledArray, SCALER_MEANS, SCALER_STD_DEVS)
                    if (scaledFeatures.isNotEmpty()) {
                        allEcgSegments.add(ecgSegment180)
                        allMorphologyFeaturesScaled.add(scaledFeatures)
                        validRPeakIndicesForInference.add(rPeakIdx)
                    }
                }
            }
        }
        Log.i(TAG, "Berhasil membuat ${allEcgSegments.size} pasang segmen dan fitur yang siap untuk inferensi.")

        // Jalankan Inferensi TFLite
        if (allEcgSegments.isNotEmpty()) {
            if (::ecgClassifier.isInitialized && ecgClassifier.isReady()) {
                statusTextView.text = "Memulai inferensi untuk ${allEcgSegments.size} segmen..."
                Log.i(TAG, "Memulai inferensi TFLite...")

                val allPredictionsText = StringBuilder("Hasil Klasifikasi:\n")
                val predictionCounts = IntArray(5) { 0 }

                for (i in allEcgSegments.indices) {
                    val ecgSegment = allEcgSegments[i]
                    val scaledFeatures = allMorphologyFeaturesScaled[i]
                    val rPeakLocation = validRPeakIndicesForInference[i]

                    val (predictedClassIndex, probabilities) = ecgClassifier.classify(ecgSegment, scaledFeatures)

                    if (predictedClassIndex != -1 && probabilities != null) {
                        val className = ecgClassifier.getClassName(predictedClassIndex)
                        allPredictionsText.append("Segmen $i (R-Peak @$rPeakLocation): Kelas=$className, Probs=${probabilities.joinToString { "%.2f".format(it) }}\n")
                        if (predictedClassIndex in predictionCounts.indices) {
                            predictionCounts[predictedClassIndex]++
                        }
                    } else {
                        allPredictionsText.append("Segmen $i (R-Peak @$rPeakLocation): Gagal melakukan inferensi.\n")
                    }
                }
                val classNames = arrayOf("Normal", "SVEB", "VEB", "Fusion", "Unknown")
                var summaryText = "Ringkasan Prediksi:\n"
                predictionCounts.forEachIndexed { index, count ->
                    summaryText += "${classNames[index]}: $count\n"
                }
                Log.i(TAG, allPredictionsText.toString())
                Log.i(TAG, summaryText)
                statusTextView.text = summaryText

            } else {
                statusTextView.text = "Error: Classifier TFLite tidak terinisialisasi."
                Log.e(TAG, "Classifier TFLite tidak terinisialisasi saat akan inferensi.")
            }
        } else {
            statusTextView.text = "Selesai. Tidak ada segmen valid untuk inferensi."
        }
    }

    private fun scaleMorphologyFeatures(
        features: DoubleArray, means: DoubleArray, stdDevs: DoubleArray
    ): FloatArray {
        val scaled = FloatArray(features.size)
        if (features.size != means.size || features.size != stdDevs.size) {
            Log.e(TAG, "Array size mismatch in scaling! F:${features.size}, M:${means.size}, S:${stdDevs.size}")
            return FloatArray(0)
        }
        for (i in features.indices) {
            if (stdDevs[i] != 0.0 && !stdDevs[i].isNaN() && !stdDevs[i].isInfinite()) {
                scaled[i] = ((features[i] - means[i]) / stdDevs[i]).toFloat()
            } else {
                scaled[i] = (features[i] - means[i]).toFloat()
                Log.w(TAG, "StdDev zero/invalid for feature $i during scaling. Value: ${stdDevs[i]}")
            }
        }
        return scaled
    }

    private fun showBatchDialog() { /* ... (Sama seperti sebelumnya, pastikan variabelnya benar) ... */ }

    fun runFeatureComparisonTest() { // Atau nama lain
        val testBeatSegmentFromPython: List<Double> = listOf(
            // SALIN DAN TEMPEL 180 NILAI DARI debug_selected_beat_segment.txt KE SINI
            // Contoh:
            0.01000000, 0.01000000, -0.01000000, -0.01500000, -0.01000000, -0.01000000, -0.01000000,
            -0.02000000, -0.02500000, -0.02500000, -0.02000000, -0.02000000, 0.00000000, -0.00500000,
            -0.01500000, -0.02500000, -0.02500000, -0.00500000, -0.00500000, -0.00500000, -0.00500000,
            -0.00500000, -0.01500000, 0.00500000, 0.01500000, 0.03500000, 0.04000000, 0.03000000, 0.03000000,
            0.03500000, 0.05000000, 0.06500000, 0.05500000, 0.04000000, 0.05500000, 0.09000000, 0.13500000,
            0.16500000, 0.15000000, 0.11000000, 0.07000000, 0.11000000, 0.11500000, 0.13000000, 0.10500000,
            0.07500000, 0.05500000, 0.06500000, 0.07500000, 0.05500000, 0.05000000, 0.02500000, 0.00500000,
            0.00000000, 0.00500000, -0.00500000, -0.01500000, -0.01500000, -0.03000000, -0.01500000, -0.01000000,
            -0.00500000, -0.00500000, -0.01500000, -0.01000000, 0.00500000, 0.00000000, -0.04500000, -0.09000000,
            -0.09000000, -0.07500000, -0.04000000, -0.00500000, 0.01000000, -0.01500000, -0.05000000, -0.07500000,
            -0.08500000, -0.06000000, -0.06500000, -0.06500000, -0.08000000, -0.07500000, 0.00000000, 0.15000000,
            0.38000000, 0.64500000, 0.93500000, 1.24000000, 1.48500000, 1.52500000, 1.31000000, 0.97500000,
            0.66500000, 0.36500000, 0.17000000, 0.08000000, 0.05500000, -0.00500000, -0.02500000, -0.04500000,
            -0.04500000, -0.04500000, -0.04500000, -0.08000000, -0.10500000, -0.13000000, -0.11500000, -0.09500000,
            -0.09500000, -0.10000000, -0.10500000, -0.12500000, -0.11500000, -0.10500000, -0.11000000, -0.10000000,
            -0.10500000, -0.11000000, -0.11000000, -0.10000000, -0.09000000, -0.11000000, -0.10000000, -0.12000000,
            -0.11000000, -0.11500000, -0.10500000, -0.11500000, -0.11000000, -0.11000000, -0.12000000, -0.11000000,
            -0.10000000, -0.10500000, -0.10000000, -0.10500000, -0.09500000, -0.09500000, -0.10500000, -0.09500000,
            -0.10000000, -0.11500000, -0.10500000, -0.09000000, -0.08000000, -0.07000000, -0.08000000, -0.11000000,
            -0.11000000, -0.10500000, -0.10500000, -0.10000000, -0.10000000, -0.10000000, -0.07500000, -0.07000000,
            -0.07500000, -0.07500000, -0.08500000, -0.08000000, -0.07000000, -0.05000000, -0.05000000, -0.06500000,
            -0.07500000, -0.07500000, -0.04000000, -0.02500000, -0.01500000, -0.03000000, -0.02000000, -0.02000000,
            0.00000000, 0.00000000, 0.00500000, 0.01000000, -0.00500000, -0.01000000, 0.00000000
        )
        val testFsKotlin = 130.0 // Frekuensi sampling yang digunakan di Kotlin

        val pythonFeaturesExpected: DoubleArray = doubleArrayOf(
            // SALIN DAN TEMPEL 37 NILAI FITUR DARI debug_python_morph_features.txt KE SINI
            // Contoh:
            2.361111156642436981e-02, 2.538454532623291016e-01, 6.443751603364944458e-02, 4.253624916076660156e+00, 1.900366783142089844e+01,
            1.524999976158142090e+00, -1.299999952316284180e-01, 1.654999971389770508e+00, 3.000000000000000000e+00, 1.524999976158142090e+00,
            5.000000000000000000e-01, 4.000000000000000000e+01, 1.111111145019531250e+02, 1.142500019073486328e+01, 1.650000065565109253e-01,
            -2.999999932944774628e-02, 2.407500028610229492e+00, 9.999999776482582092e-03, -1.199999973177909851e-01, 4.559999942779541016e+00,
            3.050000071525573730e-01, -3.350000083446502686e-01, -5.586592305917292833e-05, 4.615639336407184601e-03, 9.000000000000000000e+01,
            9.000000000000000000e+01, 1.169909954071044922e+01, 6.499499827623367310e-02, 2.549411654472351074e-01, 1.400000000000000000e+01,
            9.242424011230468750e+00, 1.524998474121093750e+02, 2.296089410781860352e+00, 9.474917054176330566e-01, 5.000000000000000000e-01,
            4.852499961853027344e+00, -2.112499952316284180e+00
        )

        // Panggil fungsi ekstraksi fitur Kotlin Anda
        Log.d("FeatureValidation", "--- Memulai Uji Ekstraksi Fitur Kotlin vs Python ---")
        val kotlinFeaturesUnscaled = EcgFeatureExtractor.extractKotlinMorphologyFeatures(testBeatSegmentFromPython, testFsKotlin)

        // Kode perbandingan yang saya berikan sebelumnya
        if (kotlinFeaturesUnscaled.size == 37 && pythonFeaturesExpected.size == 37) {
            // ... (loop untuk membandingkan dan log perbedaan) ...
            var allMatchPerfectly = true
            val tolerance = 1e-5 // Tentukan toleransi
            Log.d("FeatureValidation", "Idx | Kotlin       | Python       | Diff         | Status")
            Log.d("FeatureValidation", "--------------------------------------------------------------------")
            for (i in kotlinFeaturesUnscaled.indices) {
                val ktVal = kotlinFeaturesUnscaled[i]
                val pyVal = pythonFeaturesExpected[i]
                val diff = kotlin.math.abs(ktVal - pyVal)
                var matchStatus = if (diff < tolerance) "OK" else "MISMATCH !!!"
                if (diff >= tolerance) allMatchPerfectly = false
                Log.d("FeatureValidation", String.format(
                    Locale.US,
                    "F[%02d] | %-12.6f | %-12.6f | %-12.6e | %s",
                    i, ktVal, pyVal, diff, matchStatus
                ))
            }
            // ... (log hasil akhir perbandingan) ...
        } else { /* ... Log error jumlah fitur tidak cocok ... */ }
        Log.d("FeatureValidation", "--- Selesai Uji Ekstraksi Fitur Kotlin vs Python ---")
    }

    override fun onDestroy() {
        super.onDestroy();
        isCollectingData = false;
        ecgDisposable?.dispose();
        if (::ecgClassifier.isInitialized) { // Pengecekan lateinit di sini tetap OK
            ecgClassifier.close()
        }
        Log.d(TAG, "EcgGraphActivity destroyed.") }
}