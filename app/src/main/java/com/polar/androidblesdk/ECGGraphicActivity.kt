package com.polar.androidblesdk

import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.polar.sdk.api.PolarBleApi
import kotlinx.coroutines.launch

class EcgGraphActivity : AppCompatActivity() {

    private val viewModel: ECGViewModel by viewModels()

    private lateinit var ecgDataText: TextView
    private lateinit var ecgChart: LineChart
    private lateinit var progressBar: ProgressBar

    private lateinit var tvLatencyStats: TextView
    private lateinit var tvMemoryStats: TextView
    private lateinit var tvCpuStats: TextView

    private lateinit var api: PolarBleApi
    private var deviceId: String? = null
    private val MAX_VISIBLE_ENTRIES = 400
    private val MAX_BUFFER_ENTRIES = 800
    private val graphManager = EcgGraphManager(MAX_VISIBLE_ENTRIES)
    private val markerDataSets = mutableMapOf<String, LineDataSet>()

    private val classColors = mapOf(
        "N" to Color.GREEN,
        "Normal" to Color.GREEN,
        "S" to Color.CYAN,
        "SVEB" to Color.CYAN,
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
        ecgChart = findViewById(R.id.ecg_chart)
        ecgDataText = findViewById(R.id.ecg_data_text)
        progressBar = findViewById(R.id.buffering_progress_bar)

        tvLatencyStats = findViewById(R.id.tv_latency_stats)
        tvMemoryStats = findViewById(R.id.tv_memory_stats)
        tvCpuStats = findViewById(R.id.tv_cpu_stats)

        setupChart()
        deviceId = intent.getStringExtra("DEVICE_ID")
        if (deviceId == null) {
            Toast.makeText(this, "Device ID Missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        api = PolarAPISingle.getApi(this)
        viewModel.startStreaming(api, deviceId!!)
        observeViewModel()

        restartButton.isEnabled = false
        restartButton.alpha = 0.5f

        stopButton.setOnClickListener {
            val resultMessage = viewModel.stopAndSaveData()
            Toast.makeText(this, resultMessage, Toast.LENGTH_LONG).show()

            stopButton.isEnabled = false
            stopButton.alpha = 0.5f

            restartButton.isEnabled = true
            restartButton.alpha = 1.0f
        }

        restartButton.setOnClickListener {
            restartButton.isEnabled = false
            restartButton.alpha = 0.5f

            stopButton.isEnabled = true
            stopButton.alpha = 1.0f

            ecgChart.clear()
            ecgChart.notifyDataSetChanged()
            ecgChart.invalidate()

            setupChart()
            graphManager.reset()
            if (deviceId != null) {
                viewModel.startStreaming(api, deviceId!!)
            }
        }
    }

    private fun setupChart() {
        val lineData = LineData()
        val filteredSet = createLineSet("ECG Filtered", Color.parseColor("#00FFFF"))
        lineData.addDataSet(filteredSet)
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
        }
        ecgChart.axisRight.apply {
            isEnabled = true
            setDrawLabels(false)
            setDrawGridLines(false)
            axisMinimum = 0f
            axisMaximum = 2000f
        }
    }

    private fun observeViewModel() {
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

                        tvLatencyStats.text = state.latencyInfo
                        tvMemoryStats.text = state.memoryInfo
                        tvCpuStats.text = state.cpuInfo
                    }
                    is ECGUIState.Error -> {
                        ecgDataText.text = "Error: ${state.message}"
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.newGraphData.collect { chunk ->
                addNewDataToGraph(chunk)
            }
        }
        lifecycleScope.launch {
            viewModel.predictionMarkerEvent.collect { marker ->
                Log.d("MARKER_DEBUG", "Terima Marker: ${marker.label} di X=${marker.xIndex}")
                graphManager.enqueueMarker(marker)
            }
        }
    }
    private fun addNewDataToGraph(cleanData: DoubleArray) {
        val data = ecgChart.data ?: return
        val setFiltered = data.getDataSetByIndex(0) as? LineDataSet ?: return
        val result = graphManager.processChunk(cleanData.size)

        if (result.markersToShow.isNotEmpty()) {
            Log.d("MARKER_DEBUG", "Menggambar ${result.markersToShow.size} Marker. Contoh X=${result.markersToShow[0].x}")
        }
        var x = result.startX.toFloat()
        for (v in cleanData) {
            data.addEntry(Entry(x, v.toFloat()), 0)
            x += 1f
        }
        for (marker in result.markersToShow) {
            val markerSet = markerDataSets[marker.label]
            if (markerSet != null) {
                val pinColor = classColors[marker.label] ?: Color.WHITE
                val entry = Entry(marker.x, marker.y, marker.label)
                data.addEntry(entry, data.getIndexOfDataSet(markerSet))
            }
        }
        trimDataSet(setFiltered, MAX_BUFFER_ENTRIES)
        markerDataSets.values.forEach { trimDataSet(it, MAX_BUFFER_ENTRIES) }
        data.notifyDataChanged()
        ecgChart.notifyDataSetChanged()

        ecgChart.setVisibleXRangeMaximum(MAX_VISIBLE_ENTRIES.toFloat())
        ecgChart.moveViewToX(graphManager.lastGraphIndex.toFloat())
    }

    private fun trimDataSet(set: LineDataSet, maxEntries: Int) {
        val extra = set.entryCount - maxEntries
        if (extra > 0) {
            for (i in 0 until extra) {
                set.removeEntry(0)
            }
        }
    }
    private fun createLineSet(label: String, color: Int): LineDataSet {
        return LineDataSet(null, label).apply {
            lineWidth = 1.5f
            this.color = color
            setDrawValues(false)
            setDrawCircles(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            axisDependency = YAxis.AxisDependency.LEFT
        }
    }

    private fun createMarkerSet(label: String, color: Int): LineDataSet {
        return LineDataSet(null, label).apply {
            axisDependency = YAxis.AxisDependency.LEFT

            setDrawCircles(true)
            setCircleColor(color)
            circleRadius = 6f
            setDrawCircleHole(false)

            setDrawValues(true)
            valueTextSize = 12f
            valueTextColor = color

            this.color = Color.parseColor("#4C7766")
            lineWidth = 0f
            isHighlightEnabled = false

            valueFormatter = object : ValueFormatter() {
                override fun getPointLabel(entry: Entry?): String {
                    return entry?.data as? String ?: ""
                }
            }
        }
    }

}