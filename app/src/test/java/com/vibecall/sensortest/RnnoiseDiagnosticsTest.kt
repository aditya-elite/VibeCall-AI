package com.vibecall.sensortest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RnnoiseDiagnosticsTest {

    @Test
    fun testFrameAndFailureCountersWithMockAdapter() {
        var callCount = 0L
        val mockAdapter = RnnoiseNativeAdapter { inFrame48k, outFrame48k ->
            callCount++
            if (callCount == 3L) {
                throw RuntimeException("Simulated native JNI failure")
            }
            // Simple gain reduction to simulate suppression
            for (i in 0 until 480) {
                outFrame48k[i] = inFrame48k[i] * 0.5f
            }
        }

        val processor = RnnoiseProcessor(mockAdapter)

        assertEquals(0L, processor.getFrameCount())
        assertEquals(0L, processor.getFailureCount())

        // 480 samples at 48kHz = 1 RNNoise frame
        val singleFrame = FloatArray(480) { 1000f }
        val out1 = processor.processFrame(singleFrame)
        assertEquals(480, out1.size)
        assertEquals(1L, processor.getFrameCount())
        assertEquals(0L, processor.getFailureCount())

        val out2 = processor.processFrame(singleFrame)
        assertEquals(480, out2.size)
        assertEquals(2L, processor.getFrameCount())
        assertEquals(0L, processor.getFailureCount())

        // Third frame throws simulation exception
        val out3 = processor.processFrame(singleFrame)
        assertEquals(480, out3.size) // Fallback to raw frame copy on failure
        assertEquals(2L, processor.getFrameCount())
        assertEquals(1L, processor.getFailureCount())

        processor.close()
    }

    @Test
    fun testStreamProcessingDiagnosticCounts() {
        val mockAdapter = RnnoiseNativeAdapter { inFrame48k, outFrame48k ->
            System.arraycopy(inFrame48k, 0, outFrame48k, 0, 480)
        }
        val processor = RnnoiseProcessor(mockAdapter)

        // Process 1600 samples (10 frames of 160 samples at 16kHz)
        val stream = ShortArray(1600) { 500 }
        val out = processor.processStream(stream, 1600)
        val flushed = processor.flush()

        assertEquals(1600, out.size + flushed.size)
        assertEquals(10L, processor.getFrameCount())
        assertEquals(0L, processor.getFailureCount())

        processor.close()
    }
}
