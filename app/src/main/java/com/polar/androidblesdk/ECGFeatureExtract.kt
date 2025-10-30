package com.polar.androidblesdk // Sesuaikan dengan package Anda

import android.util.Log
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow // Tambahkan import ini untuk .pow()
import kotlin.math.roundToInt
import kotlin.math.sqrt

object ECGFeatureExtractor {

    private const val TAG = "EcgFeatureExtractor"

    // --- FUNGSI UNTUK FILTER MEDIAN DAN REMOVE BASELINE ---
    fun medianFilter(signal: List<Double>, windowSize: Int): List<Double> {
        if (signal.isEmpty() || windowSize <= 0) return signal.toList()
        if (windowSize == 1) return signal.toList()
        val k = if (windowSize % 2 == 0) windowSize + 1 else windowSize
        val halfWindow = k / 2
        val n = signal.size
        val output = DoubleArray(n)
        val windowValues = DoubleArray(k)
        for (i in signal.indices) {
            for (j in 0 until k) {
                var index = i + j - halfWindow
                if (index < 0) index = -index
                if (index >= n) index = (n - 1) - (index - (n - 1))
                index = index.coerceIn(0, n - 1)
                windowValues[j] = signal[index]
            }
            windowValues.sort()
            output[i] = windowValues[halfWindow]
        }
        return output.toList()
    }

    fun removeBaselineMedian(signal: List<Double>, fs: Double): List<Double> {
        if (signal.isEmpty() || fs <= 0) return signal.toList()
        var window1Size = (0.2 * fs).roundToInt() - 1
        if (window1Size <= 0) window1Size = 1
        if (window1Size % 2 == 0 && window1Size > 1) window1Size--
        else if (window1Size % 2 == 0 && window1Size == 0) window1Size = 1
        var window2Size = (0.6 * fs).roundToInt() - 1
        if (window2Size <= 0) window2Size = 1
        if (window2Size % 2 == 0 && window2Size > 1) window2Size--
        else if (window2Size % 2 == 0 && window2Size == 0) window2Size = 1
        Log.d(TAG, "RemoveBaseline: fs=$fs, win1=$window1Size, win2=$window2Size")
        val intermediateSignal = medianFilter(signal, window1Size)
        val baseline = medianFilter(intermediateSignal, window2Size)
        if (signal.size != baseline.size) {
            Log.e(TAG, "RemoveBaseline: Error - signal (${signal.size}) and baseline (${baseline.size}) sizes differ.")
            return signal.toList()
        }
        val filteredSignal = DoubleArray(signal.size)
        for (i in signal.indices) {
            filteredSignal[i] = signal[i] - baseline[i]
        }
        return filteredSignal.toList()
    }

    // --- FUNGSI PRA-PEMROSESAN PAN-TOMPKINS ---
    fun differentiate(signal: List<Double>): List<Double> {
        if (signal.size < 3) {
            if (signal.size == 2) return listOf(signal[1] - signal[0], signal[1] - signal[0])
            if (signal.size == 1) return listOf(0.0)
            return emptyList()
        }
        val derivative = DoubleArray(signal.size)
        derivative[0] = signal[1] - signal[0]
        for (i in 1 until signal.size - 1) {
            derivative[i] = (signal[i + 1] - signal[i - 1]) / 2.0
        }
        derivative[signal.size - 1] = signal.last() - signal[signal.size - 2]
        return derivative.toList()
    }

    fun square(signal: List<Double>): List<Double> {
        return signal.map { it * it }
    }

    fun movingWindowAverage(signal: List<Double>, windowSize: Int): List<Double> {
        if (signal.isEmpty() || windowSize <= 0) return emptyList()
        val N = windowSize.coerceAtLeast(1)
        val result = DoubleArray(signal.size)
        var sum = 0.0
        for (i in signal.indices) {
            sum += signal[i]
            if (i >= N) {
                sum -= signal[i - N]
                result[i] = sum / N
            } else {
                result[i] = sum / (i + 1)
            }
        }
        return result.toList()
    }

    // --- FUNGSI DETEKSI R-PEAK BERDASARKAN ARTIKEL (YANG AKAN KITA PAKAI) ---
    fun findRPeaks(originalSignal: List<Double>, fs: Double): List<Int> {
        if (originalSignal.isEmpty() || fs <= 0) return emptyList()

        // 1. Langkah-langkah Pra-pemrosesan Pan-Tompkins
        val differentiatedSignal = differentiate(originalSignal)
        val squaredSignal = square(differentiatedSignal)
        val mwiWindowDurationMs = 75.0
        val mwiWindowSize = (mwiWindowDurationMs / 1000.0 * fs).roundToInt().coerceAtLeast(1)
        val smoothedSignal = movingWindowAverage(squaredSignal, mwiWindowSize)
        if (smoothedSignal.isEmpty()) return emptyList()

        val finalRPeakIndices = mutableListOf<Int>()
        val refractoryPeriodSamples = (250.0 / 1000.0 * fs).roundToInt() // 250ms refractory
        val backsearchWindowMs = 150.0
        val backsearchWindowHalfSamples = (backsearchWindowMs / 2000.0 * fs).roundToInt()

        // --- Perhitungan Threshold yang Lebih Robust ---
        // Daripada mean+std yang sensitif outlier, kita gunakan kombinasi
        // persentil dan nilai maksimum dari smoothedSignal.
        val sortedSmoothed = smoothedSignal.filter { it > 0 }.sorted() // Filter nilai nol/negatif dan urutkan
        if (sortedSmoothed.isEmpty()) {
            Log.w(TAG, "Tidak ada puncak positif di smoothedSignal.")
            return emptyList()
        }
        // Ambil nilai di persentil ke-75 sebagai estimasi level sinyal umum
        val p75 = sortedSmoothed[(sortedSmoothed.size * 0.75).toInt()]
        // Threshold adalah fraksi dari estimasi ini. Faktor 0.5 adalah parameter tuning.
        // Ini kurang sensitif terhadap satu puncak yang sangat besar di awal.
        val threshold = p75 * 0.5

        Log.i(TAG, "Peak Detection (Percentile): p75=${"%.1f".format(p75)}, Threshold=${"%.1f".format(threshold)}")

        var lastRPeakIndex = -refractoryPeriodSamples

        // Deteksi Puncak
        for (i in 1 until smoothedSignal.size - 1) {
            if (smoothedSignal[i] > smoothedSignal[i - 1] && smoothedSignal[i] >= smoothedSignal[i + 1]) { // Puncak lokal
                val currentPeakValue = smoothedSignal[i]

                if (currentPeakValue > threshold && (i - lastRPeakIndex) > refractoryPeriodSamples) {
                    // Backsearch
                    val searchStart = max(0, i - backsearchWindowHalfSamples)
                    val searchEnd = min(originalSignal.size - 1, i + backsearchWindowHalfSamples)
                    var actualPeakIndex = i; var maxVal = Double.NEGATIVE_INFINITY
                    for (j in searchStart..searchEnd) {
                        if (originalSignal[j] > maxVal) {
                            maxVal = originalSignal[j]; actualPeakIndex = j
                        }
                    }
                    finalRPeakIndices.add(actualPeakIndex)
                    lastRPeakIndex = i // Gunakan 'i' dari smoothedSignal untuk refractory
                }
            }
        }
        Log.i(TAG, "Total R-Peaks (Percentile TH) detected: ${finalRPeakIndices.size}")
        return finalRPeakIndices.distinct().sorted()
    }

    // --- FUNGSI UNTUK MENGAMBIL SEGMEN ECG ---
    fun getEcgSegmentAroundRPeak(
        fullSignal: List<Double>,
        rPeakIndex: Int,
        segmentLength: Int, // Akan kita isi dengan 188 atau 180 dari pemanggil
        samplesBeforeR: Int // Akan kita sesuaikan dari pemanggil
    ): List<Double>? {
        if (rPeakIndex < 0 || rPeakIndex >= fullSignal.size) {
            Log.w(TAG, "getEcgSegment: R-peak index ($rPeakIndex) di luar batas sinyal (${fullSignal.size}).")
            return null
        }

        // samplesAfterRInclusive dihitung agar totalnya segmentLength, dengan R-peak di posisi yang benar
        // Jika R-peak adalah bagian dari 'samplesBeforeR' (misal, R-peak di akhir blok 'before'):
        // samplesAfterR = segmentLength - samplesBeforeR - 1 (jika R-peak adalah 1 sampel terpisah)
        // Jika R-peak adalah sampel pertama dari blok 'after' (samplesBeforeR tidak termasuk R-peak):
        val samplesAfterRInclusive = segmentLength - samplesBeforeR

        val startIndex = rPeakIndex - samplesBeforeR
        // endIndex dihitung agar (endIndex - startIndex + 1) == segmentLength
        val endIndex = startIndex + segmentLength - 1


        if (startIndex < 0 || endIndex >= fullSignal.size) {
            Log.w(TAG, "getEcgSegment: Segmen untuk R-peak @$rPeakIndex (len:$segmentLength, before:$samplesBeforeR) akan keluar batas. Calc_Start: $startIndex, Calc_End: $endIndex, SignalSize: ${fullSignal.size}")
            return null
        }
        return fullSignal.subList(startIndex, endIndex + 1) // endIndex inklusif untuk subList
    }

    // --- FUNGSI UNTUK EKSTRAKSI FITUR MORFOLOGI ---

    fun extractKotlinMorphologyFeatures(
        beatSegment: List<Double>, // Ini SEHARUSNYA 180 sampel
        fs: Double
    ): DoubleArray {
        val numFeatures = 37
        val features = DoubleArray(numFeatures) { 0.0 }

        val EXPECTED_MORPH_SEGMENT_LENGTH = 180 // Sesuai winL+winR Python
        if (beatSegment.size != EXPECTED_MORPH_SEGMENT_LENGTH) {
            Log.w(TAG, "extractKotlinMorphologyFeatures: Ukuran segmen beat tidak sesuai (${beatSegment.size}), " +
                    "diharapkan $EXPECTED_MORPH_SEGMENT_LENGTH. Mengembalikan fitur nol.")
            return features
        }

        try {
            // --- 1. Statistical Features (8 fitur) ---
            val meanVal = beatSegment.average()
            features[0] = meanVal

            val n = beatSegment.size.toDouble()
            val varianceVal = beatSegment.sumOf { val diff = it - meanVal; diff * diff } / n // Varians populasi (seperti np.var)
            features[2] = varianceVal

            val stdVal = sqrt(varianceVal)
            features[1] = stdVal

            // Skewness (mendekati scipy.stats.skew(beat, bias=False) lalu dikoreksi jika perlu)
            if (stdVal > 1e-9 && n > 2) {
                val m3 = beatSegment.sumOf { ((it - meanVal) / stdVal).pow(3.0) }
                // Unbiased skewness estimator (G1)
                features[3] = (sqrt(n * (n - 1)) / (n - 2)) * (m3 / n)
            } else {
                features[3] = 0.0
            }

            // Kurtosis (mendekati scipy.stats.kurtosis(beat, fisher=True, bias=False) lalu dikoreksi)
            // fisher=True berarti dikurangi 3.
            if (stdVal > 1e-9 && n > 0) {
                val m4normalizedavg = beatSegment.sumOf { ((it - meanVal) / stdVal).pow(4.0) } / n
                features[4] = m4normalizedavg - 3.0 // Excess kurtosis (Fisher) tanpa koreksi bias sampel
            } else {
                features[4] = 0.0
            }

            features[5] = beatSegment.maxOrNull() ?: 0.0 // max_val
            features[6] = beatSegment.minOrNull() ?: 0.0 // min_val
            features[7] = features[5] - features[6]     // peak_to_peak

            // --- 2. Peak Detection Features ---
            val peakHeightThresholdForNumPeaks = meanVal + 0.1 * stdVal
            val minPeakDistanceForNumPeaks = 10
            val localPeaksIndices = mutableListOf<Int>()
            for (k in 1 until beatSegment.size - 1) {
                if (beatSegment[k] > peakHeightThresholdForNumPeaks &&
                    beatSegment[k] > beatSegment[k - 1] && beatSegment[k] >= beatSegment[k + 1]) {
                    if (localPeaksIndices.isEmpty() || (k - (localPeaksIndices.lastOrNull() ?: -minPeakDistanceForNumPeaks)) >= minPeakDistanceForNumPeaks) {
                        localPeaksIndices.add(k)
                    }
                }
            }
            features[8] = localPeaksIndices.size.toDouble() // num_peaks

            var rPeakIdxInSegment = 0
            var rPeakAmplitudeInSegment = beatSegment.firstOrNull() ?: Double.NEGATIVE_INFINITY
            beatSegment.forEachIndexed { index, value ->
                if (value > rPeakAmplitudeInSegment) {
                    rPeakAmplitudeInSegment = value
                    rPeakIdxInSegment = index
                }
            }
            features[9] = rPeakAmplitudeInSegment // r_peak_amplitude
            features[10] = if (beatSegment.isNotEmpty()) rPeakIdxInSegment.toDouble() / beatSegment.size.toDouble()
            else 0.0 // r_peak_position (normalized)

            val qrsWindowHalfContext = 20
            val qrsStartIdx = max(0, rPeakIdxInSegment - qrsWindowHalfContext)
            // Python min(len(beat), r_peak_idx + 20) untuk slicing eksklusif.
            // Untuk qrs_duration sebagai jumlah sampel, kita butuh qrs_end_inclusive.
            val qrsEndIdxInclusive = min(beatSegment.size - 1, rPeakIdxInSegment + qrsWindowHalfContext)
            val qrsDurationSamples= (qrsEndIdxInclusive - qrsStartIdx)
            features[11] = qrsDurationSamples.toDouble() // qrs_duration (jumlah sampel, fitur ke-4 dari "Peak features" di Python)

            // --- 3. QRS Complex Features ---
            features[12] = (qrsDurationSamples.toDouble() / fs) * 1000.0 // qrs_width (dalam ms)

            var qrsArea = 0.0
            if (qrsStartIdx <= qrsEndIdxInclusive && qrsEndIdxInclusive < beatSegment.size && qrsStartIdx < beatSegment.size -1) {
                val qrsRegion = beatSegment.subList(qrsStartIdx, qrsEndIdxInclusive + 1)
                if (qrsRegion.size > 1) {
                    for (k in 0 until qrsRegion.size - 1) {
                        qrsArea += (abs(qrsRegion[k]) + abs(qrsRegion[k + 1])) / 2.0
                    }
                } else if (qrsRegion.size == 1) {
                    qrsArea = abs(qrsRegion[0]) // Jika hanya 1 titik, area = nilai absolutnya (dx=1)
                }
            }
            features[13] = qrsArea // qrs_area

            // --- 4. P and T wave features ---
            val oneThirdLen = beatSegment.size / 3
            if (oneThirdLen > 0) {
                val pWaveRegion = beatSegment.subList(0, oneThirdLen)
                if (pWaveRegion.isNotEmpty()) {
                    features[14] = pWaveRegion.maxOrNull() ?: 0.0 // p_wave_max
                    features[15] = pWaveRegion.minOrNull() ?: 0.0 // p_wave_min
                    var pArea = 0.0
                    if (pWaveRegion.size > 1) { for (k in 0 until pWaveRegion.size - 1) { pArea += (abs(pWaveRegion[k]) + abs(pWaveRegion[k + 1])) / 2.0 } }
                    else if (pWaveRegion.size == 1) { pArea = abs(pWaveRegion[0])}
                    features[16] = pArea // p_wave_area
                }

                if ((2 * oneThirdLen) < beatSegment.size) {
                    val tWaveRegion = beatSegment.subList(2 * oneThirdLen, beatSegment.size)
                    if (tWaveRegion.isNotEmpty()) {
                        features[17] = tWaveRegion.maxOrNull() ?: 0.0 // t_wave_max
                        features[18] = tWaveRegion.minOrNull() ?: 0.0 // t_wave_min
                        var tArea = 0.0
                        if (tWaveRegion.size > 1) { for (k in 0 until tWaveRegion.size - 1) { tArea += (abs(tWaveRegion[k]) + abs(tWaveRegion[k + 1])) / 2.0 } }
                        else if (tWaveRegion.size == 1) { tArea = abs(tWaveRegion[0])}
                        features[19] = tArea // t_wave_area
                    }
                }
            }


            // --- 5. Slope and Gradient Features (4 fitur) ---
            // Python: beat_diff = np.diff(beat)
            if (beatSegment.size >= 2) {
                val beatDiff = DoubleArray(beatSegment.size - 1) { beatSegment[it + 1] - beatSegment[it] }
                if (beatDiff.isNotEmpty()) {
                    features[20] = beatDiff.maxOrNull() ?: 0.0 // max_positive_slope
                    features[21] = beatDiff.minOrNull() ?: 0.0 // max_negative_slope
                    val meanSlope = beatDiff.average()
                    features[22] = meanSlope // mean_slope
                    features[23] = beatDiff.map { val diff = it - meanSlope; diff * diff }.average() // slope_variance
                }
            }

            // --- 6. Interval Features (2 fitur) ---
            // Python: pre_r_interval = r_peak_idx (indeks R-peak di dalam segmen)
            //         post_r_interval = len(beat) - r_peak_idx
            features[24] = rPeakIdxInSegment.toDouble() // pre_r_interval
            features[25] = (beatSegment.size - rPeakIdxInSegment).toDouble() // Jumlah sampel dari R-peak (inklusif) sampai akhir

            // --- 7. Energy Features (3 fitur) ---
            // Python: signal_energy = np.sum(beat**2)
            //         normalized_energy = signal_energy / len(beat)
            //         rms = np.sqrt(np.mean(beat**2))
            val squaredBeatForEnergy = beatSegment.map { it * it }
            features[26] = squaredBeatForEnergy.sum() // signal_energy
            features[27] = if (beatSegment.isNotEmpty()) features[26] / beatSegment.size else 0.0 // normalized_energy
            features[28] = if (beatSegment.isNotEmpty()) sqrt(squaredBeatForEnergy.average()) else 0.0 // rms

            // --- 8. Other features (Sisa 8 fitur dari Python) ---
            //    Urutan Python: zero_crossings, qrs_p_ratio, qrs_t_ratio, beat_variability,
            //                   symmetry_correlation, time_to_peak, upstroke_area, downstroke_area

            // Zero Crossings (fitur index 29)
            var zeroCrossingsCount = 0
            if (beatSegment.size >= 2) {
                for (k in 0 until beatSegment.size - 1) {
                    if ((beatSegment[k] >= 0 && beatSegment[k+1] < 0) || (beatSegment[k] < 0 && beatSegment[k+1] >= 0)) {
                        if (beatSegment[k] != 0.0 || beatSegment[k+1] != 0.0) zeroCrossingsCount++ // Hitung hanya jika bukan dari 0 ke 0
                    }
                }
            }
            features[29] = zeroCrossingsCount.toDouble()

            // Amplitude Ratios (fitur index 30 & 31)
            val rPeakAmpForRatio = features[9] // r_peak_amplitude
            val pWaveMaxForRatio = features[14] // p_wave_max
            val tWaveMaxForRatio = features[17] // t_wave_max
            val epsilon = 1e-9

            features[30] = if (abs(pWaveMaxForRatio) > 1e-9) rPeakAmpForRatio / pWaveMaxForRatio else if (rPeakAmpForRatio != 0.0) Double.POSITIVE_INFINITY else 0.0 // qrs_p_ratio
            features[31] = if (abs(tWaveMaxForRatio) > 1e-9) rPeakAmpForRatio / tWaveMaxForRatio else if (rPeakAmpForRatio != 0.0) Double.POSITIVE_INFINITY else 0.0 // qrs_t_ratio

            // Beat Variability (fitur index 32)
            val meanAbsBeat = if (beatSegment.isNotEmpty()) beatSegment.map { abs(it) }.average() else 0.0
            features[32] = if (abs(meanAbsBeat) > epsilon) stdVal / meanAbsBeat else 0.0

            // Waveform Symmetry (Correlation)
            val beatCenter = beatSegment.size / 2
            val leftHalf = beatSegment.subList(0, beatCenter)
            val rightHalfOriginal = beatSegment.subList(if (beatSegment.size % 2 == 1) beatCenter + 1 else beatCenter, beatSegment.size)
            val rightHalfReversed = rightHalfOriginal.reversed()
            val minSymmetryLen = min(leftHalf.size, rightHalfReversed.size)
            if (minSymmetryLen > 1) {
                val x = leftHalf.take(minSymmetryLen)
                val y = rightHalfReversed.take(minSymmetryLen)
                val meanX = x.average()
                val meanY = y.average()
                var sumXY = 0.0
                var sumX2 = 0.0
                var sumY2 = 0.0
                for (k in 0 until minSymmetryLen) {
                    sumXY += (x[k] - meanX) * (y[k] - meanY)
                    sumX2 += (x[k] - meanX).pow(2.0)
                    sumY2 += (y[k] - meanY).pow(2.0)
                }
                val denominator = sqrt(sumX2 * sumY2)
                features[33] = if (denominator > epsilon) sumXY / denominator else 0.0
            } else {
                features[33] = 0.0
            }


            // Time to Peak (fitur index 34) - Sama dengan r_peak_position
            features[34] = features[10]

            // Upstroke Area (fitur index 35)
            var upstrokeArea = 0.0
            if (rPeakIdxInSegment > 0) {
                val upstrokeRegion = beatSegment.subList(0, rPeakIdxInSegment) // eksklusif r_peak_idx
                if (upstrokeRegion.size > 1) { for (k in 0 until upstrokeRegion.size -1) { upstrokeArea += (upstrokeRegion[k] + upstrokeRegion[k+1]) / 2.0 }}
                else if (upstrokeRegion.size == 1) { upstrokeArea = upstrokeRegion[0]}
            }
            features[35] = upstrokeArea

            // Downstroke Area (fitur index 36)
            var downstrokeArea = 0.0
            if (rPeakIdxInSegment < beatSegment.size -1 ) {
                val downstrokeRegion = beatSegment.subList(rPeakIdxInSegment, beatSegment.size)
                if (downstrokeRegion.size > 1) { for (k in 0 until downstrokeRegion.size -1) { downstrokeArea += (downstrokeRegion[k] + downstrokeRegion[k+1]) / 2.0 }}
                else if (downstrokeRegion.size == 1) {downstrokeArea = downstrokeRegion[0]}
            }
            features[36] = downstrokeArea

            Log.d(TAG, "Ekstraksi 37 fitur morfologi selesai.")

        } catch (e: Exception) {
            Log.e(TAG, "Error saat mengekstrak fitur morfologi untuk segmen: ${e.message}", e)
            return DoubleArray(numFeatures) { 0.0 } // Kembalikan array nol jika ada error
        }
        return features
    }

    // --- Fungsi calculateRRIntervals dan calculateHeartRates TETAP SAMA ---
    fun calculateRRIntervals(rPeakIndices: List<Int>, fs: Double): List<Double> {
        val rrIntervalsMs = mutableListOf<Double>()
        if (rPeakIndices.size < 2 || fs <= 0) return rrIntervalsMs
        for (i in 0 until rPeakIndices.size - 1) {
            val intervalSamples = rPeakIndices[i + 1] - rPeakIndices[i]
            if (intervalSamples <= 0) { Log.w(TAG, "Invalid interval: $intervalSamples"); continue }
            rrIntervalsMs.add((intervalSamples.toDouble() / fs) * 1000.0)
        }
        return rrIntervalsMs
    }
    fun calculateHeartRates(rrIntervalsMs: List<Double>): List<Double> {
        return rrIntervalsMs.mapNotNull { if (it > 0) 60000.0 / it else null }
    }
}