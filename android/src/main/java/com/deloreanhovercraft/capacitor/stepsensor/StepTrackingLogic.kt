package com.deloreanhovercraft.capacitor.stepsensor

import java.time.Instant
import kotlin.math.max

object StepTrackingLogic {

    /**
     * Floor an Instant to the nearest 30-second boundary.
     * e.g., 10:02:47 -> 10:02:30, 10:03:00 -> 10:03:00
     */
    fun floorTo30Seconds(instant: Instant): Instant {
        val epochSecond = instant.epochSecond
        val floored = epochSecond - (epochSecond % 30)
        return Instant.ofEpochSecond(floored)
    }

    /**
     * Compute phone sensor step delta, handling reboot detection.
     * Returns (delta, newBaseline).
     *
     * @param currentCount Latest cumulative sensor value (-1 if no reading yet)
     * @param lastCount Previous cumulative sensor value (-1 if first interval)
     * @return Pair(stepDelta, newLastCount)
     */
    fun computeSensorDelta(currentCount: Long, lastCount: Long): Pair<Long, Long> {
        if (currentCount < 0) return Pair(0L, lastCount)

        val delta = if (lastCount >= 0) {
            if (currentCount >= lastCount) {
                currentCount - lastCount
            } else {
                // Reboot detected — counter reset
                0L
            }
        } else {
            // First reading — establish baseline
            0L
        }

        return Pair(delta, currentCount)
    }

    /**
     * Merge phone sensor delta and Health Connect delta using MAX strategy.
     * Avoids double-counting when both sources record the same steps.
     */
    fun mergeStepSources(phoneDelta: Long, hcDelta: Long): Int {
        return max(phoneDelta, hcDelta).toInt()
    }

    /**
     * Compute the bucket boundaries for a timer tick.
     * The bucket END is the floor of `now`, and START is 30s before that.
     * This means the bucket covers the 30 seconds that just elapsed.
     *
     * @return Pair(bucketStart, bucketEnd)
     */
    fun computeBucketBoundaries(now: Instant): Pair<Instant, Instant> {
        val bucketEnd = floorTo30Seconds(now)
        val bucketStart = bucketEnd.minusSeconds(30)
        return Pair(bucketStart, bucketEnd)
    }

    /**
     * Merge overlapping or adjacent tracking windows.
     * Input must be sorted by startAt.
     * Adjacent windows (one's endAt == another's startAt) are merged.
     */
    fun mergeOverlapping(windows: List<TrackingWindow>): List<TrackingWindow> {
        if (windows.isEmpty()) return emptyList()

        val result = mutableListOf<TrackingWindow>()
        var current = windows[0]

        for (i in 1 until windows.size) {
            val next = windows[i]
            if (!next.startAt.isAfter(current.endAt)) {
                // Overlapping or adjacent — merge
                current = TrackingWindow(
                    startAt = current.startAt,
                    endAt = if (next.endAt.isAfter(current.endAt)) next.endAt else current.endAt
                )
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return result
    }

    /**
     * Filter windows: skip past windows, keep windows where endAt > now.
     * Sort by startAt.
     */
    fun filterAndSortWindows(windows: List<TrackingWindow>, now: Instant): List<TrackingWindow> {
        return windows
            .filter { it.endAt.isAfter(now) }
            .sortedBy { it.startAt }
    }
}
