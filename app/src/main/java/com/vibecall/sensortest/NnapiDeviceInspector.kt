package com.vibecall.sensortest

import android.util.Log

/**
 * Step 6 NNAPI Device Information model.
 *
 * Mapped strictly from Android NDK ANeuralNetworksDevice_* APIs:
 * - [name]: ANeuralNetworksDevice_getName
 * - [type]: CPU, GPU, ACCELERATOR, DSP, NPU, or OTHER
 * - [version]: ANeuralNetworksDevice_getVersion
 * - [featureLevel]: ANeuralNetworksDevice_getFeatureLevel
 * - [isCpu]: true if device is CPU or nnapi-reference
 * - [isSelected]: true if this device is selected for execution
 */
data class NnapiDeviceInfo @JvmOverloads constructor(
    val name: String,
    val type: String,
    val version: String,
    val featureLevel: Long,
    val isCpu: Boolean,
    val isSelected: Boolean = false
) {
    fun toFormattedDisplayString(): String {
        val cpuLabel = if (isCpu) "CPU" else "NON-CPU"
        val selectedLabel = if (isSelected) "SELECTED" else "NOT SELECTED"
        return "• $name (Type: $type, Ver: $version) [$cpuLabel] [$selectedLabel]"
    }
}

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
     * Enumerates available NNAPI devices on this hardware using Android NDK APIs:
     * - ANeuralNetworks_getDeviceCount
     * - ANeuralNetworks_getDevice
     * - ANeuralNetworksDevice_getName
     * - ANeuralNetworksDevice_getType
     * - ANeuralNetworksDevice_getVersion
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
     * Logs every available NNAPI device with:
     * - Device name
     * - Device type
     * - Driver/version information
     * - Whether it is CPU or non-CPU
     * - Whether it is selected
     */
    fun logAndTagDevices(
        devices: List<NnapiDeviceInfo>,
        selectedDeviceName: String? = null
    ): List<NnapiDeviceInfo> {
        val tagged = devices.map { dev ->
            val isSel = selectedDeviceName != null && dev.name.equals(selectedDeviceName, ignoreCase = true)
            dev.copy(isSelected = isSel)
        }
        Log.i(TAG, "===== NNAPI Device Enumeration (${tagged.size} devices found) =====")
        if (tagged.isEmpty()) {
            Log.w(TAG, "  No NNAPI devices discovered via libneuralnetworks.so.")
        } else {
            tagged.forEachIndexed { idx, dev ->
                Log.i(
                    TAG,
                    "  Device [$idx]: Name='${dev.name}', Type='${dev.type}', Driver/Version='${dev.version}', " +
                        "FeatureLevel=${dev.featureLevel}, IsCpu=${dev.isCpu}, IsSelected=${dev.isSelected}"
                )
            }
        }
        Log.i(TAG, "==================================================================")
        return tagged
    }

    /**
     * Searches for a genuine non-CPU Qualcomm accelerator, such as an NPU, DSP, HTP,
     * or dedicated accelerator device.
     *
     * Explicitly rejects CPU devices, "nnapi-reference", and any device tagged as isCpu.
     */
    fun searchQualcommNonCpuAccelerator(devices: List<NnapiDeviceInfo> = getAvailableDevices()): NnapiDeviceInfo? {
        val candidates = devices.filter { !it.isCpu && it.type != "CPU" && !it.name.contains("reference", ignoreCase = true) }

        // 1. Primary search: Qualcomm / QTI dedicated NPU, DSP, or HTP accelerator
        val qualcommAcc = candidates.firstOrNull { dev ->
            val lowerName = dev.name.lowercase()
            (lowerName.contains("qti") || lowerName.contains("qualcomm") || lowerName.contains("snapdragon")) &&
                (lowerName.contains("npu") || lowerName.contains("htp") || lowerName.contains("dsp") ||
                    lowerName.contains("hta") || lowerName.contains("accelerator"))
        }
        if (qualcommAcc != null) return qualcommAcc

        // 2. Secondary search: Any device identified as NPU, DSP, or HTP
        val anyNpuOrDsp = candidates.firstOrNull { dev ->
            val lowerName = dev.name.lowercase()
            lowerName.contains("npu") || lowerName.contains("htp") || lowerName.contains("dsp") ||
                dev.type == "NPU" || dev.type == "DSP"
        }
        if (anyNpuOrDsp != null) return anyNpuOrDsp

        // 3. Tertiary search: Any non-CPU ACCELERATOR
        val genericAccelerator = candidates.firstOrNull { it.type == "ACCELERATOR" }
        if (genericAccelerator != null) return genericAccelerator

        // 4. Quaternary search: GPU device
        val gpu = candidates.firstOrNull { it.type == "GPU" }
        if (gpu != null) return gpu

        return candidates.firstOrNull()
    }

    /**
     * Alias for [searchQualcommNonCpuAccelerator].
     */
    fun findBestNonCpuDevice(devices: List<NnapiDeviceInfo> = getAvailableDevices()): NnapiDeviceInfo? {
        return searchQualcommNonCpuAccelerator(devices)
    }
}
