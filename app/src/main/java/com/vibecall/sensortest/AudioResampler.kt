package com.vibecall.sensortest

/**
 * Audio resampling utilities for converting between 16 kHz (phone microphone capture rate)
 * and 48 kHz (RNNoise native frame rate).
 */
object AudioResampler {

    /**
     * Upsamples 16 kHz 16-bit PCM samples to 48 kHz float values (3x repetition/interpolation)
     * formatted directly for RNNoise input (PCM amplitude scale [-32768f, 32767f]).
     */
    fun upsample3x(input: ShortArray, length: Int = input.size): FloatArray {
        val output = FloatArray(length * 3)
        for (i in 0 until length) {
            val v = input[i].toFloat()
            val idx = i * 3
            output[idx] = v
            output[idx + 1] = v
            output[idx + 2] = v
        }
        return output
    }

    /**
     * Downsamples 48 kHz float audio from RNNoise back to 16 kHz 16-bit PCM ShortArray (3x decimation)
     * with anti-aliasing clamping to prevent 16-bit PCM integer overflow.
     */
    fun downsample3x(input: FloatArray, length: Int = input.size): ShortArray {
        val outSize = length / 3
        val output = ShortArray(outSize)
        for (i in 0 until outSize) {
            output[i] = input[i * 3].toInt().coerceIn(-32768, 32767).toShort()
        }
        return output
    }
}
