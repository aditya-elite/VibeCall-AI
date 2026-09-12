package com.vibecall.sensortest

import org.junit.Assert.assertEquals
import org.junit.Test

class RnnoiseDurationTest {

    private val passThroughAdapter = RnnoiseNativeAdapter { inFrame48k, outFrame48k ->
        System.arraycopy(inFrame48k, 0, outFrame48k, 0, 480)
    }

    @Test
    fun testWavDurationSampleCountPreservation() {
        val processor = RnnoiseProcessor(passThroughAdapter)

        // Test various realistic stream chunk sizes (e.g. 2048 samples = 128ms @ 16kHz)
        val totalTestSamples = 16000 // 1 second of audio @ 16 kHz
        val chunkSize = 2048
        val input = ShortArray(totalTestSamples) { (it % 1000).toShort() }

        var processedCount = 0
        var offset = 0

        while (offset < totalTestSamples) {
            val length = Math.min(chunkSize, totalTestSamples - offset)
            val chunk = ShortArray(length)
            System.arraycopy(input, offset, chunk, 0, length)

            val out = processor.processStream(chunk, length)
            processedCount += out.size
            offset += length
        }

        // Flush remaining trailing frame
        val flushed = processor.flush()
        processedCount += flushed.size

        processor.close()

        // Assert that the total processed samples from processStream + flush
        // exactly matches the input sample count (16000 == 16000)
        assertEquals("RNNoise output sample count must exactly match Raw input sample count", totalTestSamples, processedCount)
    }

    @Test
    fun testArbitraryLengthDurationPreservation() {
        val processor = RnnoiseProcessor(passThroughAdapter)

        // Test an odd, arbitrary length not aligned to 160 or 480
        val arbitrarySamples = 5437
        val input = ShortArray(arbitrarySamples) { 100 }

        val out = processor.processStream(input, arbitrarySamples)
        val flushed = processor.flush()
        val totalOut = out.size + flushed.size

        processor.close()

        // Input 5437 upsampled 3x = 16311 samples.
        // After downsampling /3 = 5437 samples.
        assertEquals("RNNoise must preserve arbitrary sample count within 1 sample", arbitrarySamples, totalOut)
    }
}
