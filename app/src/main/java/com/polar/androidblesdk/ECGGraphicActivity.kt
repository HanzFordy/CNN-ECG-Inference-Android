package com.polar.androidblesdk

import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.utils.MPPointF
import com.polar.sdk.api.PolarBleApi
import kotlinx.coroutines.launch

class EcgGraphActivity : AppCompatActivity() {

    private val viewModel: ECGViewModel by viewModels()

    private lateinit var ecgDataText: TextView
    private lateinit var ecgChart: LineChart
    private lateinit var progressBar: ProgressBar

    private lateinit var api: PolarBleApi
    private var deviceId: String? = null

    // --- GRAPH MANAGER ---
    private val MAX_VISIBLE_ENTRIES = 400
    private val MAX_BUFFER_ENTRIES = 800
    private val graphManager = EcgGraphManager(MAX_VISIBLE_ENTRIES)

    // Dataset Marker per Kelas (N, S, V...)
    private val markerDataSets = mutableMapOf<String, LineDataSet>()

    private val classColors = mapOf(
        "N" to Color.GREEN,
        "Normal" to Color.GREEN, // Tambah ini
        "S" to Color.CYAN,
        "SVEB" to Color.CYAN,    // Tambah ini juga biar aman
        "V" to Color.YELLOW,
        "VEB" to Color.YELLOW,
        "F" to Color.MAGENTA,
        "Fusion" to Color.MAGENTA,
        "Q" to Color.WHITE,
        "Unknown" to Color.GRAY
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ecg_graph)

        val stopButton: Button = findViewById(R.id.stop_save_button)
        val restartButton: Button = findViewById(R.id.restart_button)

        // 1. Init View
        ecgChart = findViewById(R.id.ecg_chart)
        ecgDataText = findViewById(R.id.ecg_data_text)
        progressBar = findViewById(R.id.buffering_progress_bar)

        // 2. Setup Chart
        setupChart()

        // 3. Connect API
        deviceId = intent.getStringExtra("DEVICE_ID")
        if (deviceId == null) {
            Toast.makeText(this, "Device ID Missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        api = PolarAPISingle.getApi(this)

        // 4. Start Logic
        viewModel.startStreaming(api, deviceId!!)

        // 5. Observe Data
        observeViewModel()

        restartButton.isEnabled = false
        restartButton.alpha = 0.5f

        stopButton.setOnClickListener {
            // Panggil fungsi simpan
            val resultMessage = viewModel.stopAndSaveData()

            // Tampilkan hasil
            Toast.makeText(this, resultMessage, Toast.LENGTH_LONG).show()

            stopButton.isEnabled = false
            stopButton.alpha = 0.5f

            restartButton.isEnabled = true
            restartButton.alpha = 1.0f
        }

        restartButton.setOnClickListener {
            // Balikkan state tombol
            restartButton.isEnabled = false
            restartButton.alpha = 0.5f

            stopButton.isEnabled = true
            stopButton.alpha = 1.0f

            ecgChart.clear() // Hapus data lama di layar
            ecgChart.notifyDataSetChanged()
            ecgChart.invalidate()

            setupChart()

            // RESET MANAGER (WAJIB!)
            graphManager.reset()

            // Start ulang
            if (deviceId != null) {
                viewModel.startStreaming(api, deviceId!!)
            }
        }
    }

    private fun setupChart() {
        val lineData = LineData()

        // Dataset Utama (Sinyal ECG) - Index 0
        val filteredSet = createLineSet("ECG Filtered", Color.parseColor("#00FFFF")) // Cyan
        lineData.addDataSet(filteredSet)

        // Dataset Marker (Pin) - Index 1, 2, 3...
        classColors.forEach { (label, color) ->
            val markerSet = createMarkerSet(label, color)
            markerDataSets[label] = markerSet
            lineData.addDataSet(markerSet)
        }

        ecgChart.data = lineData
        ecgChart.description.isEnabled = false
        ecgChart.legend.isEnabled = true
        ecgChart.axisRight.isEnabled = false
        ecgChart.setTouchEnabled(true)

        ecgChart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            textColor = Color.WHITE
        }
        ecgChart.axisLeft.apply {
            setDrawGridLines(true)
            textColor = Color.WHITE
            // axisMinimum = -2000f // Opsional: Fix scale biar ga goyang
            // axisMaximum = 2000f
        }
        ecgChart.axisRight.apply {
            isEnabled = true // Hidupkan
            setDrawLabels(false) // Jangan gambar angka
            setDrawGridLines(false)
            axisMinimum = 0f // Atur range marker sesuka hati
            axisMaximum = 2000f
        }
    }

    private fun observeViewModel() {
        // A. Observe UI State (Teks & Progress)
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is ECGUIState.Buffering -> {
                        ecgDataText.text = "Mengkalibrasi... ${state.progressSeconds}/${state.totalSeconds}"
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = state.progressSeconds
                        progressBar.max = state.totalSeconds
                        ecgChart.visibility = View.GONE
                    }
                    is ECGUIState.Streaming -> {
                        ecgDataText.text = state.summaryText
                        progressBar.visibility = View.GONE
                        ecgChart.visibility = View.VISIBLE
                    }
                    is ECGUIState.Error -> {
                        ecgDataText.text = "Error: ${state.message}"
                    }
                }
            }
        }

        // B. Observe Graph Data (Sinyal Baru)
        lifecycleScope.launch {
            viewModel.newGraphData.collect { chunk ->
                addNewDataToGraph(chunk)
            }
        }

        // C. Observe Marker (Antrikan ke Manager)
        lifecycleScope.launch {
            viewModel.predictionMarkerEvent.collect { marker ->
                Log.d("MARKER_DEBUG", "Terima Marker: ${marker.label} di X=${marker.xIndex}")
                graphManager.enqueueMarker(marker)
            }
        }
    }

    // --- LOGIC GAMBAR UTAMA (Powered by EcgGraphManager) ---
    private fun addNewDataToGraph(cleanData: DoubleArray) {
        val data = ecgChart.data ?: return
        val setFiltered = data.getDataSetByIndex(0) as? LineDataSet ?: return

        // 1. Minta Manager hitung posisi X dan ambil marker yang siap tampil
        val result = graphManager.processChunk(cleanData.size)

        if (result.markersToShow.isNotEmpty()) {
            Log.d("MARKER_DEBUG", "Menggambar ${result.markersToShow.size} Marker. Contoh X=${result.markersToShow[0].x}")
        }

        // 2. Tambah Data Sinyal (X berurut)
        var x = result.startX.toFloat()
        for (v in cleanData) {
            data.addEntry(Entry(x, v.toFloat()), 0) // Index 0 = Sinyal
            x += 1f
        }

        // 3. Tambah Data Marker (Jika ada)
        for (marker in result.markersToShow) {
            val markerSet = markerDataSets[marker.label]
            if (markerSet != null) {
                val pinColor = classColors[marker.label] ?: Color.WHITE
                val entry = Entry(marker.x, marker.y, marker.label)

                // Tambahkan ke dataset yang sesuai
                data.addEntry(entry, data.getIndexOfDataSet(markerSet))
            }
        }

        // 4. Potong Data Lama (Biar Memori Aman)
        trimDataSet(setFiltered, MAX_BUFFER_ENTRIES)
        markerDataSets.values.forEach { trimDataSet(it, MAX_BUFFER_ENTRIES) }

        // 5. Update Tampilan & Auto Scroll
        data.notifyDataChanged()
        ecgChart.notifyDataSetChanged()

        ecgChart.setVisibleXRangeMaximum(MAX_VISIBLE_ENTRIES.toFloat())
        ecgChart.moveViewToX(graphManager.lastGraphIndex.toFloat()) // Geser ke paling kanan
    }

    private fun trimDataSet(set: LineDataSet, maxEntries: Int) {
        val extra = set.entryCount - maxEntries
        if (extra > 0) {
            // Remove dari awal (kiri)
            for (i in 0 until extra) {
                set.removeEntry(0)
            }
        }
    }

    // --- Helpers ---
    private fun createLineSet(label: String, color: Int): LineDataSet {
        return LineDataSet(null, label).apply {
            lineWidth = 1.5f
            this.color = color
            setDrawValues(false)
            setDrawCircles(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER // Atau LINEAR
            axisDependency = YAxis.AxisDependency.LEFT
        }
    }

    private fun createMarkerSet(label: String, color: Int): LineDataSet {
        return LineDataSet(null, label).apply {
            axisDependency = YAxis.AxisDependency.LEFT // Balikin ke LEFT biar sejajar sinyal

            setDrawCircles(true) // HIDUPKAN LINGKARAN
            setCircleColor(color)
            circleRadius = 6f
            setDrawCircleHole(false)

            setDrawValues(true) // HIDUPKAN TEKS
            valueTextSize = 12f
            valueTextColor = color

            this.color = Color.parseColor("#4C7766")
            lineWidth = 0f // Tetap tanpa garis
            isHighlightEnabled = false

            valueFormatter = object : ValueFormatter() {
                override fun getPointLabel(entry: Entry?): String {
                    // Ambil data label yang kita simpan di Entry(x, y, label)
                    return entry?.data as? String ?: ""
                }
            }
        }
    }

}