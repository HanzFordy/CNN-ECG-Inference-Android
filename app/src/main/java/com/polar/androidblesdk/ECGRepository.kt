package com.polar.androidblesdk

import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.model.PolarEcgData
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow

object ECGRepository {
    fun startEcgStream(api: PolarBleApi, deviceId: String): Flow<PolarEcgData> {
        // Minta setting dan mulai stream
        return api.requestStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.ECG)
            .toFlowable() // Ubah Single ke Flowable
            .flatMap { settings ->
                api.startEcgStreaming(deviceId, settings.maxSettings())
            }
            .subscribeOn(Schedulers.io()) // Pastikan stream berjalan di background thread
            .asFlow() // Mengubah RxJava Flowable menjadi Kotlin Flow
    }
}