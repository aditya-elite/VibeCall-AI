package com.vibecall.sensortest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioResamplerTest {

    @Test
    fun testUpsample3xExactSizeAndValues() {
        val input = shortArrayOf(100, -200, 300)
        val upsampled = AudioResampler.upsample3x(input)

        // 3 samples * 3 = 9 samples
        assertEquals(9, upsampled.size)

        // Verify repeated samples and amplitude
        assertEquals(100f, upsampled[0], 0.001f)
        assertEquals(100f, upsampled[1], 0.001f)
        assertEquals(100f, upsampled[2], 0.001f)
        assertEquals(-200f, upsampled[3], 0.001f)
        assertEquals(-200f, upsampled[4], 0.001f)
        assertEquals(-200f, upsampled[5], 0.001f)
        assertEquals(300f, upsampled[6], 0.001f)
        assertEquals(300f, upsampled[7], 0.001f)
        assertEquals(300f, upsampled[8], 0.001f)
    }

    @Test
    fun testDownsample3xDecimationAndClamping() {
        val input = floatArrayOf(
            500f, 500f, 500f,
            40000f, 40000f, 40000f, // Exceeds Short.MAX_VALUE (32767) -> should clamp
            -40000f, -40000f, -40000f // Exceeds Short.MIN_VALUE (-32768) -> should clamp
        )
        val downsampled = AudioResampler.downsample3x(input)

        assertEquals(3, downsampled.size)
        assertEquals(500.toShort(), downsampled[0])
        assertEquals(32767.toShort(), downsampled[1])
        assertEquals((-32768).toShort(), downsampled[2])
    }

    @Test
    fun testRoundTripSizeIntegrity() {
        val original16k = ShortArray(1600) { (it % 1000).toShort() }
        val upsampled48k = AudioResampler.upsample3x(original16k)
        assertEquals(4800, upsampled48k.size)

        val downsampled16k = AudioResampler.downsample3x(upsampled48k)
        assertEquals(1600, downsampled16k.size)

        for (i in original16k.indices) {
            assertEquals(original16k[i], downsampled16k[i])
        }
    }
}
