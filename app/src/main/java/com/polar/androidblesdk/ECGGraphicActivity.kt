package com.polar.androidblesdk

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.polar.sdk.api.PolarBleApi
import kotlinx.coroutines.launch

class EcgGraphActivity : AppCompatActivity() {

    // Inisialisasi ViewModel dengan cara yang benar
    private val viewModel: ECGViewModel by viewModels()

    // Variabel yang dibutuhkan oleh UI (View)
    private lateinit var ecgDataText: TextView
    private lateinit var ecgChart: LineChart
    private lateinit var morphologyChart: LineChart
    private lateinit var progressBar: ProgressBar
    private lateinit var api: PolarBleApi
    private var deviceId: String? = null

    companion object {
        private const val TAG = "EcgGraphActivity"

        const val LABEL_RAW = "ECG Raw"
        const val LABEL_BASELINE_FILTERED = "ECG Baseline Filtered"
        const val LABEL_DIFFERENTIATED = "Differentiated"
        const val LABEL_SQUARED = "Squared"
        const val LABEL_SMOOTHED = "Smoothed (MWI)"
        const val LABEL_R_PEAKS = "R-Peaks"

        // Biarkan konstanta scaler di sini, karena ini adalah data statis
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ecg_graph)

        // --- 1. SETUP UI ---
        ecgChart = findViewById(R.id.ecg_chart)
        morphologyChart = findViewById(R.id.morphology_chart)
        ecgDataText = findViewById(R.id.ecg_data_text)
        progressBar = findViewById(R.id.buffering_progress_bar)

        setupChart() // Panggil fungsi untuk setup grafik

        deviceId = intent.getStringExtra("DEVICE_ID")
        if (deviceId == null) {
            Toast.makeText(this, "Device ID tidak ditemukan!", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        api = PolarAPISingle.getApi(this)

        // --- 3. SURUH VIEWMODEL BEKERJA ---
        Log.d("ECG_FLOW_DEBUG", "ACTIVITY: Memerintahkan ViewModel untuk memulai.")
        viewModel.startStreaming(api, deviceId!!)

        observeViewModel()
    }

    private fun setupChart() {
        // Konfigurasi tampilan umum chart
        ecgChart.description.isEnabled = false
        ecgChart.setDrawGridBackground(false)
        // ... (konfigurasi lain: xAxis, axisLeft, dll.)

        // Buat semua DataSet yang kita butuhkan, lalu pasang ke chart
        val signalDataSet = createSignalDataSet()

        val lineData = LineData(signalDataSet)
        ecgChart.data = lineData
        ecgChart.invalidate()
    }

    private fun createSignalDataSet(): LineDataSet {
        return LineDataSet(null, "Filtered ECG").apply {
            color = Color.CYAN
            lineWidth = 1.5f
            setDrawValues(false)
            setDrawCircles(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
    }

    private fun observeViewModel(){
        lifecycleScope.launch {
            Log.d("ECG_FLOW_DEBUG", "ACTIVITY: Mulai 'mendengarkan' ViewModel.")
            viewModel.uiState
                .collect { state ->
                    Log.d("ECG_FLOW_DEBUG", "ACTIVITY: Menerima state baru")

                    when (state) {
                        is ECGUIState.Buffering -> {
                            ecgDataText.text = "Mohon tunggu, sedang mengkalibrasi dan mengumpulkan data awal..."
                            progressBar.visibility = android.view.View.VISIBLE // Tampilkan progress bar
                            progressBar.max = state.totalSeconds // Atur nilai maks
                            progressBar.progress = state.progressSeconds // Update progress
                            ecgChart.visibility = android.view.View.INVISIBLE // Sembunyikan chart saat buffering

                        }
                        is ECGUIState.Error -> {
                            ecgDataText.text = "Error: ${state.message}"
                            progressBar.visibility = android.view.View.GONE // Sembunyikan jika error
                            ecgChart.visibility = android.view.View.VISIBLE
                        }
                        is ECGUIState.Streaming -> {
                            ecgDataText.text = state.summaryText

                            progressBar.visibility = android.view.View.GONE // Sembunyikan progress bar
                            ecgChart.visibility = android.view.View.VISIBLE // Tampilkan chart

                            // --- LOGIKA BARU UNTUK MENGGAMBAR GRAFIK ---
                            state.ecgDataPoints?.let { dataPoints ->
                                val signalDataSet =
                                    ecgChart.data.getDataSetByIndex(0) as LineDataSet

                                // Gunakan cara yang paling aman: clear() lalu addEntry()
                                signalDataSet.clear()
                                dataPoints.forEach { entry ->
                                    signalDataSet.addEntry(entry)
                                }

                                ecgChart.data.notifyDataChanged()
                                ecgChart.notifyDataSetChanged()
                                ecgChart.invalidate()
                            }
                        }

                        is ECGUIState.Error -> {
                            ecgDataText.text = "Error: ${state.message}"
                        }
                    }
                }
        }
    }

    private fun setupEcgChartData() {
        // 1. Konfigurasi tampilan umum dari chart
        ecgChart.setTouchEnabled(true)
        ecgChart.description.isEnabled = false
        ecgChart.setDrawGridBackground(false)

        ecgChart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            textColor = Color.parseColor("#EBE6E0")
            axisLineColor = Color.parseColor("#EBE6E0")
        }

        ecgChart.axisLeft.apply {
            textColor = Color.parseColor("#EBE6E0")
            axisLineColor = Color.parseColor("#EBE6E0")
            gridColor = Color.DKGRAY
        }

        ecgChart.axisRight.isEnabled = false

        // 2. Buat SATU dataset untuk menampung data real-time kita
        val dataSet = LineDataSet(null, "Filtered ECG").apply {
            lineWidth = 1.5f
            setDrawCircles(false)
            setDrawValues(false)
            color = Color.CYAN
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        // 3. Buat LineData dan masukkan dataset kosong tersebut ke dalamnya
        val lineData = LineData(dataSet)

        // 4. Pasang LineData ke chart. SELESAI.
        ecgChart.data = lineData

        // (Tidak perlu invalidate() di sini, karena belum ada data)
    }

    private fun setupMorphologyChartData() {
        val differentiatedDataSet = LineDataSet(null, LABEL_DIFFERENTIATED).apply { color = Color.RED; lineWidth = 1.0f; setDrawCircles(false); setDrawValues(false) }
        val squaredDataSet = LineDataSet(null, LABEL_SQUARED).apply { color = Color.rgb(255,165,0) ; lineWidth = 1.0f; setDrawCircles(false); setDrawValues(false) } // Oranye
        val smoothedDataSet = LineDataSet(null, LABEL_SMOOTHED).apply { color = Color.YELLOW; lineWidth = 1.5f; setDrawCircles(false); setDrawValues(false) }
        val dataSets = ArrayList<ILineDataSet>(); dataSets.add(differentiatedDataSet); dataSets.add(squaredDataSet); dataSets.add(smoothedDataSet)
        morphologyChart.data = LineData(dataSets); morphologyChart.invalidate()
    }
}