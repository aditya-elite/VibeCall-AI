package com.vibecall.sensortest

import cn.enaium.rnnoise.createRnnoise
import java.util.ArrayList

/**
 * Wraps RNNoise for real-time denoising. RNNoise requires 48 kHz audio,
 * processed in fixed 10 ms frames (480 samples per frame).
 * Our phone recording is 16 kHz, so audio is upsampled before RNNoise
 * and downsampled after.
 */
class RnnoiseProcessor : AutoCloseable {

    companion object {
        const val FRAME_SIZE_48K = 480
        const val FRAME_SIZE_16K = 160
    }

    private val rnnoise = createRnnoise()
    val frameSize: Int = rnnoise.frameSize

    // Temporary reusable buffers to minimize GC allocations during streaming
    private val inFrame48k = FloatArray(FRAME_SIZE_48K)
    private val outFrame48k = FloatArray(FRAME_SIZE_48K)

    // Accumulator for 48kHz float samples between microphone read chunks
    private var buffer48k = FloatArray(FRAME_SIZE_48K * 4)
    private var buffer48kCount = 0

    /**
     * Denoise a single 480-sample frame of 48kHz float audio.
     * Returns denoised frame.
     */
    fun processFrame(frame48k: FloatArray): FloatArray {
        require(frame48k.size == FRAME_SIZE_48K) {
            "RNNoise requires exactly $FRAME_SIZE_48K samples (10ms @ 48kHz)"
        }
        return rnnoise.processFrame(frame48k)
    }

    /**
     * Streams 16 kHz PCM audio chunks through RNNoise.
     * Upsamples 16kHz -> 48kHz, denoises in exact 480-sample frames,
     * and downsamples back to 16kHz.
     * Retains leftover samples (<480 @ 48kHz) in an internal buffer so no
     * audio is dropped or phase-shifted between chunks.
     *
     * @param input16k ShortArray containing 16kHz PCM samples
     * @param length Number of valid samples in input16k
     * @return Denoised 16kHz PCM ShortArray
     */
    @Synchronized
    fun processStream(input16k: ShortArray, length: Int = input16k.size): ShortArray {
        if (length <= 0) return ShortArray(0)

        val upsampled = AudioResampler.upsample3x(input16k, length)
        val neededCapacity = buffer48kCount + upsampled.size
        if (neededCapacity > buffer48k.size) {
            val newCap = maxOf(buffer48k.size * 2, neededCapacity + FRAME_SIZE_48K)
            val newBuf = FloatArray(newCap)
            System.arraycopy(buffer48k, 0, newBuf, 0, buffer48kCount)
            buffer48k = newBuf
        }
        System.arraycopy(upsampled, 0, buffer48k, buffer48kCount, upsampled.size)
        buffer48kCount += upsampled.size

        val numFrames = buffer48kCount / FRAME_SIZE_48K
        if (numFrames == 0) return ShortArray(0)

        val output16k = ShortArray(numFrames * FRAME_SIZE_16K)
        var outOffset16k = 0

        for (f in 0 until numFrames) {
            val offset = f * FRAME_SIZE_48K
            System.arraycopy(buffer48k, offset, inFrame48k, 0, FRAME_SIZE_48K)
            rnnoise.processFrame(inFrame48k, outFrame48k)

            // Downsample 48kHz -> 16kHz (every 3rd sample)
            for (i in 0 until FRAME_SIZE_16K) {
                output16k[outOffset16k + i] = outFrame48k[i * 3]
                    .toInt()
                    .coerceIn(-32768, 32767)
                    .toShort()
            }
            outOffset16k += FRAME_SIZE_16K
        }

        // Shift remaining unprocessed samples to beginning of buffer
        val processedSamples = numFrames * FRAME_SIZE_48K
        val remaining = buffer48kCount - processedSamples
        if (remaining > 0) {
            System.arraycopy(buffer48k, processedSamples, buffer48k, 0, remaining)
        }
        buffer48kCount = remaining

        return output16k
    }

    /**
     * Flushes any remaining samples in the accumulator when recording ends.
     * Pads with silence to complete the final 480-sample frame, then downsamples
     * only the corresponding 16kHz samples.
     */
    @Synchronized
    fun flush(): ShortArray {
        if (buffer48kCount <= 0) return ShortArray(0)

        // Zero-fill inFrame48k and copy the leftover
        inFrame48k.fill(0f)
        System.arraycopy(buffer48k, 0, inFrame48k, 0, buffer48kCount)
        rnnoise.processFrame(inFrame48k, outFrame48k)

        val outputSamples16k = (buffer48kCount + 2) / 3
        val output16k = ShortArray(outputSamples16k)
        for (i in 0 until outputSamples16k) {
            output16k[i] = outFrame48k[i * 3]
                .toInt()
                .coerceIn(-32768, 32767)
                .toShort()
        }
        buffer48kCount = 0
        return output16k
    }

    override fun close() {
        rnnoise.close()
    }
}
