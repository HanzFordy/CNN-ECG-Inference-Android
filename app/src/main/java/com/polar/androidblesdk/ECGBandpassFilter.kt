package com.polar.androidblesdk

import android.util.Log
import com.polar.sdk.api.model.EcgSample
import com.polar.sdk.api.model.PolarEcgData
import com.polar.sdk.api.model.PolarEcgDataSample

import kotlin.math.abs

class ECGBandpassFilter {

    // Didesain untuk: Butterworth Order 4, HIGH-PASS, Cutoff = 0.05 Hz, Fs = 130 Hz
    private val sosCoefficients = arrayOf(
        // Section 1 coefficients (Fs=130Hz, HP 0.05Hz, Butterworth Order 4)
        doubleArrayOf(9.98439113e-01, -1.99687823e+00, 9.98439113e-01, -1.99687750e+00, 9.96878952e-01),
        // Section 2 coefficients (Fs=130Hz, HP 0.05Hz, Butterworth Order 4)
        doubleArrayOf(1.00000000e+00, -1.99999931e+00, 1.00000000e+00, -1.99999861e+00, 9.99999308e-01)
    )

    // State variables for each section [sectionIndex][stateVariableIndex]
    // stateVariableIndex: 0 = x[n-1], 1 = x[n-2], 2 = y[n-1], 3 = y[n-2]
    // Diinisialisasi dengan 0.0
    private val state = Array(sosCoefficients.size) { DoubleArray(4) { 0.0 } }

    /**
     * Memfilter satu sampel data input.
     * @param input Sampel input (misalnya, nilai voltage ECG dalam microvolt, dikonversi ke Double).
     * @return Sampel output yang sudah difilter.
     */
    fun filter(input: Double): Double {
        var currentInput = input
        for (i in sosCoefficients.indices) {
            val b0 = sosCoefficients[i][0]
            val b1 = sosCoefficients[i][1]
            val b2 = sosCoefficients[i][2]
            // a0 = 1.0 (implisit)
            val a1 = sosCoefficients[i][3]
            val a2 = sosCoefficients[i][4]

            // Ambil state sebelumnya untuk section ini
            val x1 = state[i][0] // x[n-1]
            val x2 = state[i][1] // x[n-2]
            val y1 = state[i][2] // y[n-1]
            val y2 = state[i][3] // y[n-2]

            // Hitung output menggunakan persamaan beda (Direct Form I)
            // y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]
            val currentOutput = (b0 * currentInput) + (b1 * x1) + (b2 * x2) - (a1 * y1) - (a2 * y2)

            // Update state untuk iterasi berikutnya pada section ini
            state[i][1] = x1       // Update x[n-2] = old x[n-1]
            state[i][0] = currentInput // Update x[n-1] = current x[n]
            state[i][3] = y1       // Update y[n-2] = old y[n-1]
            state[i][2] = currentOutput // Update y[n-1] = current y[n]

            // Output dari section ini menjadi input untuk section berikutnya
            currentInput = currentOutput
        }
        // Nilai currentInput terakhir adalah output final dari filter cascade
        return currentInput
    }

    /**
     * Memfilter sekumpulan data (chunk) dalam bentuk List<Double>.
     * @param inputData List data input.
     * @return List data output yang sudah difilter.
     */
    fun filterChunk(inputData: List<Double>): List<Double> {
        // Memetakan setiap input ke output yang difilter,
        // state filter akan diperbarui secara otomatis di setiap panggilan `filter()`
        return inputData.map { filter(it) }
    }

    /**
     * Convenience function untuk memfilter data langsung dari Polar SDK.
     * @param samples List dari PolarEcgData.Sample.
     * @return List Double yang berisi nilai voltage ECG yang sudah difilter.
     */
    fun filterPolarSamples(samples: List<PolarEcgDataSample>): List<Double> {
        val inputDoubles = samples.mapNotNull { sample ->
            if (sample is EcgSample) {
                sample.voltage.toDouble()
            } else {
                null
            }
        }
        return filterChunk(inputDoubles)
    }

    /**
     * Mereset state internal filter (nilai x[n-1], x[n-2], y[n-1], y[n-2]).
     * Penting dipanggil jika ada jeda besar dalam data atau saat memulai stream baru
     * untuk menghindari artefak dari state sebelumnya.
     */
    fun reset() {
        for (sectionState in state) {
            sectionState.fill(0.0)
        }
        Log.d("EcgBandpassFilter", "Filter state has been reset.")
    }
}