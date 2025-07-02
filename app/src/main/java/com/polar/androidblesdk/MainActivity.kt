package com.polar.androidblesdk

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.DrawableCompat
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarEcgData
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarSensorSetting
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private var deviceId = "B8C9DC2F"
    private var deviceConnected = false
    private lateinit var connectionStatusTextView: TextView
    private var isAutoConnecting = false

    private val api: PolarBleApi by lazy {
        PolarApiSingleton.getApi(this)
    }

    private var hrDisposable: Disposable? = null
    private var ecgDisposable: Disposable? = null
    private lateinit var connectButton: Button
    private lateinit var autoConnectButton: Button
    private lateinit var scanButton: Button
    private lateinit var broadcastButton: Button
    private lateinit var ecgButton: Button
    private lateinit var hrButton: Button
    private lateinit var hrTextView: TextView
    private lateinit var openGraphButton: Button
    private val hrValues = mutableListOf<Int>()
    private val ecgFilter = EcgBandpassFilter()
    private var isEcgStreaming = false
    private var ecgSampleRate: Int = 0
    private lateinit var openRecordPageButton: Button

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inisialisasi tombol & text view
        connectButton = findViewById(R.id.connect_button)
        connectButton.text = "Not connected to any device"
        connectButton.isEnabled = false

        connectionStatusTextView = findViewById(R.id.connection_status)
        autoConnectButton = findViewById(R.id.auto_connect_button)
        scanButton = findViewById(R.id.scan_button)
        broadcastButton = findViewById(R.id.broadcast_button)
        ecgButton = findViewById(R.id.ecg_button)
        hrButton = findViewById(R.id.hr_button)
        hrTextView = findViewById(R.id.hr_text)
        openRecordPageButton = findViewById(R.id.ecgrecord_button)



        // Callback API untuk status koneksi
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "CONNECTED: ${polarDeviceInfo.deviceId}")
                deviceId = polarDeviceInfo.deviceId
                deviceConnected = true
                val newText = getString(R.string.disconnect_from_device, deviceId)
                connectButton.text = newText
                connectButton.isEnabled = true
                connectionStatusTextView.text = "Connected to $deviceId"

                if (isAutoConnecting) {
                    Toast.makeText(this@MainActivity, "Tersambung ke sensor ${polarDeviceInfo.deviceId}", Toast.LENGTH_SHORT).show()
                    isAutoConnecting = false
                }
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "CONNECTING: ${polarDeviceInfo.deviceId}")
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "DISCONNECTED: ${polarDeviceInfo.deviceId}")
                deviceConnected = false
                connectButton.text = "Not connected to any device"
                connectButton.isEnabled = false
                connectionStatusTextView.text = "Disconnected"
            }
        })

        // Tombol Connect/Disconnect
        connectButton.setOnClickListener {
            Log.d(TAG, "TOMBOL CONNECT DITEKAN. Device ID: $deviceId")

            try {
                if (deviceConnected) {
                    Log.d(TAG, "Mencoba DISCONNECT dari $deviceId")
                    api.disconnectFromDevice(deviceId)
                } else {
                    Log.d(TAG, "Mencoba CONNECT ke $deviceId")
                    api.connectToDevice(deviceId)

                    // SET TIMER 5 DETIK UNTUK CEK KONEKSI
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!deviceConnected) { // Jika belum terkoneksi setelah 5 detik
                            Log.e(TAG, "GAGAL CONNECT: Timeout 5 detik")
                            showConnectionFailedDialog(deviceId)
                        }
                    }, 5000) // 5000ms = 5 detik
                }
            } catch (e: PolarInvalidArgument) {
                Log.e(TAG, "ID PERANGKAT INVALID: ${e.message}")
                showConnectionFailedDialog(deviceId)
            }
        }

        // Tombol Auto Connect
        // Tombol Auto Connect
        autoConnectButton.setOnClickListener {
            Log.d(TAG, "Auto-connect started...")
            isAutoConnecting = true

            // Set timer untuk 5 detik
            Handler(Looper.getMainLooper()).postDelayed({
                if (isAutoConnecting) { // Jika masih dalam proses auto connect
                    isAutoConnecting = false
                    Toast.makeText(this, "Tidak berhasil menyambungkan ke sensor apapun.", Toast.LENGTH_SHORT).show()
                }
            }, 10000) // 10 detik

            // Mulai auto connect
            api.autoConnectToDevice(-60, "180D", null)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    {
                        Log.d(TAG, "Auto-connect completed (success)")
                        // Tampilkan Toast dengan ID sensor yang terhubung
                        if (deviceConnected) {
                            Toast.makeText(this, "Tersambung ke sensor $deviceId", Toast.LENGTH_SHORT).show()
                        }
                    },
                    { error ->
                        isAutoConnecting = false
                        Log.e(TAG, "Auto-connect failed: $error")
                        Toast.makeText(this, "Tidak berhasil menyambungkan ke sensor apa pun.", Toast.LENGTH_SHORT).show()
                    }
                )
        }



        // Tombol Scan Devices
        scanButton.setOnClickListener {
            Log.d(TAG, "Scanning for devices...")

            val detectedDevices = mutableListOf<String>()

            val scanDisposable = api.searchForDevice()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { polarDeviceInfo: PolarDeviceInfo ->
                        Log.d(TAG, "Found device: ${polarDeviceInfo.deviceId} (RSSI: ${polarDeviceInfo.rssi})")
                        detectedDevices.add(polarDeviceInfo.deviceId)
                    },
                    { error ->
                        Log.e(TAG, "Scan failed: $error")
                        Toast.makeText(this, "Gagal melakukan scan: $error", Toast.LENGTH_SHORT).show()
                    }
                )

            // Stop scanning dan tampilkan hasil setelah 5 detik
            Handler(Looper.getMainLooper()).postDelayed({
                scanDisposable.dispose() // hentikan scan
                val message = if (detectedDevices.isNotEmpty()) {
                    "ID sensor yang terdeteksi: ${detectedDevices.joinToString(", ")}"
                } else {
                    "Tidak ada sensor yang terdeteksi."
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }, 5000)
        }



        // Tombol Listen Broadcast HR
        broadcastButton.setOnClickListener {
            Log.d(TAG, "Listening for HR broadcasts...")
            api.startListenForPolarHrBroadcasts(null)
                .subscribe(
                    { hrData ->
                        Log.d(TAG, "HR Broadcast: ${hrData.polarDeviceInfo.deviceId} - HR: ${hrData.hr}")
                    },
                    { error -> Log.e(TAG, "Broadcast failed: $error") }
                )
        }

        // Tombol ECG
        ecgButton.setOnClickListener {
            val isDisposed = ecgDisposable?.isDisposed ?: true
            if (isDisposed) {
                toggleButtonDown(ecgButton, R.string.stop_ecg_stream)
                // Penting: Reset state filter setiap kali memulai stream baru!
                ecgFilter.reset()
                isEcgStreaming = true

                ecgDisposable = api.requestStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.ECG) // Returns Single<PolarSensorSetting>
                    .observeOn(AndroidSchedulers.mainThread()) // Opsional, jika ingin doOnSuccess di Main Thread
                    .doOnSuccess { settings: PolarSensorSetting ->
                        // Tipe sebenarnya adalah Map
                        val settingsMap: MutableMap<PolarSensorSetting.SettingType, Set<Int>> = settings.settings
                        Log.d(TAG, "Available settings map: $settingsMap") // Log ini bisa membantu debugging

                        // 1. Dapatkan Set<Int> untuk kunci SAMPLE_RATE
                        // Gunakan operator [] yang mengembalikan nullable Set<Int>?
                        val sampleRateOptions: Set<Int>? = settingsMap[PolarSensorSetting.SettingType.SAMPLE_RATE]

                        if (sampleRateOptions != null && sampleRateOptions.isNotEmpty()) {
                            // 2. Cari nilai maksimum dalam Set (jika tidak null/kosong)
                            // maxOrNull() mengembalikan Int? (nullable) karena set bisa kosong
                            val maxSampleRate: Int? = sampleRateOptions.maxOrNull()

                            if (maxSampleRate != null) {
                                // Jika maxSampleRate berhasil didapatkan
                                this.ecgSampleRate = maxSampleRate
                                Log.i(TAG, "ECG Sample Rate Dikonfigurasi (Max): ${this.ecgSampleRate} Hz")
                                // Beri peringatan jika berbeda dari 130 Hz (karena filter didesain untuk itu)
                                if (this.ecgSampleRate != 130) {
                                    Log.w(TAG, "PERINGATAN: Koefisien filter dihitung untuk 130 Hz, tapi Fs yang dipilih/maksimum adalah ${this.ecgSampleRate} Hz!")
                                }
                            } else {
                                // Ini seharusnya tidak terjadi jika sampleRateOptions tidak kosong, tapi sebagai fallback
                                Log.w(TAG, "Set sample rate ada tapi tidak bisa mendapatkan nilai maksimum. Menggunakan default 130 Hz.")
                                this.ecgSampleRate = 130 // Fallback jika maxOrNull gagal (aneh)
                            }
                        } else {
                            // Handle kasus jika kunci SAMPLE_RATE tidak ada di map, atau Set-nya kosong
                            Log.w(TAG, "Kunci SAMPLE_RATE tidak ditemukan di settings map atau set kosong. Menggunakan default 130 Hz.")
                            this.ecgSampleRate = 130 // Fallback jika tidak ada opsi sample rate
                        }
                    }
                    // Transisi dari Single<Settings> ke Flowable<EcgData>
                    .flatMapPublisher { settings ->
                        if (ecgSampleRate <= 0) { // Cek sample rate valid
                            Log.e(TAG,"Sample rate tidak valid ($ecgSampleRate), membatalkan stream.")
                            Flowable.error(IllegalStateException("ECG Sample Rate tidak valid: $ecgSampleRate"))
                        } else {
                            api.startEcgStreaming(deviceId, settings) // Returns Flowable<PolarEcgData>
                        }
                    }
                    .observeOn(AndroidSchedulers.mainThread()) // Untuk callback subscribe di Main Thread
                    .subscribe(
                        { polarEcgData: PolarEcgData ->
                            // Filter data di sini...
                            // val filteredVoltages = ecgFilter.filterPolarSamples(polarEcgData.samples)
                            // Log.d(TAG, "Filtered ${filteredVoltages.size} ECG samples.")
                            // TODO: Gunakan filteredVoltages untuk grafik/analisis
                        },
                        { error: Throwable ->
                            toggleButtonUp(ecgButton, R.string.start_ecg_stream)
                            Log.e(TAG, "ECG stream failed. Reason: $error")
                            isEcgStreaming = false
                        },
                        {
                            Log.d(TAG, "ECG stream complete")
                            toggleButtonUp(ecgButton, R.string.start_ecg_stream)
                            isEcgStreaming = false
                        }
                    )
            } else {
                toggleButtonUp(ecgButton, R.string.start_ecg_stream)
                ecgDisposable?.dispose()
                isEcgStreaming = false // Update flag
                Log.d(TAG,"ECG streaming stopped by user.")
            }
        }

        // Tombol HR Stream
        hrButton.setOnClickListener {
            if (hrDisposable == null) {
                hrDisposable = api.startHrStreaming(deviceId)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { hrData: PolarHrData ->
                            for (sample in hrData.samples) {
                                val hrText = "HR: ${sample.hr} BPM, RR: ${sample.rrsMs}"
                                hrTextView.text = hrText
                                hrValues.add(sample.hr)  // Simpan data HR untuk grafik
                            }
                        },
                        { error -> Log.e(TAG, "HR stream failed: $error") }
                    )
                hrButton.text = "Stop HR Stream"
            } else {
                hrDisposable?.dispose()
                hrDisposable = null
                hrButton.text = "Start HR Stream"
            }
        }

        ecgButton.setOnClickListener {
            val intent = Intent(this, EcgGraphActivity::class.java)
            intent.putExtra("DEVICE_ID", deviceId)
            startActivity(intent)
        }

        openRecordPageButton.setOnClickListener {
            if (deviceConnected && deviceId.isNotEmpty()) { // Pastikan sudah terhubung dan deviceId ada
                val intent = Intent(this, EcgRecordingActivity::class.java)
                intent.putExtra(EcgRecordingActivity.EXTRA_DEVICE_ID, deviceId)
                startActivity(intent)
            } else {
                Toast.makeText(this, "Sensor belum terhubung. Hubungkan sensor terlebih dahulu.", Toast.LENGTH_LONG).show()
                // Atau, jika deviceId kosong tapi deviceConnected true (jarang terjadi),
                // Anda mungkin perlu logika untuk memilih perangkat lagi atau auto-connect.
            }
        }
    }

    private fun showConnectionFailedDialog(deviceId: String) {
        val alertDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Connection Failed")
            .setMessage("Failed to connect to $deviceId")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .create()

        alertDialog.show()
    }

    private fun toggleButtonDown(button: Button, text: String? = null) {
        toggleButton(button, true, text)
    }

    private fun toggleButtonDown(button: Button, @StringRes resourceId: Int) {
        toggleButton(button, true, getString(resourceId))
    }

    private fun toggleButtonUp(button: Button, text: String? = null) {
        toggleButton(button, false, text)
    }

    private fun toggleButtonUp(button: Button, @StringRes resourceId: Int) {
        toggleButton(button, false, getString(resourceId))
    }

    private fun toggleButton(button: Button, isDown: Boolean, text: String? = null) {
        if (text != null) button.text = text

        var buttonDrawable = button.background
        buttonDrawable = DrawableCompat.wrap(buttonDrawable!!)
        if (isDown) {
            DrawableCompat.setTint(buttonDrawable, resources.getColor(R.color.primaryDarkColor))
        } else {
            DrawableCompat.setTint(buttonDrawable, resources.getColor(R.color.primaryColor))
        }
        button.background = buttonDrawable
    }

    private fun requestStreamSettings(identifier: String, feature: PolarBleApi.PolarDeviceDataType): Flowable<PolarSensorSetting> {
        return api.requestStreamSettings(identifier, feature)
            .observeOn(AndroidSchedulers.mainThread())
            .toFlowable()
    }

    public override fun onPause() {
        super.onPause()
    }

    public override fun onResume() {
        super.onResume()
        api.foregroundEntered()
    }

    override fun onDestroy() {
        super.onDestroy()
        hrDisposable?.dispose()
        ecgDisposable?.dispose()
    }
}
