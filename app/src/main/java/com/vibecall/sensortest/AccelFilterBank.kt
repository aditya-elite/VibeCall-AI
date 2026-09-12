package com.vibecall.sensortest

data class FilteredAccelOutput(
    val rawX: Float,
    val rawY: Float,
    val rawZ: Float,
    val lowX: Float,
    val lowY: Float,
    val lowZ: Float,
    val bpX: Float,
    val bpY: Float,
    val bpZ: Float
)

/**
 * Stateful dual-path digital filter bank for 3-axis accelerometer data.
 *
 * Path 1: 2nd-order Butterworth LPF (5.0 Hz) to separate gravity and slow hand motion.
 * Path 2: 4th-order cascaded Butterworth bandpass (80.0 Hz HPF + 185.0 Hz LPF)
 *         to isolate vocal cord bone-conducted resonance while attenuating hand tremor and Nyquist noise.
 *
 * Each sample passes through these stateful filters exactly once as it arrives.
 */
class AccelFilterBank(val sampleRateHz: Double = 400.0) {

    companion object {
        const val DEFAULT_SAMPLE_RATE = 400.0
        const val LOWPASS_MOTION_CUTOFF_HZ = 5.0
        const val BANDPASS_LOW_CUTOFF_HZ = 80.0
        const val BANDPASS_HIGH_CUTOFF_HZ = 185.0
        const val WARMUP_SAMPLES_THRESHOLD = 40 // 40 samples at 400 Hz = 100 ms startup transient exclusion
    }

    // Lowpass filter channels for gravity and gross motion (5 Hz)
    private val motionLpX = BiquadDesign.createButterworthLowpass(LOWPASS_MOTION_CUTOFF_HZ, sampleRateHz)
    private val motionLpY = BiquadDesign.createButterworthLowpass(LOWPASS_MOTION_CUTOFF_HZ, sampleRateHz)
    private val motionLpZ = BiquadDesign.createButterworthLowpass(LOWPASS_MOTION_CUTOFF_HZ, sampleRateHz)

    // Stage 1: Highpass filter channels for vocal band (80 Hz)
    private val bpHpX = BiquadDesign.createButterworthHighpass(BANDPASS_LOW_CUTOFF_HZ, sampleRateHz)
    private val bpHpY = BiquadDesign.createButterworthHighpass(BANDPASS_LOW_CUTOFF_HZ, sampleRateHz)
    private val bpHpZ = BiquadDesign.createButterworthHighpass(BANDPASS_LOW_CUTOFF_HZ, sampleRateHz)

    // Stage 2: Lowpass filter channels for vocal band (185 Hz)
    private val bpLpX = BiquadDesign.createButterworthLowpass(BANDPASS_HIGH_CUTOFF_HZ, sampleRateHz)
    private val bpLpY = BiquadDesign.createButterworthLowpass(BANDPASS_HIGH_CUTOFF_HZ, sampleRateHz)
    private val bpLpZ = BiquadDesign.createButterworthLowpass(BANDPASS_HIGH_CUTOFF_HZ, sampleRateHz)

    private var samplesProcessedCount = 0L

    val samplesProcessed: Long
        get() = samplesProcessedCount

    val isWarmedUp: Boolean
        get() = samplesProcessedCount >= WARMUP_SAMPLES_THRESHOLD

    /**
     * Process a single 3-axis accelerometer sample through the stateful filters.
     * Must be called exactly once per raw sensor event.
     */
    fun process(rawX: Float, rawY: Float, rawZ: Float): FilteredAccelOutput {
        val xDouble = rawX.toDouble()
        val yDouble = rawY.toDouble()
        val zDouble = rawZ.toDouble()

        // 1. Path 1: Lowpass for gravity and gross motion
        val lowX = motionLpX.process(xDouble).toFloat()
        val lowY = motionLpY.process(yDouble).toFloat()
        val lowZ = motionLpZ.process(zDouble).toFloat()

        // 2. Path 2: Cascaded Bandpass (HPF 80Hz -> LPF 185Hz)
        val hpX = bpHpX.process(xDouble)
        val hpY = bpHpY.process(yDouble)
        val hpZ = bpHpZ.process(zDouble)

        val bpX = bpLpX.process(hpX).toFloat()
        val bpY = bpLpY.process(hpY).toFloat()
        val bpZ = bpLpZ.process(hpZ).toFloat()

        samplesProcessedCount++

        return FilteredAccelOutput(
            rawX = rawX,
            rawY = rawY,
            rawZ = rawZ,
            lowX = lowX,
            lowY = lowY,
            lowZ = lowZ,
            bpX = bpX,
            bpY = bpY,
            bpZ = bpZ
        )
    }

    /**
     * Reset all filter internal delay states and sample count.
     * Must be called cleanly when a new recording session begins.
     */
    fun reset() {
        motionLpX.reset()
        motionLpY.reset()
        motionLpZ.reset()

        bpHpX.reset()
        bpHpY.reset()
        bpHpZ.reset()

        bpLpX.reset()
        bpLpY.reset()
        bpLpZ.reset()

        samplesProcessedCount = 0L
    }
}
