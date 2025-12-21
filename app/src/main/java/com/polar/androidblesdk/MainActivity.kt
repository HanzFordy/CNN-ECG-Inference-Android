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
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarEcgData
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.polar.sdk.api.model.PolarSensorSetting
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
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
        PolarAPISingle.getApi(this)
    }

    private var hrDisposable: Disposable? = null
    private var ecgDisposable: Disposable? = null
    private lateinit var connectButton: Button
    private lateinit var autoConnectButton: Button
    private lateinit var ecgButton: Button
    private val ecgFilter = ECGBandpassFilter()
    private var isEcgStreaming = false
    private var ecgSampleRate: Int = 0

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inisialisasi tombol & text view
        connectButton = findViewById(R.id.connect_button)
        connectButton.text = "Tidak tersambung ke sensor apapun"
        connectButton.isEnabled = false

        connectionStatusTextView = findViewById(R.id.connection_status)
        autoConnectButton = findViewById(R.id.auto_connect_button)
        ecgButton = findViewById(R.id.ecg_button)

        // Callback API untuk status koneksi
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "CONNECTED: ${polarDeviceInfo.deviceId}")
                deviceId = polarDeviceInfo.deviceId
                deviceConnected = true
                val newText = getString(R.string.disconnect_from_device, deviceId)
                connectButton.text = newText
                connectButton.isEnabled = true
                connectionStatusTextView.text = "Tersambung ke sensor $deviceId"

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
                connectButton.text = "Tidak tersambung ke sensor apapun"
                connectButton.isEnabled = false
                connectionStatusTextView.text = "Tidak tersambung"
            }

            override fun disInformationReceived(identifier: String, disInfo: DisInfo) {
                Log.d(TAG, "DIS INFO RECEIVED: $identifier $disInfo")
            }

            override fun htsNotificationReceived(
                identifier: String,
                data: PolarHealthThermometerData
            ) {
                // Berdasarkan dokumentasi yang Anda temukan, properti yang benar adalah 'celsius'.
                // Kotlin akan secara otomatis memanggil metode getCelsius() di belakang layar.
                Log.d(TAG, "Health Thermometer from $identifier: Temp=${data.celsius} C")
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

        ecgButton.setOnClickListener {
            val intent = Intent(this, EcgGraphActivity::class.java)
            intent.putExtra("DEVICE_ID", deviceId)
            startActivity(intent)
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

    private fun toggleButtonDown(button: Button, @StringRes resourceId: Int) {
        toggleButton(button, true, getString(resourceId))
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
