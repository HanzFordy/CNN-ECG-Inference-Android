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
import androidx.appcompat.app.AppCompatActivity
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHealthThermometerData
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private var deviceId = ""
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

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectButton = findViewById(R.id.connect_button)
        connectButton.text = "Tidak tersambung ke sensor apapun"
        connectButton.isEnabled = false

        connectionStatusTextView = findViewById(R.id.connection_status)
        autoConnectButton = findViewById(R.id.auto_connect_button)
        ecgButton = findViewById(R.id.ecg_button)

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
                Log.d(TAG, "Health Thermometer from $identifier: Temp=${data.celsius} C")
            }
        })

        connectButton.setOnClickListener {
            Log.d(TAG, "TOMBOL CONNECT DITEKAN. Device ID: $deviceId")

            try {
                if (deviceConnected) {
                    Log.d(TAG, "Mencoba DISCONNECT dari $deviceId")
                    api.disconnectFromDevice(deviceId)
                } else {
                    Log.d(TAG, "Mencoba CONNECT ke $deviceId")
                    api.connectToDevice(deviceId)

                    // Kasih batas 5 detik buat connecting
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!deviceConnected) {
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

        autoConnectButton.setOnClickListener {
            Log.d(TAG, "Auto-connect started...")
            isAutoConnecting = true

            // Kasih batas 5 detik
            Handler(Looper.getMainLooper()).postDelayed({
                if (isAutoConnecting) {
                    isAutoConnecting = false
                    Toast.makeText(this, "Tidak berhasil menyambungkan ke sensor apapun.", Toast.LENGTH_SHORT).show()
                }
            }, 10000) // 10 detik

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
