package com.deloreanhovercraft.capacitor.stepsensor

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

data class HcStepRecord(
    val recordId: String,
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
     * Each zero-step bucket is capped at perBucketCap. Surplus beyond total
     * capacity is discarded — it represents steps that can't be physiologically
     * attributed to the available time window.
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
        perBucketCap: Int = MAX_STEPS_PER_BUCKET,
        now: Instant = Instant.now()
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

        // Clamp to commitment window AND exclude future buckets
        // Only keep buckets in [commitmentStart, min(commitmentEnd, now))
        val inWindowBuckets = allBuckets.filter { bucket ->
            !bucket.isBefore(commitmentStart) && bucket.isBefore(commitmentEnd) && bucket.isBefore(now)
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
            // Clamp surplus to total capacity — anything beyond is discarded
            val totalCapacity = zeroBuckets.size.toLong() * perBucketCap
            val clampedSurplus = minOf(surplus, totalCapacity)
            val perBucket = (clampedSurplus / zeroBuckets.size).toInt()
            val remainder = (clampedSurplus % zeroBuckets.size).toInt()
            for ((i, bucket) in zeroBuckets.withIndex()) {
                result[bucket] = perBucket + if (i < remainder) 1 else 0
            }
        }
        // If no zero buckets, surplus is discarded — all buckets already have
        // phone sensor data and we can't meaningfully add watch steps on top.

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
        perBucketCap: Int = MAX_STEPS_PER_BUCKET,
        now: Instant = Instant.now()
    ): Map<Instant, Int> {
        if (records.isEmpty()) return emptyMap()

        val bySource = records.groupBy { it.dataOrigin }

        val result = mutableMapOf<Instant, Int>()
        for ((_, sourceRecords) in bySource) {
            val sourceBuckets = mutableMapOf<Instant, Int>()
            for (record in sourceRecords) {
                val filled = subtractAndFill(
                    record.startTime, record.endTime, record.count,
                    existingBuckets, commitmentStart, commitmentEnd, perBucketCap, now
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

    /**
     * Compute phone steps in [rangeStart, rangeEnd) with prorated edge buckets.
     * Each bucket covers 30s starting at its key. If a bucket only partially
     * overlaps the range, its steps are multiplied by the overlap fraction.
     */
    fun computeProratedPhoneSteps(
        existingBuckets: Map<Instant, Int>,
        rangeStart: Instant,
        rangeEnd: Instant
    ): Double {
        if (!rangeEnd.isAfter(rangeStart)) return 0.0

        var total = 0.0
        for ((bucketStart, steps) in existingBuckets) {
            val bucketEnd = bucketStart.plusSeconds(30)

            // Skip buckets with no overlap
            if (!bucketEnd.isAfter(rangeStart) || !rangeEnd.isAfter(bucketStart)) continue

            val overlapStart = if (rangeStart.isAfter(bucketStart)) rangeStart else bucketStart
            val overlapEnd = if (rangeEnd.isBefore(bucketEnd)) rangeEnd else bucketEnd
            val overlapSeconds = Duration.between(overlapStart, overlapEnd).seconds.toDouble()
            val fraction = overlapSeconds / 30.0
            total += steps * fraction
        }
        return total
    }

    /**
     * Distribute surplus steps into empty 30s buckets within [rangeStart, rangeEnd).
     * Buckets are clamped to [floor(rangeStart), min(rangeEnd, now)).
     * Zero-step buckets get even distribution with per-bucket cap.
     * Surplus beyond total capacity is discarded — it represents steps that
     * can't be physiologically attributed to the available time window
     * (e.g., a large HC delta from a cumulative record catching up).
     */
    fun distributeWatchSurplus(
        surplus: Long,
        existingBuckets: Map<Instant, Int>,
        rangeStart: Instant,
        rangeEnd: Instant,
        perBucketCap: Int = MAX_STEPS_PER_BUCKET,
        now: Instant = Instant.now()
    ): Map<Instant, Int> {
        if (surplus <= 0 || !rangeEnd.isAfter(rangeStart)) return emptyMap()

        val effectiveEnd = if (now.isBefore(rangeEnd)) now else rangeEnd

        // Enumerate 30s buckets in [floor(rangeStart), effectiveEnd)
        val buckets = mutableListOf<Instant>()
        var cursor = floorTo30Seconds(rangeStart)
        while (cursor.isBefore(effectiveEnd)) {
            buckets.add(cursor)
            cursor = cursor.plusSeconds(30)
        }
        if (buckets.isEmpty()) return emptyMap()

        val zeroBuckets = buckets.filter { (existingBuckets[it] ?: 0) == 0 }
        val result = mutableMapOf<Instant, Int>()

        if (zeroBuckets.isNotEmpty()) {
            // Clamp surplus to total capacity — anything beyond is discarded
            val totalCapacity = zeroBuckets.size.toLong() * perBucketCap
            val clampedSurplus = minOf(surplus, totalCapacity)
            val perBucket = (clampedSurplus / zeroBuckets.size).toInt()
            val remainder = (clampedSurplus % zeroBuckets.size).toInt()
            for ((i, bucket) in zeroBuckets.withIndex()) {
                result[bucket] = perBucket + if (i < remainder) 1 else 0
            }
        }
        // If no zero buckets, surplus is discarded — all buckets already have
        // phone sensor data and we can't meaningfully add watch steps on top.

        return result
    }

    fun serializeHcRecords(records: List<HcStepRecord>): String {
        val jsonArray = JSONArray()
        for (record in records) {
            val obj = JSONObject().apply {
                put("recordId", record.recordId)
                put("startTime", record.startTime.toString())
                put("endTime", record.endTime.toString())
                put("count", record.count)
                put("dataOrigin", record.dataOrigin)
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }
}
