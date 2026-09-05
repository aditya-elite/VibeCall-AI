package com.vibecall.sensortest

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Loads and runs the tiny fusion-gate model on the NPU (via NNAPI delegate).
 * Input:  [audioVariance, accelX, accelY, accelZ]  (4 floats)
 * Output: a single float from 0 to 1 — how much to trust/boost the mic signal
 */
class FusionGateModel(context: Context) {

    private var nnApiDelegate: NnApiDelegate? = null
    private var interpreter: Interpreter? = null

    init {
        val modelBuffer = loadModelFile(context, "fusion_gate_model.tflite")
        try {
            val delegate = NnApiDelegate()
            nnApiDelegate = delegate
            val options = Interpreter.Options().addDelegate(delegate)
            interpreter = Interpreter(modelBuffer, options)
            Log.i(TAG, "FusionGateModel initialized with NNAPI delegate (NPU acceleration active).")
        } catch (e: Exception) {
            Log.w(TAG, "NNAPI delegate initialization failed; falling back to CPU interpreter.", e)
            interpreter = Interpreter(modelBuffer)
        }
    }

    fun getTrustValue(audioVariance: Float, accelX: Float, accelY: Float, accelZ: Float): Float {
        val input = arrayOf(floatArrayOf(audioVariance, accelX, accelY, accelZ))
        val output = Array(1) { FloatArray(1) }
        interpreter?.run(input, output)
        return output[0][0]
    }

    fun close() {
        interpreter?.close()
        nnApiDelegate?.close()
        interpreter = null
        nnApiDelegate = null
    }

    private fun loadModelFile(context: Context, filename: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(filename)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    companion object {
        private const val TAG = "FusionGateModel"
    }
}
