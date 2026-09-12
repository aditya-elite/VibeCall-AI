package com.vibecall.sensortest

import android.util.Log

/**
 * Step 6 NNAPI Device Information model.
 *
 * Mapped strictly from Android NDK ANeuralNetworksDevice_* APIs:
 * - [name]: ANeuralNetworksDevice_getName
 * - [type]: CPU, GPU, ACCELERATOR, DSP, or OTHER
 * - [version]: ANeuralNetworksDevice_getVersion
 * - [featureLevel]: ANeuralNetworksDevice_getFeatureLevel
 * - [isCpu]: true if device is CPU or nnapi-reference
 */
data class NnapiDeviceInfo(
    val name: String,
    val type: String,
    val version: String,
    val featureLevel: Long,
    val isCpu: Boolean
)

object NnapiDeviceInspector {
    private const val TAG = "NnapiDeviceInspector"
    private var nativeLibraryLoaded = false

    init {
        try {
            System.loadLibrary("nnapi_inspector")
            nativeLibraryLoaded = true
            runCatching { Log.i(TAG, "libnnapi_inspector.so loaded successfully.") }
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "Could not load libnnapi_inspector.so: ${t.message}") }
        }
    }

    @JvmStatic
    private external fun nativeGetDevices(): Array<NnapiDeviceInfo>?

    /**
     * Enumerates available NNAPI devices on this hardware.
     */
    fun getAvailableDevices(): List<NnapiDeviceInfo> {
        if (!nativeLibraryLoaded) {
            return emptyList()
        }
        return try {
            nativeGetDevices()?.toList() ?: emptyList()
        } catch (t: Throwable) {
            runCatching { Log.e(TAG, "Failed to enumerate NNAPI devices: ${t.message}", t) }
            emptyList()
        }
    }

    /**
     * Identifies the best non-CPU accelerator candidate.
     * Prefers dedicated ACCELERATOR / DSP / NPU over GPU.
     * Excludes CPU and nnapi-reference.
     */
    fun findBestNonCpuDevice(devices: List<NnapiDeviceInfo> = getAvailableDevices()): NnapiDeviceInfo? {
        val nonCpus = devices.filter { !it.isCpu && it.type != "CPU" && !it.name.contains("reference", ignoreCase = true) }
        // Preference: ACCELERATOR/DSP/NPU > GPU > OTHER
        return nonCpus.firstOrNull { it.type == "NPU" || it.type == "ACCELERATOR" || it.type == "DSP" }
            ?: nonCpus.firstOrNull { it.type == "GPU" }
            ?: nonCpus.firstOrNull()
    }
}
