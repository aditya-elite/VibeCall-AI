package com.vibecall.sensortest

import java.util.ArrayDeque

/**
 * Thread-safe rolling accelerometer buffer storing timestamped filtered samples
 * over a sliding 100 ms duration window.
 *
 * All operations are synchronized to safely handle concurrent access between
 * the sensor callback thread (writing) and audio recording executor (reading).
 */
class RollingAccelBuffer(
    val windowDurationNs: Long = 100_000_000L // 100 ms window
) {
    companion object {
        const val DEFAULT_WINDOW_DURATION_NS = 100_000_000L // 100 ms
        const val MAX_CAPACITY = 256
    }

    private val deque = ArrayDeque<FilteredAccelSample>(MAX_CAPACITY)

    @Synchronized
    fun add(sample: FilteredAccelSample) {
        deque.addLast(sample)
        evictOlderThan(sample.timestampNs - windowDurationNs)
    }

    @Synchronized
    fun evictOlderThan(cutoffTimestampNs: Long) {
        while (deque.isNotEmpty() && deque.first.timestampNs < cutoffTimestampNs) {
            deque.removeFirst()
        }
    }

    /**
     * Extracts an immutable snapshot of all samples currently in the rolling window.
     */
    @Synchronized
    fun getSnapshot(): List<FilteredAccelSample> {
        return deque.toList()
    }

    /**
     * Extracts a snapshot of samples ending at or before a specified timestamp,
     * covering the 100 ms window [targetTimestampNs - windowDurationNs, targetTimestampNs].
     */
    @Synchronized
    fun getSnapshotEndingAt(targetTimestampNs: Long): List<FilteredAccelSample> {
        val minTs = targetTimestampNs - windowDurationNs
        return deque.filter { it.timestampNs in minTs..targetTimestampNs }
    }

    @Synchronized
    fun size(): Int {
        return deque.size
    }

    @Synchronized
    fun isEmpty(): Boolean {
        return deque.isEmpty()
    }

    @Synchronized
    fun clear() {
        deque.clear()
    }
}

data class FilteredAccelSample(
    val timestampNs: Long,
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
