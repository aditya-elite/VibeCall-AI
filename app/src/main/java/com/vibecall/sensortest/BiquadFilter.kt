package com.vibecall.sensortest

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Transposed Direct Form II Biquad Filter implementation.
 * Provides numerically stable single-channel filtering with zero allocation per sample.
 */
class BiquadChannel(
    var b0: Double = 1.0,
    var b1: Double = 0.0,
    var b2: Double = 0.0,
    var a1: Double = 0.0,
    var a2: Double = 0.0
) {
    private var s1: Double = 0.0
    private var s2: Double = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + s1
        s1 = b1 * x - a1 * y + s2
        s2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        s1 = 0.0
        s2 = 0.0
    }

    fun setCoefficients(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
        this.b0 = b0
        this.b1 = b1
        this.b2 = b2
        this.a1 = a1
        this.a2 = a2
    }
}

object BiquadDesign {

    /**
     * 2nd-order Butterworth High-Pass Filter design via bilinear transform.
     */
    fun createButterworthHighpass(fc: Double, fs: Double): BiquadChannel {
        val w0 = 2.0 * Math.PI * fc / fs
        val alpha = sin(w0) / sqrt(2.0)
        val a0 = 1.0 + alpha
        val cosW0 = cos(w0)
        return BiquadChannel(
            b0 = (1.0 + cosW0) / 2.0 / a0,
            b1 = -(1.0 + cosW0) / a0,
            b2 = (1.0 + cosW0) / 2.0 / a0,
            a1 = -2.0 * cosW0 / a0,
            a2 = (1.0 - alpha) / a0
        )
    }

    /**
     * 2nd-order Butterworth Low-Pass Filter design via bilinear transform.
     */
    fun createButterworthLowpass(fc: Double, fs: Double): BiquadChannel {
        val w0 = 2.0 * Math.PI * fc / fs
        val alpha = sin(w0) / sqrt(2.0)
        val a0 = 1.0 + alpha
        val cosW0 = cos(w0)
        return BiquadChannel(
            b0 = (1.0 - cosW0) / 2.0 / a0,
            b1 = (1.0 - cosW0) / a0,
            b2 = (1.0 - cosW0) / 2.0 / a0,
            a1 = -2.0 * cosW0 / a0,
            a2 = (1.0 - alpha) / a0
        )
    }
}
