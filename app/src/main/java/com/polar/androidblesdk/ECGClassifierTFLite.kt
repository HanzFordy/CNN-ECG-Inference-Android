package com.polar.androidblesdk // Sesuaikan dengan package Anda

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class ECGClassifierTFLite(context: Context, modelFileName: String = "morphology_cnn_final.tflite") {

    private var interpreter: Interpreter? = null
    private var isInitialized = false

    // Dimensi input dan output yang diharapkan oleh model TFLite
    // Ini akan kita dapatkan dari interpreter setelah model dimuat, atau bisa di-hardcode jika sudah pasti
    private var cnnInputShape: IntArray = intArrayOf(1, 180, 1) // Default, akan diverifikasi
    private var morphInputShape: IntArray = intArrayOf(1, 37)    // Default, akan diverifikasi
    private var outputShape: IntArray = intArrayOf(1, 5)       // Default, akan diverifikasi

    private val classNames = arrayOf("Normal", "SVEB", "VEB", "Fusion", "Unknown") // Sesuaikan

    companion object {
        private const val TAG = "EcgClassifierTFLite"
    }

    init {
        try {
            val modelBuffer = loadModelFile(context.assets, modelFileName)
            val options = Interpreter.Options()
            // options.setNumThreads(2) // Opsional
            // options.setUseNNAPI(true) // Opsional
            interpreter = Interpreter(modelBuffer, options)
            isInitialized = true
            Log.i(TAG, "Model TFLite ($modelFileName) berhasil dimuat.")

            // Verifikasi dan simpan shape tensor setelah interpreter dibuat
            logTensorDetails()

        } catch (e: IOException) {
            isInitialized = false
            Log.e(TAG, "Error memuat model TFLite: ${e.message}", e)
        }
    }

    private fun logTensorDetails() {
        interpreter?.let { interp ->
            val numInputs = interp.inputTensorCount
            val numOutputs = interp.outputTensorCount
            Log.d(TAG, "Model TFLite: Inputs=$numInputs, Outputs=$numOutputs")

            if (numInputs >= 2) { // Asumsi kita punya minimal 2 input
                val inputTensor0 = interp.getInputTensor(0) // Asumsi urutan: 0=CNN, 1=Morph
                cnnInputShape = inputTensor0.shape()
                Log.d(TAG, "Input 0 (CNN): Name='${inputTensor0.name()}', Shape=${cnnInputShape.joinToString()}, DataType=${inputTensor0.dataType()}")

                val inputTensor1 = interp.getInputTensor(1)
                morphInputShape = inputTensor1.shape()
                Log.d(TAG, "Input 1 (Morph): Name='${inputTensor1.name()}', Shape=${morphInputShape.joinToString()}, DataType=${inputTensor1.dataType()}")
            } else {
                Log.e(TAG, "Jumlah input tensor kurang dari 2, periksa model TFLite.")
            }

            if (numOutputs >= 1) {
                val outputTensor0 = interp.getOutputTensor(0)
                outputShape = outputTensor0.shape()
                Log.d(TAG, "Output 0: Name='${outputTensor0.name()}', Shape=${outputShape.joinToString()}, DataType=${outputTensor0.dataType()}")
            }
        }
    }


    @Throws(IOException::class)
    private fun loadModelFile(assetManager: AssetManager, modelFileName: String): ByteBuffer {
        val fileDescriptor = assetManager.openFd(modelFileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Menjalankan inferensi pada satu pasang data (segmen ECG dan fitur morfologi).
     * @param ecgSegment List<Double> berisi 180 sampel sinyal ECG.
     * @param scaledMorphFeatures FloatArray berisi 37 fitur morfologi yang sudah di-scale.
     * @return Pair<Int, FloatArray?>: Indeks kelas prediksi dan array probabilitas, atau Pair(-1, null) jika error.
     */
    fun classify(ecgSegment: List<Double>, scaledMorphFeatures: FloatArray): Pair<Int, FloatArray?> {
        if (!isInitialized || interpreter == null) {
            Log.e(TAG, "Interpreter TFLite belum diinisialisasi.")
            return Pair(-1, null)
        }
        if (ecgSegment.size != cnnInputShape[1] || scaledMorphFeatures.size != morphInputShape[1]) {
            Log.e(TAG, "Ukuran input tidak sesuai! ECG: ${ecgSegment.size} vs ${cnnInputShape[1]}, Morph: ${scaledMorphFeatures.size} vs ${morphInputShape[1]}")
            return Pair(-1, null)
        }

        try {
            // A. Siapkan ByteBuffer untuk Input Sinyal ECG (misal, 1x180x1 Float32)
            val cnnInputByteBuffer = ByteBuffer.allocateDirect(cnnInputShape.reduce { acc, i -> acc * i } * 4) // 1*180*1*4
            cnnInputByteBuffer.order(ByteOrder.nativeOrder())
            ecgSegment.forEach { cnnInputByteBuffer.putFloat(it.toFloat()) }
            cnnInputByteBuffer.rewind()

            // B. Siapkan ByteBuffer untuk Input Fitur Morfologi (misal, 1x37 Float32)
            val morphInputByteBuffer = ByteBuffer.allocateDirect(morphInputShape.reduce { acc, i -> acc * i } * 4) // 1*37*4
            morphInputByteBuffer.order(ByteOrder.nativeOrder())
            scaledMorphFeatures.forEach { morphInputByteBuffer.putFloat(it) }
            morphInputByteBuffer.rewind()

            // C. Siapkan array input untuk interpreter
            val inputsArray = arrayOfNulls<Any>(2)
            // Penting: Sesuaikan urutan ini jika urutan input model TFLite Anda berbeda!
            // Verifikasi dengan logTensorDetails() atau Netron.
            // Asumsi Input 0 adalah CNN (raw_input), Input 1 adalah Morfologi.
            inputsArray[0] = cnnInputByteBuffer
            inputsArray[1] = morphInputByteBuffer


            // D. Siapkan map output untuk interpreter
            val outputProbabilitiesBuffer = Array(outputShape[0]) { FloatArray(outputShape[1]) } // Misal 1x5
            val outputsMap = mutableMapOf<Int, Any>()
            outputsMap[0] = outputProbabilitiesBuffer

            // E. Jalankan Inferensi
            interpreter!!.runForMultipleInputsOutputs(inputsArray, outputsMap)

            // F. Dapatkan Hasil Prediksi
            val probabilities = outputProbabilitiesBuffer[0]
            val predictedClassIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: -1

            return Pair(predictedClassIndex, probabilities)

        } catch (e: Exception) {
            Log.e(TAG, "Error saat menjalankan inferensi: ${e.message}", e)
            return Pair(-1, null)
        }
    }

    /**
     * Melepaskan resource interpreter TFLite.
     * Panggil ini saat classifier tidak lagi dibutuhkan (misalnya di onDestroy Activity).
     */

    fun isReady(): Boolean { // <-- Fungsi getter publik baru
        return this.isInitialized && this.interpreter != null
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        isInitialized = false
        Log.i(TAG, "Interpreter TFLite ditutup.")
    }

    fun getClassName(predictedClassIndex: Int): String {
        return if (predictedClassIndex >= 0 && predictedClassIndex < classNames.size) {
            classNames[predictedClassIndex]
        } else {
            "Error/Unknown"
        }
    }
}