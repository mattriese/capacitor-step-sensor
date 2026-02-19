package com.deloreanhovercraft.capacitor.stepsensor

import java.time.Instant
import kotlin.math.max

data class HcStepRecord(
    val startTime: Instant,
    val endTime: Instant,
    val count: Long,
    val dataOrigin: String
)

object StepTrackingLogic {

    const val MAX_STEPS_PER_BUCKET = 90

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

    /**
     * Find the TrackingWindow containing `now`.
     * startAt is inclusive, endAt is exclusive.
     */
    fun findActiveWindow(windows: List<TrackingWindow>, now: Instant): TrackingWindow? {
        return windows.find { !now.isBefore(it.startAt) && now.isBefore(it.endAt) }
    }

    /**
     * Subtract-and-fill: distribute HC surplus into empty buckets within the commitment window.
     *
     * Full inclusion policy: the entire hcCount is used (not proportional to in-window overlap).
     * Only buckets within [commitmentStart, commitmentEnd) receive steps.
     * Each zero-step bucket is capped at perBucketCap. Overflow goes to the last
     * in-window bucket uncapped, to avoid losing steps.
     *
     * @return Map of bucketStart → final step count for that bucket
     */
    fun subtractAndFill(
        recordStart: Instant,
        recordEnd: Instant,
        hcCount: Long,
        existingBuckets: Map<Instant, Int>,
        commitmentStart: Instant,
        commitmentEnd: Instant,
        perBucketCap: Int = MAX_STEPS_PER_BUCKET
    ): Map<Instant, Int> {
        if (hcCount <= 0 || !recordEnd.isAfter(recordStart)) return emptyMap()

        // Enumerate all 30s buckets this HC record covers
        val allBuckets = mutableListOf<Instant>()
        var cursor = floorTo30Seconds(recordStart)
        while (cursor.isBefore(recordEnd)) {
            allBuckets.add(cursor)
            cursor = cursor.plusSeconds(30)
        }
        if (allBuckets.isEmpty()) return emptyMap()

        // Clamp to commitment window — only keep buckets in [commitmentStart, commitmentEnd)
        val inWindowBuckets = allBuckets.filter { bucket ->
            !bucket.isBefore(commitmentStart) && bucket.isBefore(commitmentEnd)
        }
        if (inWindowBuckets.isEmpty()) return emptyMap()

        // Sum phone steps in in-window buckets
        val phoneTotal = inWindowBuckets.sumOf { existingBuckets[it] ?: 0 }

        // Full inclusion: use entire hcCount, not proportional
        val surplus = max(0L, hcCount - phoneTotal)
        if (surplus == 0L) return emptyMap()

        // Find zero-step in-window buckets
        val zeroBuckets = inWindowBuckets.filter { (existingBuckets[it] ?: 0) == 0 }

        val result = mutableMapOf<Instant, Int>()

        if (zeroBuckets.isNotEmpty()) {
            val totalCapacity = zeroBuckets.size.toLong() * perBucketCap

            if (surplus <= totalCapacity) {
                // Fits within caps — distribute evenly
                val perBucket = (surplus / zeroBuckets.size).toInt()
                val remainder = (surplus % zeroBuckets.size).toInt()
                for ((i, bucket) in zeroBuckets.withIndex()) {
                    result[bucket] = perBucket + if (i < remainder) 1 else 0
                }
            } else {
                // Overflow: fill each zero bucket to cap
                for (bucket in zeroBuckets) {
                    result[bucket] = perBucketCap
                }
                // Leftover to last in-window bucket (uncapped)
                val leftover = (surplus - totalCapacity).toInt()
                val lastBucket = inWindowBuckets.last()
                val existing = existingBuckets[lastBucket] ?: 0
                result[lastBucket] = (result[lastBucket] ?: existing) + leftover
            }
        } else {
            // No zero buckets: all surplus to last in-window bucket (uncapped)
            val lastBucket = inWindowBuckets.last()
            val existing = existingBuckets[lastBucket] ?: 0
            result[lastBucket] = existing + surplus.toInt()
        }

        return result
    }

    /**
     * Process HC records: group by dataOrigin, run subtractAndFill per source,
     * MAX across sources per bucket.
     */
    fun processHcRecords(
        records: List<HcStepRecord>,
        existingBuckets: Map<Instant, Int>,
        commitmentStart: Instant,
        commitmentEnd: Instant,
        perBucketCap: Int = MAX_STEPS_PER_BUCKET
    ): Map<Instant, Int> {
        if (records.isEmpty()) return emptyMap()

        val bySource = records.groupBy { it.dataOrigin }

        val result = mutableMapOf<Instant, Int>()
        for ((_, sourceRecords) in bySource) {
            val sourceBuckets = mutableMapOf<Instant, Int>()
            for (record in sourceRecords) {
                val filled = subtractAndFill(
                    record.startTime, record.endTime, record.count,
                    existingBuckets, commitmentStart, commitmentEnd, perBucketCap
                )
                for ((bucketStart, steps) in filled) {
                    sourceBuckets[bucketStart] = max(sourceBuckets[bucketStart] ?: 0, steps)
                }
            }
            for ((bucketStart, steps) in sourceBuckets) {
                result[bucketStart] = max(result[bucketStart] ?: 0, steps)
            }
        }
        return result
    }
}
