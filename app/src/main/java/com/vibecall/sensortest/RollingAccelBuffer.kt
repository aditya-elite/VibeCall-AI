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
    val windowDurationNs: Long = DEFAULT_WINDOW_DURATION_NS // 350 ms (retains >= 300 ms)
) {
    companion object {
        const val DEFAULT_WINDOW_DURATION_NS = 350_000_000L // 350 ms
        const val DURATION_100MS_NS = 100_000_000L // 100 ms
        const val DURATION_250MS_NS = 250_000_000L // 250 ms
        const val MAX_CAPACITY = 512
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
     * Extracts an immutable snapshot of all samples currently in the rolling buffer.
     */
    @Synchronized
    fun getSnapshot(): List<FilteredAccelSample> {
        return deque.toList()
    }

    /**
     * Extracts a snapshot of samples ending at or before targetTimestampNs,
     * covering [targetTimestampNs - durationNs, targetTimestampNs].
     * Samples strictly after targetTimestampNs are excluded (no future samples).
     */
    @Synchronized
    fun getSnapshotEndingAt(targetTimestampNs: Long, durationNs: Long = windowDurationNs): List<FilteredAccelSample> {
        val minTs = targetTimestampNs - durationNs
        return deque.filter { it.timestampNs in minTs..targetTimestampNs }
    }

    /**
     * Extracts a 100 ms snapshot ending at or before targetTimestampNs.
     */
    @Synchronized
    fun getSnapshot100msEndingAt(targetTimestampNs: Long): List<FilteredAccelSample> {
        return getSnapshotEndingAt(targetTimestampNs, DURATION_100MS_NS)
    }

    /**
     * Extracts a 250 ms snapshot ending at or before targetTimestampNs.
     */
    @Synchronized
    fun getSnapshot250msEndingAt(targetTimestampNs: Long): List<FilteredAccelSample> {
        return getSnapshotEndingAt(targetTimestampNs, DURATION_250MS_NS)
    }

    /**
     * Returns the timestamp of the latest sample currently in the buffer, or 0 if empty.
     */
    @Synchronized
    fun latestTimestampNs(): Long {
        return if (deque.isNotEmpty()) deque.last.timestampNs else 0L
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
