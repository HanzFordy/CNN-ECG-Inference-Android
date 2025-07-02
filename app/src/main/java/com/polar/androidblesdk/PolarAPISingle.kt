package com.polar.androidblesdk

import android.content.Context
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiDefaultImpl

object PolarApiSingleton {
    private var apiInstance: PolarBleApi? = null

    fun getApi(context: Context): PolarBleApi {
        if (apiInstance == null) {
            apiInstance = PolarBleApiDefaultImpl.defaultImplementation(
                context.applicationContext,
                setOf(
                    PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
                    PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_SDK_MODE
                )
            )
        }
        return apiInstance!!
    }

    fun shutDown() {
        apiInstance?.shutDown()
        apiInstance = null
    }
}
