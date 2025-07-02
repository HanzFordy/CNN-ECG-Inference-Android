package com.polar.androidblesdk

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.model.PolarEcgData
import com.polar.sdk.api.model.PolarSensorSetting
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EcgRecordingActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var startRecordButton: Button
    private lateinit var stopRecordButton: Button
    private lateinit var fileSavedTextView: TextView

    private lateinit var api: PolarBleApi
    private var deviceId: String? = null
    private var ecgDisposable: Disposable? = null

    private val ecgRecordingBuffer = mutableListOf<Int>()
    private var isRecording = false
    private val RECORDING_DURATION_SECONDS = 30
    private var currentFs: Double = 130.0 // Default, akan diupdate
    private var countDownTimer: CountDownTimer? = null

    companion object {
        private const val TAG = "EcgRecordingActivity"
        const val EXTRA_DEVICE_ID = "device_id_for_recording"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ecg_recording)

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        if (deviceId == null) {
            Toast.makeText(this, "Device ID tidak ditemukan!", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        statusTextView = findViewById(R.id.recording_status_textview)
        progressBar = findViewById(R.id.recording_progressbar)
        startRecordButton = findViewById(R.id.start_record_button)
        stopRecordButton = findViewById(R.id.stop_record_button)
        fileSavedTextView = findViewById(R.id.file_saved_textview)

        api = PolarApiSingleton.getApi(this)

        startRecordButton.setOnClickListener {
            if (!isRecording) {
                startEcgRecording()
            }
        }

        stopRecordButton.setOnClickListener {
            if (isRecording) {
                stopEcgRecording(manualStop = true)
            }
        }
    }

    @SuppressLint("CheckResult")
    private fun startEcgRecording() {
        ecgRecordingBuffer.clear()
        fileSavedTextView.text = ""
        isRecording = true
        startRecordButton.isEnabled = false
        startRecordButton.text = "Merekam..."
        stopRecordButton.visibility = View.VISIBLE
        progressBar.progress = 0
        progressBar.max = RECORDING_DURATION_SECONDS
        progressBar.visibility = View.VISIBLE
        statusTextView.text = "Mempersiapkan stream..."

        api.requestStreamSettings(deviceId!!, PolarBleApi.PolarDeviceDataType.ECG)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ settings ->
                val sensorSetting = settings.maxSettings()
                currentFs = sensorSetting.settings[PolarSensorSetting.SettingType.SAMPLE_RATE]
                    ?.firstOrNull()?.toDouble() ?: 130.0
                Log.d(TAG, "Fs: $currentFs Hz. Memulai Perekaman ECG.")
                statusTextView.text = "Merekam... (0/${RECORDING_DURATION_SECONDS}s)"

                // Setup Countdown Timer
                countDownTimer = object : CountDownTimer((RECORDING_DURATION_SECONDS * 1000).toLong(), 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        val secondsElapsed = RECORDING_DURATION_SECONDS - (millisUntilFinished / 1000)
                        progressBar.progress = secondsElapsed.toInt()
                        statusTextView.text = "Merekam... (${secondsElapsed}/${RECORDING_DURATION_SECONDS}s)"
                    }
                    override fun onFinish() {
                        // Perekaman selesai secara otomatis
                        if(isRecording) { // Pastikan belum dihentikan manual
                            stopEcgRecording(manualStop = false)
                        }
                    }
                }.start()

                ecgDisposable = api.startEcgStreaming(deviceId!!, sensorSetting)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ ecgData: PolarEcgData ->
                        if (!isRecording) {
                            if (ecgDisposable?.isDisposed == false) ecgDisposable?.dispose()
                            return@subscribe
                        }
                        ecgData.samples.forEach { ecgRecordingBuffer.add(it.voltage) }
                    },
                        { error ->
                            Log.e(TAG, "ECG stream error saat merekam: ${error.message}", error)
                            stopEcgRecording(manualStop = true, errorMessage = "Error stream: ${error.message}")
                        })
            }, { error ->
                Log.e(TAG, "Gagal mendapatkan ECG settings untuk rekam: ${error.message}", error)
                statusTextView.text = "Gagal memulai rekam: ${error.message}"
                resetRecordingState()
            })
    }

    private fun stopEcgRecording(manualStop: Boolean, errorMessage: String? = null) {
        if (!isRecording && !manualStop) return // Jika sudah dihentikan otomatis dan ini adalah callback timer.onFinish kedua kalinya

        isRecording = false
        countDownTimer?.cancel()
        ecgDisposable?.dispose()

        if (errorMessage != null) {
            statusTextView.text = errorMessage
        } else if (manualStop) {
            statusTextView.text = "Perekaman dihentikan manual."
        } else {
            statusTextView.text = "Perekaman selesai otomatis."
        }
        progressBar.visibility = View.GONE

        if (ecgRecordingBuffer.isNotEmpty()) {
            saveEcgDataToFile()
        } else {
            fileSavedTextView.text = "Tidak ada data ECG yang direkam."
            Log.w(TAG, "Tidak ada data di buffer untuk disimpan.")
        }
        resetRecordingStateAfterSave()
    }

    private fun saveEcgDataToFile() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ecg_record_${deviceId}_fs${currentFs.toInt()}_${timestamp}.txt"
        val file = File(getExternalFilesDir(null), fileName) // Simpan ke direktori eksternal spesifik aplikasi

        try {
            FileOutputStream(file).use { fos ->
                OutputStreamWriter(fos).use { writer ->
                    writer.write("# Polar H10 ECG Recording\n")
                    writer.write("# Device ID: $deviceId\n")
                    writer.write("# Sampling Rate: $currentFs Hz\n")
                    writer.write("# Timestamp: $timestamp\n")
                    writer.write("# Samples: ${ecgRecordingBuffer.size}\n")
                    writer.write("# Data format: microvolts (integer), one sample per line\n")
                    writer.write("# -----------------------------------------------------\n")
                    ecgRecordingBuffer.forEach { voltage ->
                        writer.write("$voltage\n")
                    }
                }
            }
            val successMessage = "ECG Disimpan: ${file.name}\nLokasi: ${file.absolutePath}"
            Log.i(TAG, successMessage)
            fileSavedTextView.text = successMessage
            Toast.makeText(this, "ECG disimpan: ${file.name}", Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            Log.e(TAG, "Error menyimpan file ECG: ${e.message}", e)
            fileSavedTextView.text = "Gagal menyimpan file: ${e.message}"
            Toast.makeText(this, "Gagal menyimpan ECG", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetRecordingState() {
        startRecordButton.isEnabled = true
        startRecordButton.text = "Mulai Rekam ECG (30 Detik)"
        stopRecordButton.visibility = View.GONE
        progressBar.visibility = View.GONE
        progressBar.progress = 0
    }
    private fun resetRecordingStateAfterSave() {
        startRecordButton.isEnabled = true
        startRecordButton.text = "Mulai Rekam ECG (Lagi)"
        stopRecordButton.visibility = View.GONE
        progressBar.visibility = View.GONE
        progressBar.progress = 0
    }


    override fun onDestroy() {
        isRecording = false // Pastikan flag direset
        countDownTimer?.cancel()
        ecgDisposable?.dispose()
        Log.d(TAG, "EcgRecordingActivity destroyed, ECG stream (if any) disposed.")
        super.onDestroy()
    }
}