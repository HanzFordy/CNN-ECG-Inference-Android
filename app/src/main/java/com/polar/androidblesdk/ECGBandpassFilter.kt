package com.polar.androidblesdk

import android.util.Log
import com.polar.sdk.api.model.EcgSample
import com.polar.sdk.api.model.PolarEcgDataSample

class ECGBandpassFilter {
    private val sosCoefficients = arrayOf(
        doubleArrayOf(9.98439113e-01, -1.99687823e+00, 9.98439113e-01, -1.99687750e+00, 9.96878952e-01),
        doubleArrayOf(1.00000000e+00, -1.99999931e+00, 1.00000000e+00, -1.99999861e+00, 9.99999308e-01)
    )
    private val state = Array(sosCoefficients.size) { DoubleArray(4) { 0.0 } }

    fun filter(input: Double): Double {
        var currentInput = input
        for (i in sosCoefficients.indices) {
            val b0 = sosCoefficients[i][0]
            val b1 = sosCoefficients[i][1]
            val b2 = sosCoefficients[i][2]
            val a1 = sosCoefficients[i][3]
            val a2 = sosCoefficients[i][4]
            val x1 = state[i][0]
            val x2 = state[i][1]
            val y1 = state[i][2]
            val y2 = state[i][3]
            val currentOutput = (b0 * currentInput) + (b1 * x1) + (b2 * x2) - (a1 * y1) - (a2 * y2)
            state[i][1] = x1
            state[i][0] = currentInput
            state[i][3] = y1
            state[i][2] = currentOutput
            currentInput = currentOutput
        }
        return currentInput
    }

    fun filterChunk(inputData: List<Double>): List<Double> {
        return inputData.map { filter(it) }
    }

    fun reset() {
        for (sectionState in state) {
            sectionState.fill(0.0)
        }
        Log.d("EcgBandpassFilter", "Filter state has been reset.")
    }
}